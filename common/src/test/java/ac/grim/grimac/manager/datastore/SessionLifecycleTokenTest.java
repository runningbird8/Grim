package ac.grim.grimac.manager.datastore;

import ac.grim.grimac.api.storage.DataStore;
import ac.grim.grimac.internal.storage.identity.PlayerIdentityService;
import com.github.retrooper.packetevents.protocol.player.User;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionLifecycleTokenTest {
    private static final SessionTracker.ClientMeta META = SessionTracker.ClientMeta.empty();

    @Test
    void staleGenerationCannotCloseItsReplacement() {
        UUID playerUuid = UUID.randomUUID();
        SessionTracker tracker = new SessionTrackerImpl(dataStore(), "test", 0);

        UUID firstGeneration = tracker.open(playerUuid, 10, META);
        UUID replacementGeneration = tracker.open(playerUuid, 20, META);

        assertNotEquals(firstGeneration, replacementGeneration);
        assertFalse(tracker.close(playerUuid, firstGeneration, 30, META));
        assertEquals(replacementGeneration, tracker.currentSessionId(playerUuid));
        assertTrue(tracker.close(playerUuid, replacementGeneration, 40, META));
        assertNull(tracker.currentSessionId(playerUuid));
        assertFalse(tracker.close(playerUuid, replacementGeneration, 50, META));
    }

    @Test
    void userDisconnectsOnlyCloseTheirCompletedJoinGeneration() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        DataStore store = dataStore();
        SessionTracker tracker = new SessionTrackerImpl(store, "test", 0);
        LiveWriteHooksImpl hooks = new LiveWriteHooksImpl(store, new PlayerIdentityService(store), null, tracker);
        User firstUser = user();
        User queuedUnpublishedUser = user();
        User replacementUser = user();

        // A queued login that disconnects before its JOIN continuation ran has
        // no binding and must not close the already-current first connection.
        hooks.onJoinFromUserLogin(firstUser, playerUuid, "first", 10, META);
        UUID firstGeneration = tracker.currentSessionId(playerUuid);
        hooks.onQuitFromUserDisconnect(queuedUnpublishedUser, 20, META);
        assertEquals(firstGeneration, tracker.currentSessionId(playerUuid));

        // A completed replacement gets a new token. The delayed first QUIT is
        // unable to remove it, whereas the replacement's own QUIT closes it.
        hooks.onJoinFromUserLogin(replacementUser, playerUuid, "replacement", 30, META);
        UUID replacementGeneration = tracker.currentSessionId(playerUuid);
        assertNotEquals(firstGeneration, replacementGeneration);
        hooks.onQuitFromUserDisconnect(firstUser, 40, META);
        assertEquals(replacementGeneration, tracker.currentSessionId(playerUuid));
        hooks.onQuitFromUserDisconnect(replacementUser, 50, META);
        assertNull(tracker.currentSessionId(playerUuid));

        // Disconnect callbacks can be repeated by PacketEvents/shutdown paths.
        hooks.onQuitFromUserDisconnect(replacementUser, 60, META);
        assertNull(tracker.currentSessionId(playerUuid));
    }

    @Test
    void activityCannotCreateAProvisionalOrReopenAClosedSession() {
        UUID playerUuid = UUID.randomUUID();
        SessionTracker tracker = new SessionTrackerImpl(dataStore(), "test", 0);

        assertNull(tracker.observeActivity(playerUuid, UUID.randomUUID(), 5, META));
        assertNull(tracker.currentSessionId(playerUuid));

        UUID token = tracker.open(playerUuid, 10, META);
        assertEquals(token, tracker.observeActivity(playerUuid, token, 20, META));
        assertTrue(tracker.close(playerUuid, token, 30, META));
        assertNull(tracker.observeActivity(playerUuid, token, 40, META));
        assertNull(tracker.currentSessionId(playerUuid));
    }

    @Test
    void heartbeatSubmissionCompletesBeforeExactCloseSubmission() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        BlockingStore store = new BlockingStore(2);
        SessionTracker tracker = new SessionTrackerImpl(store.dataStore(), "test", 1);
        UUID sessionId = tracker.open(playerUuid, 10, META);

        Thread heartbeat = new Thread(
                () -> tracker.pollHeartbeat(playerUuid, sessionId, 20), "session-heartbeat");
        heartbeat.start();
        store.awaitBlocked();
        AtomicReference<Boolean> closed = new AtomicReference<>();
        Thread close = new Thread(
                () -> closed.set(tracker.close(playerUuid, sessionId, 30, META)), "session-close");
        close.start();

        Thread.sleep(25);
        assertTrue(close.isAlive());
        assertEquals(2, store.submitCalls.get());
        store.release();
        heartbeat.join(5_000);
        close.join(5_000);

        assertFalse(heartbeat.isAlive());
        assertFalse(close.isAlive());
        assertEquals(Boolean.TRUE, closed.get());
        assertEquals(3, store.submitCalls.get());
        assertNull(tracker.currentSessionId(playerUuid));
    }

    @Test
    void overlappingOpensSubmitTheFirstOpenBeforeItsCloseAndReplacementOpen() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        BlockingStore store = new BlockingStore(1);
        SessionTracker tracker = new SessionTrackerImpl(store.dataStore(), "test", 0);
        AtomicReference<UUID> firstToken = new AtomicReference<>();
        AtomicReference<UUID> replacementToken = new AtomicReference<>();
        Thread first = new Thread(
                () -> firstToken.set(tracker.open(playerUuid, 10, META)), "first-session-open");
        first.start();
        store.awaitBlocked();
        Thread replacement = new Thread(
                () -> replacementToken.set(tracker.open(playerUuid, 20, META)), "replacement-session-open");
        replacement.start();

        Thread.sleep(25);
        assertTrue(replacement.isAlive());
        assertEquals(1, store.submitCalls.get());
        store.release();
        first.join(5_000);
        replacement.join(5_000);

        assertFalse(first.isAlive());
        assertFalse(replacement.isAlive());
        assertNotEquals(firstToken.get(), replacementToken.get());
        assertEquals(replacementToken.get(), tracker.currentSessionId(playerUuid));
        assertEquals(3, store.submitCalls.get());
    }

    private static DataStore dataStore() {
        return (DataStore) Proxy.newProxyInstance(
                DataStore.class.getClassLoader(),
                new Class<?>[] {DataStore.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("submit")) {
                        return null;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static final class BlockingStore {
        private final int blockedCall;
        private final AtomicInteger submitCalls = new AtomicInteger();
        private final CountDownLatch blocked = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        private BlockingStore(int blockedCall) {
            this.blockedCall = blockedCall;
        }

        private DataStore dataStore() {
            return (DataStore) Proxy.newProxyInstance(
                    DataStore.class.getClassLoader(), new Class<?>[] {DataStore.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("submit")) {
                            int call = submitCalls.incrementAndGet();
                            if (call == blockedCall) {
                                blocked.countDown();
                                if (!released.await(5, TimeUnit.SECONDS)) {
                                    throw new AssertionError("timed out waiting to release datastore submission");
                                }
                            }
                            return null;
                        }
                        return defaultValue(method.getReturnType());
                    });
        }

        private void awaitBlocked() throws InterruptedException {
            assertTrue(blocked.await(5, TimeUnit.SECONDS));
        }

        private void release() {
            released.countDown();
        }
    }

    private static User user() throws ReflectiveOperationException {
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
        return (User) unsafe.allocateInstance(User.class);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        return null;
    }
}
