package ac.grim.grimac.utils.anticheat;

import ac.grim.grimac.manager.SpectateManager;
import ac.grim.grimac.platform.api.player.PlatformPlayer;
import ac.grim.grimac.platform.api.world.PlatformWorld;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.math.Location;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerDataManagerTest {
    @Test
    void alertCleanupFailureStillRunsSpectateCleanupAndInvalidatesFactoryCache() {
        AtomicBoolean spectateCleaned = new AtomicBoolean();
        AtomicBoolean invalidated = new AtomicBoolean();

        assertThrows(IllegalStateException.class, () -> PlayerDataManager.runWithPlayerInvalidation(
                () -> { throw new IllegalStateException("alert cleanup failed"); },
                () -> spectateCleaned.set(true),
                () -> invalidated.set(true)));

        assertTrue(spectateCleaned.get());
        assertTrue(invalidated.get());
    }

    @Test
    void untrackedDisconnectCleansNativeOwnedLifecycleAndHideState() throws Exception {
        assertUntrackedDisconnectCleansLifecycle(false);
    }

    @Test
    void untrackedDisconnectCleansWrapperFallbackOwnedLifecycleAndHideState() throws Exception {
        assertUntrackedDisconnectCleansLifecycle(true);
    }

    @Test
    void trackedDisconnectUsesItsRegisteredConnectionBinding() throws Exception {
        UUID uuid = UUID.randomUUID();
        SpectateManager spectateManager = spectateManager();
        FakePlayer fake = new FakePlayer(uuid, false);
        User user = user(uuid, "tracked");
        DisconnectServices services = new DisconnectServices(spectateManager);
        PlayerDataManager playerDataManager = new PlayerDataManager(services);
        GrimPlayer grimPlayer = grimPlayer(fake.player, fake.nativeIdentity);
        addTrackedPlayer(playerDataManager, user, grimPlayer);
        playerDataManager.onUserLogin(user, fake.player, fake.nativeIdentity);
        spectateManager.onLogin(fake.player, user);
        assertEquals(SpectateManager.StartResult.STARTED,
                spectateManager.enable(fake.player, fake.target));

        playerDataManager.onDisconnect(user);

        assertEquals(1, services.quitEvents.get());
        assertEquals(1, services.quitWrites.get());
        assertEquals(1, services.toggleEvictions.get());
        assertEquals(1, services.alertCleanups.get());
        assertEquals(1, services.spectateCleanups.get());
        assertEquals(1, services.invalidations.get());
        assertFalse(spectateManager.isLifecycleActive(uuid));
        assertFalse(isHidden(spectateManager, uuid));
        assertEquals(GameMode.CREATIVE, fake.gameMode);
    }

    @Test
    void trackedDisconnectBeforeUserLoginCleansOnlyItsCapturedLifecycle() throws Exception {
        UUID uuid = UUID.randomUUID();
        SpectateManager spectateManager = spectateManager();
        FakePlayer fake = new FakePlayer(uuid, false);
        User user = user(uuid, "before-login");
        DisconnectServices services = new DisconnectServices(spectateManager);
        PlayerDataManager playerDataManager = new PlayerDataManager(services);
        addTrackedPlayer(playerDataManager, user, grimPlayer(fake.player, fake.nativeIdentity));
        spectateManager.onLogin(fake.player, user);
        assertEquals(SpectateManager.StartResult.STARTED,
                spectateManager.enable(fake.player, fake.target));

        playerDataManager.onDisconnect(user);
        playerDataManager.onDisconnect(user);

        assertEquals(1, services.quitEvents.get());
        assertEquals(1, services.quitWrites.get());
        assertEquals(0, services.toggleEvictions.get());
        assertEquals(1, services.alertCleanups.get());
        assertEquals(1, services.spectateCleanups.get());
        assertEquals(1, services.invalidations.get());
        assertFalse(spectateManager.isLifecycleActive(uuid));
        assertFalse(isHidden(spectateManager, uuid));
        assertEquals(GameMode.CREATIVE, fake.gameMode);
    }

    @Test
    void immediateLoginContinuationsAreSerializedAcrossReplacementPublication() throws Exception {
        UUID uuid = UUID.randomUUID();
        DisconnectServices services = new DisconnectServices(spectateManager());
        PlayerDataManager playerDataManager = new PlayerDataManager(services);
        FakePlayer first = new FakePlayer(uuid, false);
        FakePlayer replacement = new FakePlayer(uuid, false);
        User firstUser = user(uuid, "first-login");
        User replacementUser = user(uuid, "replacement-login");
        CountDownLatch firstContinuationSelected = new CountDownLatch(1);
        CountDownLatch releaseFirstContinuation = new CountDownLatch(1);
        List<String> effects = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        services.beforeLoginContinuation = user -> {
            if (user != firstUser) return;
            firstContinuationSelected.countDown();
            try {
                if (!releaseFirstContinuation.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("timed out waiting to release first login continuation");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        };
        Thread firstLogin = new Thread(() -> {
            try {
                playerDataManager.onUserLogin(firstUser, first.player, first.nativeIdentity,
                        () -> true, () -> effects.add("first"));
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        firstLogin.start();
        assertTrue(firstContinuationSelected.await(5, TimeUnit.SECONDS));

        PlayerDataManager.LoginResult replacementResult = playerDataManager.onUserLogin(
                replacementUser, replacement.player, replacement.nativeIdentity, () -> true,
                () -> effects.add("replacement"));

        assertEquals(PlayerDataManager.LoginResult.DEFERRED, replacementResult);
        assertTrue(effects.isEmpty());
        releaseFirstContinuation.countDown();
        firstLogin.join(5_000);
        assertFalse(firstLogin.isAlive());
        assertEquals(null, failure.get());
        assertEquals(List.of("replacement"), effects);
        assertTrue(hasConnectionBinding(playerDataManager, replacementUser));
        assertTrue(hasCurrentConnection(playerDataManager, uuid));
    }

    @Test
    void disconnectAfterContinuationSelectionRecordsQuitAfterJoinContinuation() throws Exception {
        UUID uuid = UUID.randomUUID();
        DisconnectServices services = new DisconnectServices(spectateManager());
        PlayerDataManager playerDataManager = new PlayerDataManager(services);
        FakePlayer player = new FakePlayer(uuid, false);
        User user = user(uuid, "selected-then-disconnected");
        AtomicInteger quitWritesObservedByContinuation = new AtomicInteger(-1);
        services.afterLoginContinuationSelected = selected -> {
            if (selected == user) playerDataManager.onDisconnect(user);
        };

        PlayerDataManager.LoginResult result = playerDataManager.onUserLogin(
                user, player.player, player.nativeIdentity, () -> true,
                () -> quitWritesObservedByContinuation.set(services.quitWrites.get()));

        assertEquals(PlayerDataManager.LoginResult.PUBLISHED, result);
        assertEquals(0, quitWritesObservedByContinuation.get());
        assertEquals(1, services.quitWrites.get());
        assertEquals(1, services.toggleEvictions.get());
        assertEquals(1, services.alertCleanups.get());
        assertEquals(1, services.spectateCleanups.get());
        assertEquals(1, services.invalidations.get());
        assertFalse(hasConnectionBinding(playerDataManager, user));
        assertFalse(hasCurrentConnection(playerDataManager, uuid));
    }

    @Test
    void staleOldSpectateTeardownCannotRemoveReplacementHideLifecycle() throws Exception {
        UUID uuid = UUID.randomUUID();
        SpectateManager spectateManager = spectateManager();
        DisconnectServices services = new DisconnectServices(spectateManager);
        PlayerDataManager playerDataManager = new PlayerDataManager(services);
        FakePlayer old = new FakePlayer(uuid, false);
        FakePlayer replacement = new FakePlayer(uuid, false);
        User oldUser = user(uuid, "old-spectator");
        User replacementUser = user(uuid, "replacement-spectator");
        playerDataManager.onUserLogin(oldUser, old.player, old.nativeIdentity);
        spectateManager.onLogin(old.player, oldUser);
        assertEquals(SpectateManager.StartResult.STARTED,
                spectateManager.enable(old.player, old.target));
        playerDataManager.onUserLogin(replacementUser, replacement.player, replacement.nativeIdentity);
        spectateManager.onLogin(replacement.player, replacementUser);

        playerDataManager.onDisconnect(oldUser);

        assertEquals(0, services.toggleEvictions.get());
        assertEquals(0, services.alertCleanups.get());
        assertEquals(1, services.spectateCleanups.get());
        assertEquals(1, services.invalidations.get());
        assertFalse(spectateManager.isLifecycleActive(uuid));
        assertTrue(isHidden(spectateManager, uuid));
        assertEquals(GameMode.CREATIVE, old.gameMode);
        assertTrue(hasConnectionBinding(playerDataManager, replacementUser));
        assertTrue(hasCurrentConnection(playerDataManager, uuid));
    }

    @Test
    void untrackedDisconnectBeforeUserLoginOnlyRecordsQuit() throws Exception {
        UUID uuid = UUID.randomUUID();
        DisconnectServices services = new DisconnectServices(spectateManager());
        PlayerDataManager playerDataManager = new PlayerDataManager(services);

        playerDataManager.onDisconnect(user(uuid, "untracked-before-login"));

        assertEquals(0, services.quitEvents.get());
        assertEquals(1, services.quitWrites.get());
        assertEquals(0, services.toggleEvictions.get());
        assertEquals(0, services.alertCleanups.get());
        assertEquals(0, services.spectateCleanups.get());
        assertEquals(0, services.invalidations.get());
    }

    @Test
    void staleLoginCannotRegressTheReplacementBinding() throws Exception {
        UUID uuid = UUID.randomUUID();
        DisconnectServices services = new DisconnectServices(spectateManager());
        PlayerDataManager playerDataManager = new PlayerDataManager(services);
        FakePlayer replacement = new FakePlayer(uuid, false);
        FakePlayer stale = new FakePlayer(uuid, false);
        User replacementUser = user(uuid, "replacement");
        User staleUser = user(uuid, "stale");

        assertTrue(playerDataManager.onUserLogin(replacementUser, replacement.player, replacement.nativeIdentity));
        assertFalse(playerDataManager.onUserLogin(staleUser, stale.player, stale.nativeIdentity, () -> false));

        playerDataManager.onDisconnect(staleUser);
        assertEquals(1, services.quitWrites.get());
        assertEquals(0, services.toggleEvictions.get());

        playerDataManager.onDisconnect(replacementUser);
        assertEquals(2, services.quitWrites.get());
        assertEquals(1, services.toggleEvictions.get());
        assertEquals(1, services.alertCleanups.get());
        assertEquals(1, services.spectateCleanups.get());
        assertEquals(1, services.invalidations.get());
    }

    @Test
    void reentrantLoginFromToggleEvictionDefersItsEntireContinuation() throws Exception {
        UUID uuid = UUID.randomUUID();
        DisconnectServices services = new DisconnectServices(spectateManager());
        PlayerDataManager playerDataManager = new PlayerDataManager(services);
        FakePlayer old = new FakePlayer(uuid, false);
        FakePlayer replacement = new FakePlayer(uuid, false);
        User oldUser = user(uuid, "old");
        User replacementUser = user(uuid, "replacement");
        AtomicReference<PlayerDataManager.LoginResult> result = new AtomicReference<>();
        AtomicInteger continuationRuns = new AtomicInteger();
        AtomicInteger runsObservedInsideEviction = new AtomicInteger(-1);
        AtomicInteger invalidationsObservedByContinuation = new AtomicInteger(-1);
        playerDataManager.onUserLogin(oldUser, old.player, old.nativeIdentity);
        services.onEvictToggle = () -> {
            result.set(playerDataManager.onUserLogin(replacementUser, replacement.player,
                    replacement.nativeIdentity, () -> true, () -> {
                        invalidationsObservedByContinuation.set(services.invalidations.get());
                        continuationRuns.incrementAndGet();
                    }));
            runsObservedInsideEviction.set(continuationRuns.get());
        };

        playerDataManager.onDisconnect(oldUser);

        assertEquals(PlayerDataManager.LoginResult.DEFERRED, result.get());
        assertEquals(0, runsObservedInsideEviction.get());
        assertEquals(1, continuationRuns.get());
        assertEquals(1, invalidationsObservedByContinuation.get());
        services.onEvictToggle = null;
        playerDataManager.onDisconnect(replacementUser);
        assertEquals(2, services.toggleEvictions.get());
        assertEquals(2, services.invalidations.get());
    }

    @Test
    void reentrantLoginsFromUnboundAlertAndSpectateCleanupCoalesceToLatest() throws Exception {
        UUID uuid = UUID.randomUUID();
        DisconnectServices services = new DisconnectServices(spectateManager());
        PlayerDataManager playerDataManager = new PlayerDataManager(services);
        FakePlayer old = new FakePlayer(uuid, false);
        FakePlayer firstReplacement = new FakePlayer(uuid, false);
        FakePlayer latestReplacement = new FakePlayer(uuid, false);
        User oldUser = user(uuid, "old-unbound");
        User firstUser = user(uuid, "first-replacement");
        User latestUser = user(uuid, "latest-replacement");
        AtomicReference<PlayerDataManager.LoginResult> alertResult = new AtomicReference<>();
        AtomicReference<PlayerDataManager.LoginResult> spectateResult = new AtomicReference<>();
        AtomicInteger firstRuns = new AtomicInteger();
        AtomicInteger latestRuns = new AtomicInteger();
        AtomicInteger runsObservedInAlert = new AtomicInteger(-1);
        AtomicInteger runsObservedInSpectate = new AtomicInteger(-1);
        addTrackedPlayer(playerDataManager, oldUser, grimPlayer(old.player, old.nativeIdentity));
        services.onAlertCleanup = () -> {
            alertResult.set(playerDataManager.onUserLogin(firstUser, firstReplacement.player,
                    firstReplacement.nativeIdentity, () -> true, firstRuns::incrementAndGet));
            runsObservedInAlert.set(firstRuns.get() + latestRuns.get());
        };
        services.onSpectateCleanup = () -> {
            spectateResult.set(playerDataManager.onUserLogin(latestUser, latestReplacement.player,
                    latestReplacement.nativeIdentity, () -> true, latestRuns::incrementAndGet));
            runsObservedInSpectate.set(firstRuns.get() + latestRuns.get());
        };

        playerDataManager.onDisconnect(oldUser);

        assertEquals(PlayerDataManager.LoginResult.DEFERRED, alertResult.get());
        assertEquals(PlayerDataManager.LoginResult.DEFERRED, spectateResult.get());
        assertEquals(0, runsObservedInAlert.get());
        assertEquals(0, runsObservedInSpectate.get());
        assertEquals(0, firstRuns.get());
        assertEquals(1, latestRuns.get());
        services.onAlertCleanup = null;
        services.onSpectateCleanup = null;
        playerDataManager.onDisconnect(latestUser);
        assertEquals(2, services.invalidations.get());
    }

    @Test
    void staleDeferredLoginDropsWithoutPublishingOrRunningContinuation() throws Exception {
        UUID uuid = UUID.randomUUID();
        DisconnectServices services = new DisconnectServices(spectateManager());
        PlayerDataManager playerDataManager = new PlayerDataManager(services);
        FakePlayer old = new FakePlayer(uuid, false);
        FakePlayer stale = new FakePlayer(uuid, false);
        User oldUser = user(uuid, "old");
        User staleUser = user(uuid, "stale-deferred");
        AtomicReference<PlayerDataManager.LoginResult> result = new AtomicReference<>();
        AtomicInteger continuationRuns = new AtomicInteger();
        playerDataManager.onUserLogin(oldUser, old.player, old.nativeIdentity);
        services.onEvictToggle = () -> result.set(playerDataManager.onUserLogin(staleUser, stale.player,
                stale.nativeIdentity, () -> false, continuationRuns::incrementAndGet));

        playerDataManager.onDisconnect(oldUser);

        assertEquals(PlayerDataManager.LoginResult.DEFERRED, result.get());
        assertEquals(0, continuationRuns.get());
        int toggleEvictions = services.toggleEvictions.get();
        int invalidations = services.invalidations.get();
        services.onEvictToggle = null;
        playerDataManager.onDisconnect(staleUser);
        assertEquals(toggleEvictions, services.toggleEvictions.get());
        assertEquals(invalidations, services.invalidations.get());
    }

    @Test
    void staleFirstGenerationDisconnectCannotCleanThirdGenerationDuringDrain() throws Exception {
        UUID uuid = UUID.randomUUID();
        DisconnectServices services = new DisconnectServices(spectateManager());
        PlayerDataManager playerDataManager = new PlayerDataManager(services);
        FakePlayer first = new FakePlayer(uuid, false);
        FakePlayer cleaning = new FakePlayer(uuid, false);
        FakePlayer replacement = new FakePlayer(uuid, false);
        User firstUser = user(uuid, "generation-a");
        User cleaningUser = user(uuid, "generation-b");
        User replacementUser = user(uuid, "generation-c");
        AtomicReference<PlayerDataManager.LoginResult> replacementResult = new AtomicReference<>();
        AtomicInteger replacementContinuationRuns = new AtomicInteger();
        playerDataManager.onUserLogin(firstUser, first.player, first.nativeIdentity);
        playerDataManager.onUserLogin(cleaningUser, cleaning.player, cleaning.nativeIdentity);
        services.onEvictToggle = () -> replacementResult.set(playerDataManager.onUserLogin(
                replacementUser, replacement.player, replacement.nativeIdentity, () -> true, () -> {
                    replacementContinuationRuns.incrementAndGet();
                    playerDataManager.onDisconnect(firstUser);
                }));

        playerDataManager.onDisconnect(cleaningUser);

        assertEquals(PlayerDataManager.LoginResult.DEFERRED, replacementResult.get());
        assertEquals(1, replacementContinuationRuns.get());
        assertEquals(2, services.quitWrites.get());
        assertEquals(1, services.toggleEvictions.get());
        assertEquals(1, services.alertCleanups.get());
        assertEquals(2, services.spectateCleanups.get());
        assertEquals(2, services.invalidations.get());
        assertFalse(hasConnectionBinding(playerDataManager, firstUser));
        assertTrue(hasConnectionBinding(playerDataManager, replacementUser));
        assertTrue(hasCurrentConnection(playerDataManager, uuid));

        services.onEvictToggle = null;
        playerDataManager.onDisconnect(replacementUser);
        assertEquals(2, services.toggleEvictions.get());
        assertEquals(3, services.invalidations.get());
    }

    @Test
    void cleanupExceptionStillReleasesClaimAndDrainsDeferredLogin() throws Exception {
        UUID uuid = UUID.randomUUID();
        DisconnectServices services = new DisconnectServices(spectateManager());
        PlayerDataManager playerDataManager = new PlayerDataManager(services);
        FakePlayer old = new FakePlayer(uuid, false);
        FakePlayer replacement = new FakePlayer(uuid, false);
        User oldUser = user(uuid, "old-cleanup-failure");
        User replacementUser = user(uuid, "replacement-after-failure");
        AtomicInteger continuationRuns = new AtomicInteger();
        playerDataManager.onUserLogin(oldUser, old.player, old.nativeIdentity);
        services.onEvictToggle = () -> playerDataManager.onUserLogin(replacementUser, replacement.player,
                replacement.nativeIdentity, () -> true, continuationRuns::incrementAndGet);
        services.failEvictToggle = true;

        assertThrows(IllegalStateException.class, () -> playerDataManager.onDisconnect(oldUser));

        assertEquals(1, continuationRuns.get());
        assertEquals(1, services.alertCleanups.get());
        assertEquals(1, services.spectateCleanups.get());
        assertEquals(1, services.invalidations.get());
        services.onEvictToggle = null;
        services.failEvictToggle = false;
        playerDataManager.onDisconnect(replacementUser);
        assertEquals(2, services.toggleEvictions.get());
        assertEquals(2, services.alertCleanups.get());
        assertEquals(2, services.spectateCleanups.get());
        assertEquals(2, services.invalidations.get());
    }

    @Test
    void deferredContinuationCanDisconnectItsExactPublishedReplacement() throws Exception {
        assertDeferredContinuationDisconnectsReplacement(false);
    }

    @Test
    void unboundCleanupDrainHandlesDisconnectInsideDeferredContinuation() throws Exception {
        assertDeferredContinuationDisconnectsReplacement(true);
    }

    private static void assertDeferredContinuationDisconnectsReplacement(boolean unboundCleanup) throws Exception {
        UUID uuid = UUID.randomUUID();
        DisconnectServices services = new DisconnectServices(spectateManager());
        PlayerDataManager playerDataManager = new PlayerDataManager(services);
        FakePlayer old = new FakePlayer(uuid, false);
        FakePlayer replacement = new FakePlayer(uuid, false);
        User oldUser = user(uuid, unboundCleanup ? "old-unbound" : "old-bound");
        User replacementUser = user(uuid, "self-disconnecting-replacement");
        AtomicInteger continuationEffects = new AtomicInteger();
        Runnable submitReplacement = () -> playerDataManager.onUserLogin(replacementUser, replacement.player,
                replacement.nativeIdentity, () -> true, () -> {
                    continuationEffects.incrementAndGet();
                    playerDataManager.onDisconnect(replacementUser);
                });
        if (unboundCleanup) {
            addTrackedPlayer(playerDataManager, oldUser, grimPlayer(old.player, old.nativeIdentity));
            services.onAlertCleanup = submitReplacement;
        } else {
            playerDataManager.onUserLogin(oldUser, old.player, old.nativeIdentity);
            services.onEvictToggle = submitReplacement;
        }

        playerDataManager.onDisconnect(oldUser);

        assertEquals(1, continuationEffects.get());
        assertEquals(2, services.quitWrites.get());
        assertEquals(unboundCleanup ? 1 : 2, services.toggleEvictions.get());
        assertEquals(2, services.alertCleanups.get());
        assertEquals(2, services.spectateCleanups.get());
        assertEquals(2, services.invalidations.get());
        assertFalse(hasConnectionBinding(playerDataManager, replacementUser));
        assertFalse(hasCurrentConnection(playerDataManager, uuid));
    }

    @Test
    void quitEventFailureDoesNotSkipConnectionCleanup() throws Exception {
        assertConnectionCleanupSurvivesLifecycleFailure(true, false);
    }

    @Test
    void quitWriteFailureDoesNotSkipConnectionCleanup() throws Exception {
        assertConnectionCleanupSurvivesLifecycleFailure(false, true);
    }

    @Test
    void replacementRemainsProtectedWhenQuitEventFails() throws Exception {
        assertReplacementRemainsProtectedWhenLifecycleTeardownFails(true, false);
    }

    @Test
    void replacementRemainsProtectedWhenQuitWriteFails() throws Exception {
        assertReplacementRemainsProtectedWhenLifecycleTeardownFails(false, true);
    }

    private static void assertConnectionCleanupSurvivesLifecycleFailure(boolean failQuitEvent,
                                                                         boolean failQuitWrite) throws Exception {
        UUID uuid = UUID.randomUUID();
        SpectateManager spectateManager = spectateManager();
        FakePlayer fake = new FakePlayer(uuid, false);
        User user = user(uuid, "lifecycle-failure");
        DisconnectServices services = new DisconnectServices(spectateManager);
        services.failQuitEvent = failQuitEvent;
        services.failQuitWrite = failQuitWrite;
        PlayerDataManager playerDataManager = new PlayerDataManager(services);
        addTrackedPlayer(playerDataManager, user, grimPlayer(fake.player, fake.nativeIdentity));
        playerDataManager.onUserLogin(user, fake.player, fake.nativeIdentity);
        spectateManager.onLogin(fake.player, user);
        assertEquals(SpectateManager.StartResult.STARTED,
                spectateManager.enable(fake.player, fake.target));

        playerDataManager.onDisconnect(user);

        assertEquals(1, services.quitEvents.get());
        assertEquals(1, services.quitWrites.get());
        assertEquals(1, services.disconnectFailureLogs.get());
        assertEquals(1, services.toggleEvictions.get());
        assertEquals(1, services.alertCleanups.get());
        assertEquals(1, services.spectateCleanups.get());
        assertEquals(1, services.invalidations.get());
        assertFalse(spectateManager.isLifecycleActive(uuid));
        assertFalse(isHidden(spectateManager, uuid));
        assertEquals(GameMode.CREATIVE, fake.gameMode);
    }

    private static void assertReplacementRemainsProtectedWhenLifecycleTeardownFails(boolean failQuitEvent,
                                                                                      boolean failQuitWrite) throws Exception {
        UUID uuid = UUID.randomUUID();
        SpectateManager spectateManager = spectateManager();
        FakePlayer old = new FakePlayer(uuid, false);
        FakePlayer replacement = new FakePlayer(uuid, false);
        User replacementUser = user(uuid, "replacement");
        User delayedOldUser = user(uuid, "old");
        DisconnectServices services = new DisconnectServices(spectateManager);
        services.failQuitEvent = failQuitEvent;
        services.failQuitWrite = failQuitWrite;
        PlayerDataManager playerDataManager = new PlayerDataManager(services);
        addTrackedPlayer(playerDataManager, delayedOldUser, grimPlayer(old.player, old.nativeIdentity));
        playerDataManager.onUserLogin(delayedOldUser, old.player, old.nativeIdentity);
        services.pauseAfterOwnershipDecision();

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread oldDisconnect = new Thread(() -> {
            try {
                playerDataManager.onDisconnect(delayedOldUser);
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        oldDisconnect.start();
        assertTrue(services.ownershipDecision.await(5, TimeUnit.SECONDS));

        // This is the exact production handoff: the replacement publishes its
        // wrapper/native pair before any old connection cleanup can resolve a
        // wrapper or mutate toggle, alert, spectate, or factory state.
        playerDataManager.onUserLogin(replacementUser, replacement.player, replacement.nativeIdentity);
        spectateManager.onLogin(replacement.player, replacementUser);
        assertEquals(SpectateManager.StartResult.STARTED,
                spectateManager.enable(replacement.player, replacement.target));
        services.resumeCleanup.countDown();
        oldDisconnect.join(5_000);

        assertFalse(oldDisconnect.isAlive());
        assertEquals(null, failure.get());
        assertTrue(spectateManager.isLifecycleActive(uuid));
        assertTrue(isHidden(spectateManager, uuid));
        assertEquals(GameMode.SPECTATOR, replacement.gameMode);
        assertEquals(1, services.quitEvents.get());
        assertEquals(1, services.quitWrites.get());
        assertEquals(1, services.disconnectFailureLogs.get());
        assertEquals(0, services.toggleEvictions.get());
        assertEquals(0, services.alertCleanups.get());
        assertEquals(1, services.spectateCleanups.get());
        assertEquals(1, services.invalidations.get());
    }

    private static void assertUntrackedDisconnectCleansLifecycle(boolean nativeUnavailable) throws Exception {
        UUID uuid = UUID.randomUUID();
        SpectateManager spectateManager = spectateManager();
        FakePlayer fake = new FakePlayer(uuid, nativeUnavailable);
        User user = user(uuid, nativeUnavailable ? "wrapper" : "native");
        DisconnectServices services = new DisconnectServices(spectateManager);
        PlayerDataManager playerDataManager = new PlayerDataManager(services);
        playerDataManager.onUserLogin(user, fake.player, fake.nativeIdentity);
        spectateManager.onLogin(fake.player, user);
        assertEquals(SpectateManager.StartResult.STARTED,
                spectateManager.enable(fake.player, fake.target));

        playerDataManager.onDisconnect(user);

        assertFalse(spectateManager.isLifecycleActive(uuid));
        assertFalse(isHidden(spectateManager, uuid));
        assertEquals(GameMode.CREATIVE, fake.gameMode);
        assertEquals(1, services.quitWrites.get());
        assertEquals(1, services.toggleEvictions.get());
        assertEquals(1, services.alertCleanups.get());
        assertEquals(1, services.spectateCleanups.get());
        assertEquals(1, services.invalidations.get());
    }

    @SuppressWarnings("unchecked")
    private static void addTrackedPlayer(PlayerDataManager manager, User user, GrimPlayer grimPlayer)
            throws ReflectiveOperationException {
        Field field = PlayerDataManager.class.getDeclaredField("playerDataMap");
        field.setAccessible(true);
        ((java.util.concurrent.ConcurrentHashMap<User, GrimPlayer>) field.get(manager)).put(user, grimPlayer);
    }

    private static GrimPlayer grimPlayer(PlatformPlayer platformPlayer, Object sessionIdentity)
            throws ReflectiveOperationException {
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
        GrimPlayer grimPlayer = (GrimPlayer) unsafe.allocateInstance(GrimPlayer.class);
        grimPlayer.platformPlayer = platformPlayer;
        grimPlayer.platformPlayerSessionIdentity = sessionIdentity;
        return grimPlayer;
    }

    @SuppressWarnings("unchecked")
    private static boolean hasConnectionBinding(PlayerDataManager manager, User user)
            throws ReflectiveOperationException {
        Field field = PlayerDataManager.class.getDeclaredField("connectionsByUser");
        field.setAccessible(true);
        return ((java.util.Map<User, ?>) field.get(manager)).containsKey(user);
    }

    @SuppressWarnings("unchecked")
    private static boolean hasCurrentConnection(PlayerDataManager manager, UUID uuid)
            throws ReflectiveOperationException {
        Field field = PlayerDataManager.class.getDeclaredField("currentConnections");
        field.setAccessible(true);
        return ((java.util.Map<UUID, ?>) field.get(manager)).containsKey(uuid);
    }

    @SuppressWarnings("unchecked")
    private static boolean isHidden(SpectateManager manager, UUID uuid) throws ReflectiveOperationException {
        Field field = SpectateManager.class.getDeclaredField("hiddenPlayers");
        field.setAccessible(true);
        return ((Set<UUID>) field.get(manager)).contains(uuid);
    }

    private static SpectateManager spectateManager() throws ReflectiveOperationException {
        Constructor<SpectateManager> constructor = SpectateManager.class.getDeclaredConstructor(Consumer.class);
        constructor.setAccessible(true);
        return constructor.newInstance((Consumer<Runnable>) ignored -> {
        });
    }

    private static User user(UUID uuid, String name) throws ReflectiveOperationException {
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
        User user = (User) unsafe.allocateInstance(User.class);
        Field profileField = User.class.getDeclaredField("profile");
        unsafe.putObject(user, unsafe.objectFieldOffset(profileField), new UserProfile(uuid, name));
        return user;
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

    private static final class DisconnectServices implements PlayerDataManager.DisconnectServices {
        private final SpectateManager spectateManager;
        private final AtomicInteger quitEvents = new AtomicInteger();
        private final AtomicInteger quitWrites = new AtomicInteger();
        private final AtomicInteger toggleEvictions = new AtomicInteger();
        private final AtomicInteger alertCleanups = new AtomicInteger();
        private final AtomicInteger spectateCleanups = new AtomicInteger();
        private final AtomicInteger invalidations = new AtomicInteger();
        private final AtomicInteger disconnectFailureLogs = new AtomicInteger();
        private CountDownLatch ownershipDecision;
        private CountDownLatch resumeCleanup;
        private boolean failQuitEvent;
        private boolean failQuitWrite;
        private boolean failEvictToggle;
        private Runnable onEvictToggle;
        private Runnable onAlertCleanup;
        private Runnable onSpectateCleanup;
        private Consumer<User> beforeLoginContinuation;
        private Consumer<User> afterLoginContinuationSelected;

        private DisconnectServices(SpectateManager spectateManager) {
            this.spectateManager = spectateManager;
        }

        private void pauseAfterOwnershipDecision() {
            ownershipDecision = new CountDownLatch(1);
            resumeCleanup = new CountDownLatch(1);
        }

        @Override
        public void fireQuit(GrimPlayer grimPlayer) {
            quitEvents.incrementAndGet();
            if (failQuitEvent) throw new IllegalStateException("quit event failed");
        }

        @Override
        public void recordQuit(User user, GrimPlayer grimPlayer, long now) {
            quitWrites.incrementAndGet();
            if (failQuitWrite) throw new IllegalStateException("quit write failed");
        }

        @Override
        public void evictToggle(UUID uuid) {
            toggleEvictions.incrementAndGet();
            if (onEvictToggle != null) onEvictToggle.run();
            if (failEvictToggle) throw new IllegalStateException("toggle eviction failed");
        }

        @Override
        public void handleAlertQuit(PlatformPlayer platformPlayer) {
            alertCleanups.incrementAndGet();
            if (onAlertCleanup != null) onAlertCleanup.run();
        }

        @Override
        public void handleSpectateQuit(PlatformPlayer platformPlayer, Object... sessionIdentities) {
            spectateCleanups.incrementAndGet();
            if (onSpectateCleanup != null) onSpectateCleanup.run();
            spectateManager.onQuit(platformPlayer, sessionIdentities);
        }

        @Override
        public void invalidatePlayer(UUID uuid, PlatformPlayer platformPlayer) {
            invalidations.incrementAndGet();
        }

        @Override
        public void logDisconnectFailure(String description, Throwable throwable) {
            disconnectFailureLogs.incrementAndGet();
        }

        @Override
        public void beforeLoginContinuation(User user) {
            if (beforeLoginContinuation != null) beforeLoginContinuation.accept(user);
        }

        @Override
        public void afterLoginContinuationSelected(User user) {
            if (afterLoginContinuationSelected != null) afterLoginContinuationSelected.accept(user);
        }

        @Override
        public void afterDisconnectOwnershipDecision(User user) {
            if (ownershipDecision == null) return;
            ownershipDecision.countDown();
            try {
                if (!resumeCleanup.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("timed out waiting to resume disconnect cleanup");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting to resume disconnect cleanup", exception);
            }
        }
    }

    private static final class FakePlayer {
        private final Object nativeIdentity = new Object();
        private final Location origin;
        private final Location target;
        private GameMode gameMode = GameMode.CREATIVE;
        private final PlatformPlayer player;

        private FakePlayer(UUID uuid, boolean nativeUnavailable) {
            PlatformWorld world = (PlatformWorld) Proxy.newProxyInstance(
                    PlatformWorld.class.getClassLoader(), new Class<?>[] {PlatformWorld.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "isLoaded" -> true;
                        case "getName" -> "test";
                        default -> defaultValue(method.getReturnType());
                    });
            this.origin = new Location(world, 1, 2, 3);
            this.target = new Location(world, 40, 50, 60);
            this.player = (PlatformPlayer) Proxy.newProxyInstance(
                    PlatformPlayer.class.getClassLoader(), new Class<?>[] {PlatformPlayer.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getUniqueId" -> uuid;
                        case "getNative" -> {
                            if (nativeUnavailable) throw new IllegalStateException("native unavailable");
                            yield nativeIdentity;
                        }
                        case "getLocation" -> origin;
                        case "getWorld" -> world;
                        case "getGameMode" -> gameMode;
                        case "setGameMode" -> {
                            gameMode = (GameMode) args[0];
                            yield null;
                        }
                        case "teleportAsync" -> CompletableFuture.completedFuture(true);
                        case "isOnline", "hasPermission" -> true;
                        case "isExternalPlayer", "isDead" -> false;
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        default -> defaultValue(method.getReturnType());
                    });
        }
    }
}
