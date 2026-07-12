package ac.grim.grimac.utils.anticheat;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.event.events.GrimJoinEvent;
import ac.grim.grimac.api.event.events.GrimQuitEvent;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.platform.api.player.PlatformPlayer;
import ac.grim.grimac.utils.reflection.GeyserUtil;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.netty.channel.ChannelHelper;
import com.github.retrooper.packetevents.protocol.player.User;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

public class PlayerDataManager {

    interface DisconnectServices {
        void fireQuit(GrimPlayer grimPlayer);

        void recordQuit(User user, GrimPlayer grimPlayer, long now);

        void evictToggle(UUID uuid);

        void handleAlertQuit(PlatformPlayer platformPlayer);

        void handleSpectateQuit(PlatformPlayer platformPlayer, Object... sessionIdentities);

        void invalidatePlayer(UUID uuid, PlatformPlayer platformPlayer);

        void logDisconnectFailure(String description, Throwable throwable);

        /** Test seam immediately before a selected login continuation may run. */
        default void beforeLoginContinuation(User user) {
        }

        /** Test seam after final selection, immediately before continuation invocation. */
        default void afterLoginContinuationSelected(User user) {
        }

        /** Test seam: invoked after the exact connection binding is observed, before cleanup claims it. */
        default void afterDisconnectOwnershipDecision(User user) {
        }
    }

    private static final DisconnectServices GRIM_SERVICES = new DisconnectServices() {
        @Override
        public void fireQuit(GrimPlayer grimPlayer) {
            Channels.QUIT.fire(grimPlayer);
        }

        @Override
        public void recordQuit(User user, GrimPlayer grimPlayer, long now) {
            GrimAPI.INSTANCE.getDataStoreLifecycle().liveWriteHooks()
                    .onQuitFromUserDisconnect(user, grimPlayer, now);
        }

        @Override
        public void evictToggle(UUID uuid) {
            GrimAPI.INSTANCE.getDataStoreLifecycle().playerToggleStore().evict(uuid);
        }

        @Override
        public void handleAlertQuit(PlatformPlayer platformPlayer) {
            GrimAPI.INSTANCE.getAlertManager().handlePlayerQuit(platformPlayer);
        }

        @Override
        public void handleSpectateQuit(PlatformPlayer platformPlayer, Object... sessionIdentities) {
            GrimAPI.INSTANCE.getSpectateManager().onQuit(platformPlayer, sessionIdentities);
        }

        @Override
        public void invalidatePlayer(UUID uuid, PlatformPlayer platformPlayer) {
            GrimAPI.INSTANCE.getPlatformPlayerFactory().invalidatePlayer(uuid, platformPlayer);
        }

        @Override
        public void logDisconnectFailure(String description, Throwable throwable) {
            LogUtil.warn(description + " Further failures of this kind are suppressed for this manager instance.", throwable);
        }
    };

    // Holder — PlayerDataManager is constructed inside GrimAPI's ctor, so a
    // plain static-final would see a null GrimAPI.INSTANCE. Holder init runs
    // on first fire, after GrimAPI is fully built.
    private static final class Channels {
        static final GrimJoinEvent.Channel JOIN = GrimAPI.INSTANCE.getEventBus().get(GrimJoinEvent.class);
        static final GrimQuitEvent.Channel QUIT = GrimAPI.INSTANCE.getEventBus().get(GrimQuitEvent.class);
    }

    private final Set<User> exemptUsers = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<User, GrimPlayer> playerDataMap = new ConcurrentHashMap<>();
    private final DisconnectServices disconnectServices;
    /**
     * Serializes publication of a UUID's replacement connection with all
     * UUID-global disconnect cleanup. The user map intentionally uses object
     * identity: a UUID can be reused by a replacement connection, but its
     * PacketEvents User must never be mistaken for the old connection.
     */
    private final Object connectionLock = new Object();
    private final Map<User, ConnectionBinding> connectionsByUser = new IdentityHashMap<>();
    private final Map<UUID, ConnectionBinding> currentConnections = new HashMap<>();
    private final Map<UUID, CleanupClaim> cleanupClaims = new HashMap<>();
    private final Map<UUID, PendingLogin> pendingLogins = new HashMap<>();
    private final ReferenceQueue<User> disconnectTombstoneQueue = new ReferenceQueue<>();
    private final Set<IdentityWeakReference> disconnectTombstones = new HashSet<>();
    private final AtomicBoolean quitFailureLogged = new AtomicBoolean();
    private final AtomicBoolean quitWriteFailureLogged = new AtomicBoolean();
    private final AtomicBoolean loginContinuationFailureLogged = new AtomicBoolean();
    private final AtomicBoolean pendingDisconnectFailureLogged = new AtomicBoolean();

