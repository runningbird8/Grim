package ac.grim.grimac.platform.api.player;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformPlayerCacheTest {
    @Test
    void delayedOldInvalidationCannotEvictReplacementWrapper() {
        PlatformPlayerCache cache = PlatformPlayerCache.getInstance();
        UUID uuid = UUID.randomUUID();
        PlatformPlayer old = player(uuid);
        PlatformPlayer replacement = player(uuid);
        cache.removePlayer(uuid);
        cache.addOrGetPlayer(uuid, replacement);

        assertFalse(cache.removePlayer(uuid, old));
        assertSame(replacement, cache.getPlayer(uuid));
        assertTrue(cache.removePlayer(uuid, replacement));
    }

    @Test
    void replacementNativeObjectGetsANewWrapperBeforeOldDisconnectInvalidates() {
        UUID uuid = UUID.randomUUID();
        FakeFactory factory = new FakeFactory(uuid);
        Object oldNative = new Object();
        Object replacementNative = new Object();

        factory.setCurrentNative(oldNative);
        PlatformPlayer old = factory.getFromNativePlayerType(oldNative);
        factory.setCurrentNative(replacementNative);
        PlatformPlayer replacement = factory.getFromNativePlayerType(replacementNative);

        assertNotSame(old, replacement);
        assertSame(replacement, factory.getFromNativePlayerType(replacementNative));
        assertFalse(PlatformPlayerCache.getInstance().removePlayer(uuid, old));
        assertSame(replacement, PlatformPlayerCache.getInstance().getPlayer(uuid));
        PlatformPlayerCache.getInstance().removePlayer(uuid, replacement);
    }

    @Test
    void lateOldNativeCannotReplaceTheCurrentReplacementWrapper() {
        UUID uuid = UUID.randomUUID();
        FakeFactory factory = new FakeFactory(uuid);
        Object oldNative = new Object();
        Object replacementNative = new Object();

        factory.setCurrentNative(oldNative);
        PlatformPlayer old = factory.getFromNativePlayerType(oldNative);
        factory.setCurrentNative(replacementNative);
        PlatformPlayer replacement = factory.getFromNativePlayerType(replacementNative);

        PlatformPlayer delayedOld = factory.getFromNativePlayerType(oldNative);

        assertNotSame(old, replacement);
        assertNotSame(replacement, delayedOld);
        assertSame(replacement, PlatformPlayerCache.getInstance().getPlayer(uuid));
        PlatformPlayerCache.getInstance().removePlayer(uuid, replacement);
    }

    private static PlatformPlayer player(UUID uuid) {
        return (PlatformPlayer) Proxy.newProxyInstance(
                PlatformPlayer.class.getClassLoader(),
                new Class<?>[]{PlatformPlayer.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static final class FakeFactory extends AbstractPlatformPlayerFactory<Object> {
        private final UUID uuid;
        private Object currentNative;

        private FakeFactory(UUID uuid) {
            this.uuid = uuid;
        }

        private void setCurrentNative(Object currentNative) {
            this.currentNative = currentNative;
        }

        @Override
        protected Object getNativePlayer(UUID uuid) {
            return currentNative;
        }

        @Override
        protected Object getNativePlayer(String name) {
            return null;
        }

        @Override
        protected PlatformPlayer createPlatformPlayer(Object nativePlayer) {
            return (PlatformPlayer) Proxy.newProxyInstance(
                    PlatformPlayer.class.getClassLoader(), new Class<?>[]{PlatformPlayer.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getUniqueId" -> uuid;
                        case "getNative" -> nativePlayer;
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        default -> defaultValue(method.getReturnType());
                    });
        }

        @Override
        protected UUID getPlayerUUID(Object nativePlayer) {
            return uuid;
        }

        @Override
        protected java.util.Collection<Object> getNativeOnlinePlayers() {
            return List.of();
        }

        @Override
        public OfflinePlatformPlayer getOfflineFromUUID(UUID uuid) {
            return null;
        }

        @Override
        public OfflinePlatformPlayer getOfflineFromName(String name) {
            return null;
        }

        @Override
        public java.util.Collection<OfflinePlatformPlayer> getOfflinePlayers() {
            return List.of();
        }
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
