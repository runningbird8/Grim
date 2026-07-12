package ac.grim.grimac.manager.datastore;

import ac.grim.grimac.api.storage.DataStore;
import ac.grim.grimac.api.storage.category.Categories;
import ac.grim.grimac.api.storage.model.SessionRecord;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Concrete {@link SessionTracker}. The disabled-datastore path uses
 * {@link SessionTracker#NOOP} instead.
 */
public final class SessionTrackerImpl implements SessionTracker {

    private static final Object[] STATE_LOCKS = createStateLocks();

    private final DataStore store;
    private final long heartbeatIntervalMs;
    private final @Nullable UUID startupId;
    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    public SessionTrackerImpl(@NotNull DataStore store, @NotNull String serverName, long heartbeatIntervalMs) {
        this(store, serverName, heartbeatIntervalMs, null);
    }

    public SessionTrackerImpl(
            @NotNull DataStore store,
            @NotNull String serverName,
            long heartbeatIntervalMs,
            @Nullable UUID startupId) {
        this.store = store;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.startupId = startupId;
    }

    @Override
    public @NotNull UUID open(
            @NotNull UUID playerUuid,
            long now,
            @NotNull ClientMeta meta) {
        // Every completed JOIN receives a new generation. This matters when
        // PacketEvents still holds an old User for the same UUID: its later
        // QUIT must not be able to close the replacement's session.
        synchronized (stateLock(playerUuid)) {
            UUID sessionId = UUID.randomUUID();
            State fresh = new State(sessionId, now, now, now, meta);
            State current = states.put(playerUuid, fresh);
            if (current != null) {
                // Reconnect supersedes the prior generation immediately. Close
                // that exact row before opening the replacement so it cannot
                // remain indefinitely open when its delayed QUIT is correctly
                // rejected by the generation CAS.
                State superseded = new State(current.sessionId, current.startedEpochMs,
                        current.lastActivityEpochMs, now, current.cachedMeta);
                emit(superseded, playerUuid, current.lastActivityEpochMs, now);
            }
            emit(fresh, playerUuid, now, SessionRecord.OPEN);
            return sessionId;
        }
    }

    @Override
    public @Nullable UUID observeActivity(
            @NotNull UUID playerUuid,
            @NotNull UUID expectedSessionId,
            long now,
            @NotNull ClientMeta meta) {
        synchronized (stateLock(playerUuid)) {
            State current = states.get(playerUuid);
            if (current == null || !current.sessionId.equals(expectedSessionId)) {
                // Only a completed JOIN may create an owned generation. A
                // pre-JOIN or post-close packet cannot safely synthesize one:
                // its later disconnect may be stale or may never be observed.
                return null;
            }
            State next = new State(current.sessionId, current.startedEpochMs, now, now,
                    mergeMeta(current.cachedMeta, meta));
            states.put(playerUuid, next);
            emit(next, playerUuid, now, SessionRecord.OPEN);
            return next.sessionId;
        }
    }

    @Override
    public void pollHeartbeat(@NotNull UUID playerUuid, @NotNull UUID expectedSessionId, long now) {
        synchronized (stateLock(playerUuid)) {
            if (heartbeatIntervalMs <= 0) return;
            State current = states.get(playerUuid);
            if (current == null || !current.sessionId.equals(expectedSessionId)) return;
            if (now - current.lastEmittedEpochMs < heartbeatIntervalMs) return;
            State next = new State(current.sessionId, current.startedEpochMs, now, now, current.cachedMeta);
            states.put(playerUuid, next);
            emit(next, playerUuid, now, SessionRecord.OPEN);
        }
    }

    @Override
    public boolean close(
            @NotNull UUID playerUuid,
            @NotNull UUID expectedSessionId,
            long now,
            @NotNull ClientMeta meta) {
        synchronized (stateLock(playerUuid)) {
            State previous = states.get(playerUuid);
            if (previous == null || !previous.sessionId.equals(expectedSessionId)) return false;
            states.remove(playerUuid);

            // Emit last_activity from the exact state removed, with a distinct
            // graceful close timestamp.
            State closed = new State(previous.sessionId, previous.startedEpochMs,
                    previous.lastActivityEpochMs, now, mergeMeta(previous.cachedMeta, meta));
            emit(closed, playerUuid, previous.lastActivityEpochMs, now);
            return true;
        }
    }

    @Override
    public @Nullable UUID currentSessionId(@NotNull UUID playerUuid) {
        State s = states.get(playerUuid);
        return s == null ? null : s.sessionId;
    }

    private void emit(State s, UUID playerUuid, long now, long closedAt) {
        final UUID sessionId = s.sessionId;
        final long started = s.startedEpochMs;
        final ClientMeta meta = s.cachedMeta;
        store.submit(Categories.SESSION, e -> e
                .sessionId(sessionId)
                .playerUuid(playerUuid)
                .startedEpochMs(started)
                .lastActivityEpochMs(now)
                .closedAtEpochMs(closedAt)
                .clientBrand(meta.clientBrand())
                .clientVersion(meta.clientVersion())
                .startupId(startupId));
    }

    private static Object[] createStateLocks() {
        Object[] locks = new Object[64];
        for (int index = 0; index < locks.length; index++) locks[index] = new Object();
        return locks;
    }

    private static Object stateLock(UUID playerUuid) {
        return STATE_LOCKS[(playerUuid.hashCode() & Integer.MAX_VALUE) % STATE_LOCKS.length];
    }

    private static ClientMeta mergeMeta(ClientMeta current, ClientMeta incoming) {
        return new ClientMeta(
                latest(incoming.grimVersion(), current.grimVersion()),
                current.clientBrand() != null ? current.clientBrand() : incoming.clientBrand(),
                incoming.clientVersion() >= 0 ? incoming.clientVersion() : current.clientVersion(),
                latest(incoming.serverVersion(), current.serverVersion()));
    }

    private static <T> T latest(T incoming, T current) {
        return incoming != null ? incoming : current;
    }

    private record State(UUID sessionId,
                         long startedEpochMs,
                         long lastActivityEpochMs,
                         long lastEmittedEpochMs,
                         ClientMeta cachedMeta) {}
}