    public PlayerDataManager() {
        this(GRIM_SERVICES);
    }

    PlayerDataManager(DisconnectServices disconnectServices) {
        this.disconnectServices = Objects.requireNonNull(disconnectServices);
    }

    public boolean isExemptUser(@Nullable User user) {
        return user != null && exemptUsers.contains(user);
    }

    public void exemptUser(@Nullable User user) {
        if (user == null) return;
        exemptUsers.add(user);
    }

    public boolean clearExemptions(@Nullable User user) {
        if (user == null) return false;
        return exemptUsers.remove(user);
    }

    @Nullable
    public GrimPlayer getPlayer(final @NotNull UUID uuid) {
        // Is it safe to interact with this, or is this internal PacketEvents code?
        Object channel = PacketEvents.getAPI().getProtocolManager().getChannel(uuid);
        if (channel == null) return null;
        User user = PacketEvents.getAPI().getProtocolManager().getUser(channel);
        if (user == null) return null;
        return getPlayer(user);
    }

    @Nullable
    public GrimPlayer getPlayer(final @NotNull User user) {
        @Nullable GrimPlayer player = playerDataMap.get(user);
        if (player != null && player.platformPlayer != null && player.platformPlayer.isExternalPlayer())
            return null;
        return player;
    }

    public boolean shouldCheck(@NotNull User user) {
        if (isExemptUser(user)) return false;
        if (!ChannelHelper.isOpen(user.getChannel())) return false;

        if (user.getUUID() != null) {
            // Bedrock players don't have Java movement
            if (GeyserUtil.isBedrockPlayer(user.getUUID())) {
                exemptUser(user);
                return false;
            }

            // Has exempt permission
            GrimPlayer grimPlayer = GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(user);
            if (grimPlayer != null && grimPlayer.hasPermission("grim.exempt")) {
                exemptUser(user);
                return false;
            }

            // Geyser formatted player string
            // This will never happen for Java players, as the first character in the 3rd group is always 4 (xxxxxxxx-xxxx-4xxx-xxxx-xxxxxxxxxxxx)
            if (user.getUUID().toString().startsWith("00000000-0000-0000-0009")) {
                exemptUser(user);
                return false;
            }
        }

        return true;
    }

    public void addUser(final @NotNull User user) {
        if (shouldCheck(user)) {
            GrimPlayer player = new GrimPlayer(user);
            playerDataMap.put(user, player);
            Channels.JOIN.fire(player);
        }
    }

    public GrimPlayer remove(final @NotNull User user) {
        return playerDataMap.remove(user);
    }

    /**
     * Publishes the exact platform/native pair for this PacketEvents
     * connection before login code mutates alert, toggle, or spectate state.
     */
    public boolean onUserLogin(@NotNull User user, @NotNull PlatformPlayer platformPlayer,
                               @NotNull Object nativePlayer) {
        return onUserLogin(user, platformPlayer, nativePlayer, () -> true, () -> {}) != LoginResult.STALE;
    }

    /**
     * Publishes a login only while the platform still considers this exact
     * native player authoritative. The check runs under the connection lock so
     * a stale event cannot regress an already-published replacement binding.
     */
    public boolean onUserLogin(@NotNull User user, @NotNull PlatformPlayer platformPlayer,
                               @NotNull Object nativePlayer, @NotNull BooleanSupplier isNativeCurrent) {
        return onUserLogin(user, platformPlayer, nativePlayer, isNativeCurrent, () -> {}) != LoginResult.STALE;
    }

