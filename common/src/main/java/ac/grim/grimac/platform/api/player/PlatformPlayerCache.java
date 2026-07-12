package ac.grim.grimac.platform.api.player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlatformPlayerCache {
    private static final PlatformPlayerCache INSTANCE = new PlatformPlayerCache();
    private final Map<UUID, PlatformPlayer> playerCache = new ConcurrentHashMap<>();

    private PlatformPlayerCache() {
        // Private constructor to prevent instantiation
    }

    public static PlatformPlayerCache getInstance() {
        return INSTANCE;
    }

    /**
     * Adds or updates a PlatformPlayer in the cache.
     *
     * @param uuid   the UUID of the player
     * @param player the PlatformPlayer instance
     * @return the cached PlatformPlayer instance
     */
    public PlatformPlayer addOrGetPlayer(UUID uuid, PlatformPlayer player) {
        return playerCache.compute(uuid, (key, existing) -> {
            if (existing != null) {
                return existing; // Return existing instance if already cached
            }
            return player;
        });
    }

    /**
     * Returns the cached wrapper only when it belongs to the exact native
     * player instance supplied by the caller. Reconnects reuse UUIDs, so a
     * UUID-only cache hit can otherwise hand a replacement connection the
     * disconnecting connection's wrapper.
     */
    public PlatformPlayer addOrReplacePlayerForNative(UUID uuid, Object nativePlayer, PlatformPlayer player) {
        return playerCache.compute(uuid, (key, existing) -> ownsNativePlayer(existing, nativePlayer) ? existing : player);
    }

    /** Returns the cached wrapper only when it owns this exact native object. */
    public PlatformPlayer getPlayerForNative(UUID uuid, Object nativePlayer) {
        PlatformPlayer player = playerCache.get(uuid);
        return ownsNativePlayer(player, nativePlayer) ? player : null;
    }

    private static boolean ownsNativePlayer(PlatformPlayer player, Object nativePlayer) {
        if (player == null || nativePlayer == null) return false;
        try {
            return player.getNative() == nativePlayer;
        } catch (Throwable ignored) {
            // If the old wrapper cannot safely identify its native owner,
            // fail closed and construct a wrapper for the joining player.
            return false;
        }
    }

    /**
     * Removes a player from the cache.
     *
     * @param uuid the UUID of the player to remove
     */
    public void removePlayer(UUID uuid) {
        playerCache.remove(uuid);
    }

    /** Removes only the wrapper owned by the disconnecting connection. */
    public boolean removePlayer(UUID uuid, PlatformPlayer expectedPlayer) {
        return uuid != null && expectedPlayer != null && playerCache.remove(uuid, expectedPlayer);
    }

    /**
     * Gets a PlatformPlayer from the cache by UUID.
     *
     * @param uuid the UUID of the player
     * @return the cached PlatformPlayer, or null if not found
     */
    public PlatformPlayer getPlayer(UUID uuid) {
        return playerCache.get(uuid);
    }
}
