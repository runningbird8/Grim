package ac.grim.grimac.manager.datastore;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.AbstractCheck;
import ac.grim.grimac.api.storage.DataStore;
import ac.grim.grimac.api.storage.category.Categories;
import ac.grim.grimac.api.storage.model.VerboseFormat;
import ac.grim.grimac.internal.storage.checks.CheckRegistry;
import ac.grim.grimac.internal.storage.checks.StableKeyMapping;
import ac.grim.grimac.internal.storage.identity.PlayerIdentityService;
import ac.grim.grimac.platform.api.player.PlatformPlayer;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.LogUtil;
import com.github.retrooper.packetevents.protocol.player.User;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Concrete {@link LiveWriteHooks}. Caches display-name → checkId locally so
 * the hot path skips the {@code synchronized intern(...)} call on
 * {@link CheckRegistry}.
 */
public final class LiveWriteHooksImpl implements LiveWriteHooks {

    private final DataStore store;
    private final PlayerIdentityService identityService;
    private final CheckRegistry checkRegistry;
    private final SessionTracker sessionTracker;
    /**
     * Completed PacketEvents User identity -> exact session generation. UUID
     * identity is insufficient: an old and replacement connection can share
     * it while both still have disconnect callbacks in flight.
     */
    private final Map<User, UserSession> sessionsByUser = new IdentityHashMap<>();
    /** display name (lowercased) → checkId. Populated lazily via intern. */
    private final Map<String, Integer> checkIdCache = new ConcurrentHashMap<>();
    /** display-name-lowercase of checks we've already warned about. Prevents log spam. */
    private final Set<String> missingStableKeyLogged = ConcurrentHashMap.newKeySet();

    public LiveWriteHooksImpl(
            @NotNull DataStore store,
            @NotNull PlayerIdentityService identityService,
            @NotNull CheckRegistry checkRegistry,
            @NotNull SessionTracker sessionTracker) {
        this.store = store;
        this.identityService = identityService;
        this.checkRegistry = checkRegistry;
        this.sessionTracker = sessionTracker;
    }

    @Override
    public @NotNull UUID onJoin(
            @NotNull UUID uuid,
            @Nullable String name,
            long now,
            @NotNull SessionTracker.ClientMeta meta) {
        identityService.observe(uuid, name, now);
        return sessionTracker.open(uuid, now, meta);
    }

    @Override
    public void onQuit(
            @NotNull UUID uuid,
            @NotNull UUID sessionId,
            long now,
            @NotNull SessionTracker.ClientMeta meta) {
        sessionTracker.close(uuid, sessionId, now, meta);
    }

    @Override
    public void observeBrand(@NotNull UUID uuid, long now, @NotNull SessionTracker.ClientMeta meta) {
        UUID sessionId = sessionTracker.currentSessionId(uuid);
        if (sessionId != null) sessionTracker.observeActivity(uuid, sessionId, now, meta);
    }

    @Override
    public void recordFlag(
            @NotNull UUID playerUuid,
            @NotNull AbstractCheck check,
            double vl,
            @Nullable String verbose,
            long now,
            @NotNull SessionTracker.ClientMeta meta) {
        UUID sessionId = sessionTracker.currentSessionId(playerUuid);
        if (sessionId == null) {
            // A pre-JOIN or post-close callback has no exact connection-owned
            // session token. Drop it rather than synthesizing an unowned row
            // that a stale disconnect could close—or leave open forever.
            return;
        }
        recordFlagForSession(playerUuid, sessionId, check, vl, verbose, now);
    }

    private void recordFlagForSession(
            UUID playerUuid, UUID sessionId, AbstractCheck check, double vl,
            @Nullable String verbose, long now) {
        final int checkId = resolveCheckId(check);
        store.submit(Categories.VIOLATION, e -> e
                .sessionId(sessionId)
                .playerUuid(playerUuid)
                .checkId(checkId)
                .vl(vl)
                .occurredEpochMs(now)
                .verbose(verbose)
                .verboseFormat(VerboseFormat.TEXT));
    }

    @Override
    public void onJoinFromUserLogin(@NotNull PlatformPlayer player, @NotNull User user, long now) {
        GrimPlayer gp = GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(user);
        onJoinFromUserLogin(user, player.getUniqueId(), player.getName(), now,
                LiveWriteHooks.clientMetaFor(user, gp));
    }