    /**
     * Publishes and runs the complete login lifecycle, or defers both while an
     * exact UUID cleanup owns the lifecycle. Deferred logins are coalesced so
     * only the latest exact connection is considered when the claim drains.
     */
    public LoginResult onUserLogin(@NotNull User user, @NotNull PlatformPlayer platformPlayer,
                                   @NotNull Object nativePlayer, @NotNull BooleanSupplier isNativeCurrent,
                                   @NotNull Runnable continuation) {
        UUID uuid = userUuid(user);
        if (uuid == null) return LoginResult.STALE;

        PendingLogin login = new PendingLogin(
                new ConnectionBinding(user, uuid, platformPlayer, nativePlayer),
                isNativeCurrent,
                continuation);
        CleanupClaim claim;
        boolean drain;
        synchronized (connectionLock) {
            purgeDisconnectTombstones();
            if (isDisconnectTombstoned(user)) return LoginResult.STALE;
            ConnectionBinding existingForUser = connectionsByUser.get(user);
            if (existingForUser != null && existingForUser.disconnectStarted) {
                return LoginResult.STALE;
            }
            claim = cleanupClaims.get(uuid);
            if (claim != null) {
                if (claim.disconnectTombstones.contains(user)) return LoginResult.STALE;
                pendingLogins.put(uuid, login);
                return LoginResult.DEFERRED;
            }
            claim = claimCleanup(uuid);
            if (claim == null) return LoginResult.DEFERRED;
            pendingLogins.put(uuid, login);
            drain = true;
        }
        if (drain) releaseCleanupClaim(claim);
        return login.result;
    }

    public void onDisconnect(User user) {
        if (user == null) return;
        ConnectionBinding connection;
        CleanupClaim activeClaim;
        boolean tombstonedPendingLogin = false;
        GrimPlayer claimedGrimPlayer = null;
        boolean deferredExactDisconnect = false;
        synchronized (connectionLock) {
            purgeDisconnectTombstones();
            if (!disconnectTombstones.add(new IdentityWeakReference(user, disconnectTombstoneQueue))) return;
            connection = connectionsByUser.get(user);
            UUID uuid = connection == null ? userUuid(user) : connection.uuid;
            activeClaim = uuid == null ? null : cleanupClaims.get(uuid);

            if (connection != null) {
                if (connection.disconnectStarted) return;
                connection.disconnectStarted = true;
                if (activeClaim != null && currentConnections.get(connection.uuid) == connection) {
                    claimedGrimPlayer = remove(user);
                    clearExemptions(user);
                    deferredExactDisconnect = queueDisconnect(
                            activeClaim, connection, claimedGrimPlayer, false);
                }
            } else if (activeClaim != null) {
                if (!activeClaim.disconnectTombstones.add(user)) return;
                PendingLogin pending = pendingLogins.get(activeClaim.uuid);
                if (pending != null && pending.connection.user == user) {
                    pendingLogins.remove(activeClaim.uuid, pending);
                    tombstonedPendingLogin = true;
                }
            }
        }

        // The claim owns a selected/in-progress login continuation. Defer the
        // exact connection's QUIT/write together with its global cleanup so a
        // continuation can never record JOIN after an already-recorded QUIT.
        if (deferredExactDisconnect) return;

        GrimPlayer grimPlayer = claimedGrimPlayer == null ? remove(user) : claimedGrimPlayer;
        clearExemptions(user);
        fireQuitAndRecord(user, grimPlayer);

        // A queued login that never published has no UUID/global lifecycle to
        // undo. Its exact disconnect tombstone prevents the claim from later
        // resurrecting it or recording the same queued request twice.
        if (tombstonedPendingLogin) return;

        if (connection == null) {
            cleanupUnboundTrackedConnection(user, grimPlayer);
            return;
        }

        if (activeClaim != null) {
            boolean queued;
            boolean stale;
            synchronized (connectionLock) {
                stale = currentConnections.get(connection.uuid) != connection;
                if (stale) {
                    connectionsByUser.remove(user, connection);
                    queued = false;
                } else {
                    queued = queueDisconnect(activeClaim, connection, grimPlayer, true);
                }
            }
            if (stale) {
                cleanupExactConnection(connection);
                return;
            }
            if (queued) return;
        }

        // Deliberately outside the lock. This is a test barrier, and mirrors
        // the handoff window where a replacement may publish before cleanup
        // has claimed the UUID-global lifecycle state.
        disconnectServices.afterDisconnectOwnershipDecision(user);

        CleanupClaim claim;
        boolean stale;
        synchronized (connectionLock) {
            if (connectionsByUser.get(user) != connection) return;
            stale = currentConnections.get(connection.uuid) != connection;
            if (stale) {
                connectionsByUser.remove(user, connection);
                claim = null;
            } else {
                claim = claimCleanup(connection.uuid);
                if (claim == null) {
                    CleanupClaim existing = cleanupClaims.get(connection.uuid);
                    if (existing != null) queueDisconnect(existing, connection, grimPlayer, true);
                    return;
                }
            }
        }
        if (stale) {
            cleanupExactConnection(connection);
            return;
        }

        // The explicit claim, unlike a Java monitor, also excludes reentrant
        // same-thread login callbacks. All external work stays outside the
        // monitor; replacement publication and its full continuation drain
        // only after cleanup releases the claim.
        try {
            cleanupBoundConnection(connection);
        } finally {
            synchronized (connectionLock) {
                currentConnections.remove(connection.uuid, connection);
                connectionsByUser.remove(user, connection);
            }
            releaseCleanupClaim(claim);
        }
    }

