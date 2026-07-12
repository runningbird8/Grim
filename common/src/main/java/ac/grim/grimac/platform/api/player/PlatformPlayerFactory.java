package ac.grim.grimac.platform.api.player;

import java.util.Collection;
import java.util.UUID;

public interface PlatformPlayerFactory {
    OfflinePlatformPlayer getOfflineFromUUID(UUID uuid);

    OfflinePlatformPlayer getOfflineFromName(String name);

    Collection<OfflinePlatformPlayer> getOfflinePlayers();

    PlatformPlayer getFromName(String name);

    PlatformPlayer getFromUUID(UUID uuid);

    PlatformPlayer getFromNativePlayerType(Object playerObject);

    /**
     * Whether this exact native player object still owns its UUID on the
     * platform. Factories that cannot establish ownership fail closed so a
     * delayed login event cannot replace the active connection's wrapper.
     */
    default boolean isCurrentNativePlayer(Object playerObject) {
        return false;
    }

    void invalidatePlayer(UUID uuid);

    /**
     * Invalidates a cached wrapper only when it still belongs to the expected
     * connection. Implementations which cannot compare identity fail closed.
     */
    default boolean invalidatePlayer(UUID uuid, PlatformPlayer expectedPlayer) {
        return false;
    }

    Collection<PlatformPlayer> getOnlinePlayers();
}