    @Override
    public void onQuitFromUserDisconnect(@NotNull User user, @Nullable GrimPlayer grimPlayer, long now) {
        onQuitFromUserDisconnect(user, now, LiveWriteHooks.clientMetaFor(user, grimPlayer));
    }

    /**
     * Testable completed-login seam. The public UserLogin path invokes this
     * after its continuation is selected, so only a completed JOIN receives a
     * binding. Package-private tests can avoid bootstrapping {@link GrimAPI}.
     */
    void onJoinFromUserLogin(
            @NotNull User user,
            @NotNull UUID uuid,
            @Nullable String name,
            long now,
            @NotNull SessionTracker.ClientMeta meta) {
        UUID sessionId = onJoin(uuid, name, now, meta);
        synchronized (sessionsByUser) {
            sessionsByUser.entrySet().removeIf(entry -> entry.getKey() != user
                    && entry.getValue().uuid.equals(uuid));
            sessionsByUser.put(user, new UserSession(uuid, sessionId));
        }
    }

    /**
     * Atomically forgets the exact User binding before attempting the tracker
     * CAS. Unpublished/early Users have no binding, so their disconnect can
     * never close a live replacement sharing their UUID.
     */
    void onQuitFromUserDisconnect(@NotNull User user, long now, @NotNull SessionTracker.ClientMeta meta) {
        UserSession session;
        synchronized (sessionsByUser) {
            session = sessionsByUser.remove(user);
        }
        if (session != null) onQuit(session.uuid, session.sessionId, now, meta);
    }

    @Override
    public void observeBrandFromCheck(@NotNull GrimPlayer grimPlayer) {
        UUID uuid = grimPlayer.user.getUUID();
        if (uuid == null) return;
        UserSession session = exactUserSession(grimPlayer.user);
        if (session == null || !session.uuid.equals(uuid)) return;
        sessionTracker.observeActivity(uuid, session.sessionId, System.currentTimeMillis(),
                LiveWriteHooks.clientMetaFor(grimPlayer.user, grimPlayer));
    }

    @Override
    public void recordFlagFromCheck(
            @NotNull GrimPlayer player,
            @NotNull AbstractCheck check,
            double vl,
            @Nullable String verbose) {
        try {
            UserSession session = exactUserSession(player.user);
            if (session == null || !session.uuid.equals(player.uuid)) return;
            recordFlagForSession(player.uuid, session.sessionId, check, vl, verbose, System.currentTimeMillis());
        } catch (RuntimeException e) {
            // Don't let a datastore issue break the alert path; the legacy
            // write already ran when we got here. One warn, then swallow.
            LogUtil.warn("v1 datastore recordFlag failed: " + e.getMessage());
        }
    }

    @Override
    public void pollHeartbeatFromUser(@NotNull User user, long now) {
        UserSession session = exactUserSession(user);
        if (session != null) sessionTracker.pollHeartbeat(session.uuid, session.sessionId, now);
    }

    private @Nullable UserSession exactUserSession(User user) {
        synchronized (sessionsByUser) {
            return sessionsByUser.get(user);
        }
    }

    private int resolveCheckId(@NotNull AbstractCheck check) {
        String display = check.getCheckName();
        String key = display.toLowerCase(Locale.ROOT);
        Integer cached = checkIdCache.get(key);
        if (cached != null) return cached;

        String declaredStable = check.getStableKey();
        String stable;
        if (declaredStable != null && !declaredStable.isEmpty()) {
            stable = declaredStable;
        } else {
            // Check hasn't adopted the stable-key contract yet. Fall back to
            // the legacy map, and warn exactly once per display so the
            // missing declaration surfaces without spamming the log.
            if (missingStableKeyLogged.add(key)) {
                LogUtil.warn("[grim-history] check " + display
                        + " has no stableKey declared; falling back to StableKeyMapping. "
                        + "Populate the @CheckData.stableKey / CheckInfo.stableKey field.");
            }
            stable = StableKeyMapping.stableKeyFor(display)
                    .orElse(StableKeyMapping.legacyFallback(display));
        }
        String description = check.getDescription();
        String introducedVersion = safePluginVersion();
        int id = checkRegistry.intern(stable, display, description, introducedVersion);
        checkIdCache.put(key, id);
        return id;
    }

    private static @Nullable String safePluginVersion() {
        try {
            return GrimAPI.INSTANCE.getExternalAPI().getGrimVersion();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private record UserSession(UUID uuid, UUID sessionId) {}
}