    /**
     * Quit listeners and datastore writes are owned by the exact User and
     * GrimPlayer, not by the UUID's currently published connection. Always
     * release them, even when this disconnect arrived before onUserLogin or
     * after a replacement claimed the UUID.
     */
    private void fireQuitAndRecord(User user, @Nullable GrimPlayer grimPlayer) {
        if (grimPlayer != null) {
            try {
                disconnectServices.fireQuit(grimPlayer);
            } catch (Throwable throwable) {
                logDisconnectFailure(quitFailureLogged, "Failed to fire Grim quit event during disconnect", throwable);
            }
        }
        try {
            disconnectServices.recordQuit(user, grimPlayer, System.currentTimeMillis());
        } catch (Throwable throwable) {
            logDisconnectFailure(quitWriteFailureLogged, "Failed to record datastore quit during disconnect", throwable);
        }
    }

    private void logDisconnectFailure(AtomicBoolean logged, String description, Throwable throwable) {
        if (!logged.compareAndSet(false, true)) return;
        try {
            disconnectServices.logDisconnectFailure(description, throwable);
        } catch (Throwable ignored) {
            // Partial shutdown may also have torn down logging. Disconnect
            // cleanup must remain best-effort even when diagnostics are gone.
        }
    }

    /**
     * A tracked connection can close before onUserLogin binds it. Its captured
     * wrapper/session identity is still safe for identity-owned cleanup, but
     * no UUID-global cleanup may run and an already-published replacement
     * always wins.
     */
    private void cleanupUnboundTrackedConnection(User user, @Nullable GrimPlayer grimPlayer) {
        if (grimPlayer == null || grimPlayer.platformPlayer == null) return;
        UUID uuid = grimPlayer.uuid != null ? grimPlayer.uuid : userUuid(user);
        if (uuid == null) return;

        Object identity = grimPlayer.platformPlayerSessionIdentity == null
                ? grimPlayer.platformPlayer : grimPlayer.platformPlayerSessionIdentity;
        ConnectionBinding connection = new ConnectionBinding(user, uuid, grimPlayer.platformPlayer, identity);
        CleanupClaim claim;
        boolean replacementCurrent;
        synchronized (connectionLock) {
            replacementCurrent = currentConnections.containsKey(uuid);
            claim = replacementCurrent ? null : claimCleanup(uuid);
        }
        if (replacementCurrent) {
            cleanupExactConnection(connection);
            return;
        }
        if (claim == null) return;
        try {
            runWithPlayerInvalidation(
                    () -> disconnectServices.handleAlertQuit(connection.platformPlayer),
                    () -> disconnectServices.handleSpectateQuit(connection.platformPlayer,
                            connection.sessionIdentities()),
                    () -> disconnectServices.invalidatePlayer(connection.uuid, connection.platformPlayer));
        } finally {
            releaseCleanupClaim(claim);
        }
    }

    private @Nullable CleanupClaim claimCleanup(UUID uuid) {
        if (cleanupClaims.containsKey(uuid)) return null;
        CleanupClaim claim = new CleanupClaim(uuid);
        cleanupClaims.put(uuid, claim);
        return claim;
    }

    private boolean queueDisconnect(CleanupClaim claim, ConnectionBinding connection,
                                    @Nullable GrimPlayer grimPlayer, boolean quitRecorded) {
        if (cleanupClaims.get(claim.uuid) != claim) return false;
        claim.pendingDisconnects.addLast(new PendingDisconnect(connection, grimPlayer, quitRecorded));
        claim.disconnectTombstones.add(connection.user);
        return true;
    }

    private void cleanupBoundConnection(ConnectionBinding connection) {
        try {
            disconnectServices.evictToggle(connection.uuid);
        } finally {
            runWithPlayerInvalidation(
                    () -> disconnectServices.handleAlertQuit(connection.platformPlayer),
                    () -> disconnectServices.handleSpectateQuit(connection.platformPlayer,
                            connection.sessionIdentities()),
                    () -> disconnectServices.invalidatePlayer(connection.uuid, connection.platformPlayer));
        }
    }

    /** Exact identity teardown is safe even after a replacement owns the UUID. */
    private void cleanupExactConnection(ConnectionBinding connection) {
        try {
            disconnectServices.handleSpectateQuit(connection.platformPlayer, connection.sessionIdentities());
        } finally {
            disconnectServices.invalidatePlayer(connection.uuid, connection.platformPlayer);
        }
    }

    /** Drains outside the monitor while retaining the claim against reentrant publication. */
    private void releaseCleanupClaim(CleanupClaim claim) {
        try {
            while (true) {
                PendingDisconnect disconnect;
                PendingLogin login;
                synchronized (connectionLock) {
                    if (cleanupClaims.get(claim.uuid) != claim) return;
                    disconnect = claim.pendingDisconnects.pollFirst();
                    if (disconnect != null) {
                        if (currentConnections.get(disconnect.connection.uuid) != disconnect.connection) {
                            connectionsByUser.remove(disconnect.connection.user, disconnect.connection);
                            login = null;
                        } else {
                            login = null;
                        }
                    } else {
                        login = pendingLogins.remove(claim.uuid);
                        if (login == null) {
                            cleanupClaims.remove(claim.uuid, claim);
                            return;
                        }
                        if (!isNativeCurrent(login)) {
                            login.result = LoginResult.STALE;
                            continue;
                        }
                        publish(login.connection);
                        login.result = LoginResult.PUBLISHED;
                    }
                }

                if (disconnect != null) {
                    if (!disconnect.quitRecorded) {
                        fireQuitAndRecord(disconnect.connection.user, disconnect.grimPlayer);
                    }
                    boolean current;
                    synchronized (connectionLock) {
                        current = currentConnections.get(disconnect.connection.uuid) == disconnect.connection;
                    }
                    if (!current) {
                        try {
                            cleanupExactConnection(disconnect.connection);
                        } catch (Throwable throwable) {
                            logDisconnectFailure(pendingDisconnectFailureLogged,
                                    "Failed exact teardown for stale deferred disconnect", throwable);
                        }
                        continue;
                    }
                    try {
                        cleanupBoundConnection(disconnect.connection);
                    } catch (Throwable throwable) {
                        logDisconnectFailure(pendingDisconnectFailureLogged,
                                "Failed to clean deferred player disconnect", throwable);
                    } finally {
                        synchronized (connectionLock) {
                            currentConnections.remove(disconnect.connection.uuid, disconnect.connection);
                            connectionsByUser.remove(disconnect.connection.user, disconnect.connection);
                        }
                    }
                    continue;
                }

                try {
                    disconnectServices.beforeLoginContinuation(login.connection.user);
                } catch (Throwable throwable) {
                    logDisconnectFailure(loginContinuationFailureLogged,
                            "Failed before deferred player login continuation", throwable);
                }
                synchronized (connectionLock) {
                    if (cleanupClaims.get(claim.uuid) != claim) return;
                    if (pendingLogins.containsKey(claim.uuid)
                            || !claim.pendingDisconnects.isEmpty()
                            || currentConnections.get(login.connection.uuid) != login.connection) {
                        continue;
                    }
                }
                try {
                    disconnectServices.afterLoginContinuationSelected(login.connection.user);
                } catch (Throwable throwable) {
                    logDisconnectFailure(loginContinuationFailureLogged,
                            "Failed after selecting deferred player login continuation", throwable);
                }
                try {
                    login.continuation.run();
                } catch (Throwable throwable) {
                    logDisconnectFailure(loginContinuationFailureLogged,
                            "Failed to run deferred player login continuation", throwable);
                }
            }
        } finally {
            synchronized (connectionLock) {
                if (cleanupClaims.remove(claim.uuid, claim)) {
                    pendingLogins.remove(claim.uuid);
                }
            }
        }
    }

    private void publish(ConnectionBinding connection) {
        ConnectionBinding previous = connectionsByUser.put(connection.user, connection);
        if (previous != null && !previous.uuid.equals(connection.uuid)) {
            currentConnections.remove(previous.uuid, previous);
        }
        currentConnections.put(connection.uuid, connection);
    }

    private static boolean isNativeCurrent(PendingLogin login) {
        try {
            return login.isNativeCurrent.getAsBoolean();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static @Nullable UUID userUuid(User user) {
        try {
            UUID uuid = user.getUUID();
            if (uuid != null) return uuid;
            return user.getProfile() == null ? null : user.getProfile().getUUID();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean isDisconnectTombstoned(User user) {
        return disconnectTombstones.contains(new IdentityWeakReference(user));
    }

    private void purgeDisconnectTombstones() {
        IdentityWeakReference reference;
        while ((reference = (IdentityWeakReference) disconnectTombstoneQueue.poll()) != null) {
            disconnectTombstones.remove(reference);
        }
    }

    private static final class ConnectionBinding {
        private final User user;
        private final UUID uuid;
        private final PlatformPlayer platformPlayer;
        private final Object nativePlayer;
        private boolean disconnectStarted;

        private ConnectionBinding(User user, UUID uuid, PlatformPlayer platformPlayer, Object nativePlayer) {
            this.user = user;
            this.uuid = uuid;
            this.platformPlayer = platformPlayer;
            this.nativePlayer = nativePlayer;
        }

        private Object[] sessionIdentities() {
            return nativePlayer == platformPlayer
                    ? new Object[] {user, platformPlayer}
                    : new Object[] {user, nativePlayer, platformPlayer};
        }
    }

    private static final class CleanupClaim {
        private final UUID uuid;
        private final ArrayDeque<PendingDisconnect> pendingDisconnects = new ArrayDeque<>();
        private final Set<User> disconnectTombstones = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

        private CleanupClaim(UUID uuid) {
            this.uuid = uuid;
        }
    }

    private static final class PendingLogin {
        private final ConnectionBinding connection;
        private final BooleanSupplier isNativeCurrent;
        private final Runnable continuation;
        private LoginResult result = LoginResult.DEFERRED;

        private PendingLogin(ConnectionBinding connection, BooleanSupplier isNativeCurrent, Runnable continuation) {
            this.connection = connection;
            this.isNativeCurrent = isNativeCurrent;
            this.continuation = continuation;
        }
    }

    private record PendingDisconnect(ConnectionBinding connection, @Nullable GrimPlayer grimPlayer,
                                     boolean quitRecorded) {}

    private static final class IdentityWeakReference extends WeakReference<User> {
        private final int identityHash;

        private IdentityWeakReference(User user) {
            super(user);
            this.identityHash = System.identityHashCode(user);
        }

        private IdentityWeakReference(User user, ReferenceQueue<User> queue) {
            super(user, queue);
            this.identityHash = System.identityHashCode(user);
        }

        @Override
        public int hashCode() {
            return identityHash;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) return true;
            if (!(object instanceof IdentityWeakReference other)) return false;
            User user = get();
            return user != null && user == other.get();
        }
    }

    public enum LoginResult {
        PUBLISHED,
        DEFERRED,
        STALE
    }

    static void runWithPlayerInvalidation(Runnable alertCleanup, Runnable spectateCleanup, Runnable invalidation) {
        try {
            try {
                alertCleanup.run();
            } finally {
                spectateCleanup.run();
            }
        } finally {
            invalidation.run();
        }
    }

    public Collection<GrimPlayer> getEntries() {
        return playerDataMap.values();
    }

    public int size() {
        return playerDataMap.size();
    }
}
