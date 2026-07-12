package ac.grim.grimac.manager;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.manager.init.ReloadableInitable;
import ac.grim.grimac.manager.init.start.StartableInitable;
import ac.grim.grimac.platform.api.player.PlatformPlayer;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.LogUtil;
import ac.grim.grimac.utils.math.Location;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfo;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class SpectateManager implements StartableInitable, ReloadableInitable {
    static final long TELEPORT_TIMEOUT_TICKS = 100L;

    /** No Grim spectate lifecycle exists for the spectator. */
    public static final int SPECTATE_TELEPORT_NONE = 0;
    /** The packet is the captured target destination for an authoritative STARTING lifecycle. */
    public static final int SPECTATE_TELEPORT_EXPECTED_START = 1;
    /** The lifecycle is RETURNING; native rollback/correction teleports must remain untouched. */
    public static final int SPECTATE_TELEPORT_RETURNING = 2;
    /** A lifecycle exists, but this is not the validated STARTING target teleport. */
    public static final int SPECTATE_TELEPORT_UNEXPECTED_ACTIVE = 3;

    // Packet coordinates are copied from the captured Location. This epsilon only
    // absorbs representation noise; it is not a movement allowance.
    static final double PACKET_DESTINATION_TOLERANCE = 1.0E-6;

    // Reflection compatibility: value objects intentionally retain location()
    // and gameMode() accessors used by existing integrations.
    private final Map<UUID, SpectateState> spectatingPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, TimedOutEntry> timedOutEntries = new ConcurrentHashMap<>();
    private final Map<UUID, RetiredSession> retiredSessions = new ConcurrentHashMap<>();
    private final Set<UUID> hiddenPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Object> hiddenPlayerOwners = new ConcurrentHashMap<>();
    private final Set<String> allowedWorlds = ConcurrentHashMap.newKeySet();
    private final AtomicLong generations = new AtomicLong();
    private final TimeoutScheduler timeoutScheduler;

    private boolean checkWorld;

    public SpectateManager() {
        this((player, task, retired) -> {
            var handle = GrimAPI.INSTANCE.getScheduler().getEntityScheduler().runDelayed(
                    player, GrimAPI.INSTANCE.getGrimPlugin(), task, retired, TELEPORT_TIMEOUT_TICKS);
            return handle != null;
        });
    }

    // Backward-compatible test seam.
    SpectateManager(Consumer<Runnable> timeoutScheduler) {
        this((player, task, retired) -> {
            timeoutScheduler.accept(task);
            return true;
        });
    }

    SpectateManager(TimeoutScheduler timeoutScheduler) {
        this.timeoutScheduler = Objects.requireNonNull(timeoutScheduler);
    }

    @Override
    public void start() {
        reload();
    }

    @Override
    public void reload() {
        allowedWorlds.clear();
        allowedWorlds.addAll(GrimAPI.INSTANCE.getConfigManager().getConfig()
                .getStringListElse("spectators.allowed-worlds", new ArrayList<>()));
        checkWorld = !(allowedWorlds.isEmpty() || new ArrayList<>(allowedWorlds).get(0).isEmpty());
    }

    /**
     * Stable authorization API for integrations. This deliberately resolves the
     * platform player directly, so staff exempt from Grim tracking still work.
     */
    public boolean canStartSpectating(UUID uuid) {
        if (uuid == null) return false;
        if (isLifecycleActive(uuid)) return false;
        try {
            PlatformPlayer player = GrimAPI.INSTANCE.getPlatformPlayerFactory().getFromUUID(uuid);
            return isLocalOnlinePlayer(player, uuid) && player.hasPermission("grim.spectate");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Returns whether the stop command can launch a return. */
    public boolean isSpectating(UUID uuid) {
        if (uuid == null) return false;
        SpectateState state = spectatingPlayers.get(uuid);
        return state != null && !isReturning(state.phase);
    }

    /** Authoritative phase-aware check exemption for the whole lifecycle. */
    public boolean isLifecycleActive(UUID uuid) {
        return uuid != null && (spectatingPlayers.containsKey(uuid)
                || timedOutEntries.containsKey(uuid)
                || retiredSessions.containsKey(uuid));
    }

    /** Only STARTING/ACTIVE should suppress respawn gamemode changes. */
    public boolean shouldPreserveSpectatorGameMode(UUID uuid) {
        if (uuid == null) return false;
        SpectateState state = spectatingPlayers.get(uuid);
        return state != null && !isReturning(state.phase);
    }

    /**
     * Stable primitive classification API for packet integrations.
     *
     * <p>Returns one of {@link #SPECTATE_TELEPORT_NONE},
     * {@link #SPECTATE_TELEPORT_EXPECTED_START},
     * {@link #SPECTATE_TELEPORT_RETURNING}, or
     * {@link #SPECTATE_TELEPORT_UNEXPECTED_ACTIVE}. Expected START requires the
     * captured target UUID and packet coordinates, live local source and target,
     * the same loaded target world, and the target still being at the captured
     * destination at packet precision. A moved target causes the stale entry to
     * be rejected and safely returned instead of accepting a nearby landing.</p>
     */
    public int classifySpectateTeleport(UUID spectator, UUID target, double x, double y, double z) {
        if (spectator == null) return SPECTATE_TELEPORT_NONE;
        if (retiredSessions.containsKey(spectator)) return SPECTATE_TELEPORT_UNEXPECTED_ACTIVE;
        SpectateState state = spectatingPlayers.get(spectator);
        // Legitimate safety-return and final game-mode restoration packets must
        // remain native while their exact live returning state is present.
        if (state != null && isReturning(state.phase)) return SPECTATE_TELEPORT_RETURNING;
        // A timed-out entry can still complete after its safety return (and even
        // after a terminal kick attempt). It must never regain the native entry
        // allowance merely because the live state has already been restored.
        TimedOutEntry timedOut = timedOutEntries.get(spectator);
        if (timedOut != null) {
            return timedOut.correctionPending && coordinatesMatch(timedOut.location, x, y, z)
                    ? SPECTATE_TELEPORT_RETURNING
                    : SPECTATE_TELEPORT_UNEXPECTED_ACTIVE;
        }
        if (state == null) return SPECTATE_TELEPORT_NONE;
        if (state.phase == Phase.STARTING
                && state.authoritativeTarget
                && Objects.equals(state.targetUuid, target)
                && coordinatesMatch(state.targetLocation, x, y, z)
                && isAuthoritativeTargetStillValid(state)) {
            return SPECTATE_TELEPORT_EXPECTED_START;
        }
        return SPECTATE_TELEPORT_UNEXPECTED_ACTIVE;
    }

    public boolean shouldHidePlayer(GrimPlayer receiver, WrapperPlayServerPlayerInfo.PlayerData playerData) {
        return receiver != null && playerData != null
                && playerData.getUser() != null
                && playerData.getUser().getUUID() != null
                && shouldHidePlayer(receiver, playerData.getUser().getUUID());
    }

    public boolean shouldHidePlayer(GrimPlayer receiver, UUID uuid) {
        return receiver != null && uuid != null
                && !Objects.equals(uuid, receiver.uuid)
                && shouldBeHidden(uuid)
                && !(receiver.uuid != null && shouldBeHidden(receiver.uuid))
                && (!checkWorld || (receiver.platformPlayer != null
                && allowedWorlds.contains(receiver.platformPlayer.getWorld().getName())));
    }

    boolean shouldBeHidden(UUID uuid) {
        if (uuid == null) return false;
        SpectateState state = spectatingPlayers.get(uuid);
        if (state != null && state.phase == Phase.RESTORING_GAME_MODE) return false;
        if (retiredSessions.containsKey(uuid)) return true;
        if (timedOutEntries.containsKey(uuid)) return true;
        return state == null ? hiddenPlayers.contains(uuid) : state.phase != Phase.RESTORING_GAME_MODE;
    }

    /**
     * Location-only compatibility overload. It cannot authoritatively identify
     * the target, so classification will never label its entry packet expected.
     */
    public StartResult enable(@NotNull PlatformPlayer platformPlayer, @NotNull Location targetLocation) {
        return enableInternal(platformPlayer, null, targetLocation, false);
    }

    /** Starts an authoritative target-bound spectate lifecycle. */
    public StartResult enable(@NotNull PlatformPlayer platformPlayer, @NotNull PlatformPlayer targetPlayer,
                              @NotNull Location targetLocation) {
        return enableInternal(platformPlayer, targetPlayer, targetLocation, true);
    }

    private synchronized StartResult enableInternal(PlatformPlayer platformPlayer, PlatformPlayer targetPlayer,
                                                    Location targetLocation, boolean authoritative) {
        UUID uuid = platformPlayer.getUniqueId();
        if (retiredSessions.containsKey(uuid)) return StartResult.RECONNECT_REQUIRED;
        if (timedOutEntries.containsKey(uuid)) return StartResult.RECONNECT_REQUIRED;
        if (!isLocalOnlinePlayer(platformPlayer, uuid)) return StartResult.INVALID_SENDER;
        if (authoritative && !hasSpectatePermission(platformPlayer)) return StartResult.INVALID_SENDER;

        Location target = targetLocation.clone();
        if (!isValidLocation(target)) return StartResult.INVALID_TARGET;
        UUID targetUuid = authoritative && targetPlayer != null ? targetPlayer.getUniqueId() : null;
        if (authoritative && (!isLocalOnlinePlayer(targetPlayer, targetUuid)
                || !sameWorld(target, targetPlayer.getLocation()))) {
            return StartResult.INVALID_TARGET;
        }

        Location origin = platformPlayer.getLocation();
        if (!isValidLocation(origin)) return StartResult.START_FAILED;

        // Final TOCTOU check immediately before the first lifecycle mutation.
        if (!isLocalOnlinePlayer(platformPlayer, uuid)) return StartResult.INVALID_SENDER;
        if (authoritative && !hasSpectatePermission(platformPlayer)) return StartResult.INVALID_SENDER;
        if (authoritative && !isCapturedTargetValid(targetPlayer, targetUuid, target)) {
            return StartResult.INVALID_TARGET;
        }

        long session = generations.incrementAndGet();
        SpectateState state = new SpectateState(platformPlayer.getGameMode(), origin.clone(), Phase.STARTING,
                session, session, true, false, platformPlayer, currentSessionIdentity(platformPlayer),
                targetUuid, targetPlayer, target, authoritative);
        SpectateState existing = spectatingPlayers.putIfAbsent(uuid, state);
        if (existing != null) {
            return isReturning(existing.phase) ? StartResult.RETURNING : StartResult.ALREADY_SPECTATING;
        }

        try {
            platformPlayer.setGameMode(GameMode.SPECTATOR);
        } catch (Throwable throwable) {
            spectatingPlayers.remove(uuid, state);
            return StartResult.START_FAILED;
        }

        teleport(platformPlayer, target).whenComplete((success, throwable) -> {
            boolean entered = throwable == null && Boolean.TRUE.equals(success);
            if (entered && authoritative && !isAuthoritativeTargetStillValid(state)) {
                entered = false;
                sendIfOnline(platformPlayer,
                        "The spectate target left, transferred, changed worlds, or moved. Returning you to your previous location.",
                        NamedTextColor.RED);
            }
            handleActualEntryCompletion(platformPlayer, state, entered);
        });
        scheduleTimeout(platformPlayer,
                () -> handleEntryTimeout(platformPlayer, state),
                () -> retireSession(platformPlayer.getUniqueId(), state.session),
                () -> handleEntryTimeout(platformPlayer, state));
        return StartResult.STARTED;
    }

    private synchronized void handleActualEntryCompletion(PlatformPlayer platformPlayer, SpectateState initial,
                                                          boolean success) {
        UUID uuid = platformPlayer.getUniqueId();
        TimedOutEntry timedOut = timedOutEntries.get(uuid);
        if (timedOut != null && timedOut.session == initial.session) {
            if (!success) {
                timedOutEntries.remove(uuid, timedOut);
            } else {
                correctLateEntry(platformPlayer, timedOut);
            }
            return;
        }
        settleEntry(platformPlayer, initial.session, success);
    }

    private synchronized void handleEntryTimeout(PlatformPlayer platformPlayer, SpectateState initial) {
        SpectateState current = spectatingPlayers.get(platformPlayer.getUniqueId());
        if (current == null || current.session != initial.session || !current.entryPending) return;
        timedOutEntries.put(platformPlayer.getUniqueId(), new TimedOutEntry(current));
        settleEntry(platformPlayer, initial.session, false);
    }

    private void settleEntry(PlatformPlayer platformPlayer, long session, boolean success) {
        UUID uuid = platformPlayer.getUniqueId();
        SpectateState current = spectatingPlayers.get(uuid);
        if (current == null || current.session != session || !current.entryPending) return;

        if (current.phase == Phase.STARTING && success) {
            spectatingPlayers.replace(uuid, current, current.entrySettled(Phase.ACTIVE, false));
            return;
        }

        boolean notifyFailure = current.phase == Phase.STARTING && !success;
        SpectateState returning = current.entrySettled(Phase.RETURNING, notifyFailure);
        if (spectatingPlayers.replace(uuid, current, returning)) beginReturn(platformPlayer, returning);
    }

    private void correctLateEntry(PlatformPlayer platformPlayer, TimedOutEntry timedOut) {
        UUID uuid = platformPlayer.getUniqueId();
        if (!beginLateCorrection(uuid, timedOut)) return;
        CompletableFuture<Boolean> correction = teleport(platformPlayer, timedOut.location);
        correction.whenComplete((success, throwable) -> {
            if (throwable == null && Boolean.TRUE.equals(success)) {
                completeLateCorrection(platformPlayer, timedOut);
            } else {
                failLateCorrection(platformPlayer, timedOut,
                        "A delayed spectate teleport could not be corrected; reconnect to continue safely.");
            }
        });
        scheduleTimeout(platformPlayer,
                () -> failLateCorrection(platformPlayer, timedOut,
                        "A delayed spectate teleport correction timed out; reconnect to continue safely."),
                () -> retireSession(platformPlayer.getUniqueId(), timedOut.session),
                () -> failLateCorrection(platformPlayer, timedOut,
                        "A delayed spectate teleport correction could not be scheduled; reconnect to continue safely."));
    }

    private synchronized boolean beginLateCorrection(UUID uuid, TimedOutEntry timedOut) {
        return timedOutEntries.get(uuid) == timedOut && timedOut.beginCorrection();
    }

    private synchronized void failLateCorrection(PlatformPlayer platformPlayer, TimedOutEntry timedOut,
                                                 String reason) {
        if (timedOutEntries.get(platformPlayer.getUniqueId()) != timedOut || !timedOut.finishCorrection()) return;
        terminalKick(platformPlayer, timedOut.session, reason);
    }

    private synchronized void completeLateCorrection(PlatformPlayer platformPlayer, TimedOutEntry timedOut) {
        if (timedOutEntries.get(platformPlayer.getUniqueId()) != timedOut || !timedOut.finishCorrection()) return;
        SpectateState current = spectatingPlayers.get(platformPlayer.getUniqueId());
        boolean restored = current == null
                ? finishTombstonedCorrection(platformPlayer, timedOut)
                : current.session == timedOut.session && finishReturn(platformPlayer, current);
        if (!restored) {
            terminalKick(platformPlayer, timedOut.session,
                    "A delayed spectate teleport was corrected, but game mode restoration failed; reconnect to continue safely.");
            return;
        }
        timedOutEntries.remove(platformPlayer.getUniqueId(), timedOut);
    }

    public void onLogin(UUID uuid) {
        if (uuid == null) return;
        hiddenPlayers.add(uuid);
    }

    /** Binds hide-on-login state to this native player lifecycle. */
    public void onLogin(@NotNull PlatformPlayer platformPlayer) {
        onLogin(platformPlayer, currentSessionIdentity(platformPlayer));
    }

    public synchronized void onLogin(@NotNull PlatformPlayer platformPlayer, @NotNull Object sessionIdentity) {
        UUID uuid = platformPlayer.getUniqueId();
        if (uuid == null) return;
        hiddenPlayers.add(uuid);
        hiddenPlayerOwners.put(uuid, sessionIdentity);
    }

    public synchronized void onQuit(UUID uuid) {
        if (uuid == null) return;
        hiddenPlayers.remove(uuid);
        hiddenPlayerOwners.remove(uuid);
        spectatingPlayers.remove(uuid);
        timedOutEntries.remove(uuid);
        retiredSessions.remove(uuid);
    }

    /** Disconnect cleanup never propagates restore failures. */
    public synchronized void onQuit(@NotNull PlatformPlayer platformPlayer) {
        try {
            Object nativeIdentity = platformPlayer.getNative();
            if (nativeIdentity != null) {
                onQuit(platformPlayer, platformPlayer, nativeIdentity);
                return;
            }
        } catch (Throwable ignored) {
        }
        onQuit(platformPlayer, platformPlayer);
    }

    /** Disconnect cleanup bound to the exact PacketEvents/native connection lifecycle. */
    public synchronized void onQuit(@NotNull PlatformPlayer platformPlayer, @NotNull Object... sessionIdentities) {
        UUID uuid = platformPlayer.getUniqueId();
        Object hiddenOwner = hiddenPlayerOwners.get(uuid);
        if (containsIdentity(sessionIdentities, hiddenOwner)
                && hiddenPlayerOwners.remove(uuid, hiddenOwner)) hiddenPlayers.remove(uuid);
        TimedOutEntry timedOut = removeOwnedTimedOut(uuid, sessionIdentities);
        RetiredSession retired = removeOwnedRetired(uuid, sessionIdentities);
        SpectateState current = removeOwnedState(uuid, sessionIdentities);
        SpectateState restore = current != null
                ? current
                : retired != null ? retired.state : timedOut != null ? timedOut.state : null;
        if (restore == null) return;
        try {
            platformPlayer.setGameMode(restore.gameMode);
        } catch (Throwable throwable) {
            logError("Failed to restore game mode while disconnecting spectating player " + uuid, throwable);
        }
    }

    private SpectateState removeOwnedState(UUID uuid, Object[] owners) {
        SpectateState state = spectatingPlayers.get(uuid);
        return state != null && ownsSession(state, owners) && spectatingPlayers.remove(uuid, state) ? state : null;
    }

    private TimedOutEntry removeOwnedTimedOut(UUID uuid, Object[] owners) {
        TimedOutEntry timedOut = timedOutEntries.get(uuid);
        return timedOut != null && ownsSession(timedOut.state, owners)
                && timedOutEntries.remove(uuid, timedOut) ? timedOut : null;
    }

    private RetiredSession removeOwnedRetired(UUID uuid, Object[] owners) {
        RetiredSession retired = retiredSessions.get(uuid);
        return retired != null && ownsSession(retired.state, owners)
                && retiredSessions.remove(uuid, retired) ? retired : null;
    }

    private static boolean ownsSession(SpectateState state, Object[] owners) {
        return containsIdentity(owners, state.ownerIdentity)
                || (state.ownerIdentity == null && containsIdentity(owners, state.sourcePlayer));
    }

    private static boolean containsIdentity(Object[] identities, Object expected) {
        if (expected == null || identities == null) return false;
        for (Object identity : identities) if (identity == expected) return true;
        return false;
    }

    private static Object currentSessionIdentity(PlatformPlayer platformPlayer) {
        try {
            GrimPlayer grimPlayer = GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(platformPlayer.getUniqueId());
            if (grimPlayer != null && grimPlayer.user != null) return grimPlayer.user;
        } catch (Throwable ignored) {
        }
        try {
            Object nativePlayer = platformPlayer.getNative();
            if (nativePlayer != null) return nativePlayer;
        } catch (Throwable ignored) {
        }
        return platformPlayer;
    }

    /**
     * Stable, coherent return-state API. Location and gamemode are read from one
     * immutable lifecycle value; version changes whenever that value is replaced.
     */
    public synchronized SpectateReturnState getSpectateReturnState(UUID uuid) {
        if (uuid == null) return null;
        SpectateState state = spectatingPlayers.get(uuid);
        if (state == null) {
            RetiredSession retired = retiredSessions.get(uuid);
            TimedOutEntry timedOut = timedOutEntries.get(uuid);
            state = retired != null ? retired.state : timedOut != null ? timedOut.state : null;
        }
        return state == null ? null
                : new SpectateReturnState(state.gameMode, state.location, state.generation, state.session);
    }

    public record SpectateReturnState(GameMode gameMode, Location location, long version, long session) {
    }

    public synchronized StopResult disable(@NotNull PlatformPlayer platformPlayer, boolean teleportBack) {
        UUID uuid = platformPlayer.getUniqueId();
        if (!isLocalOnlinePlayer(platformPlayer, uuid)) return StopResult.INVALID_PLAYER;

        SpectateState current;
        SpectateState state;
        do {
            current = spectatingPlayers.get(uuid);
            if (current == null) return StopResult.NOT_SPECTATING;
            if (isReturning(current.phase)) return StopResult.ALREADY_RETURNING;
            // Final TOCTOU validation immediately before mutation.
            if (!isLocalOnlinePlayer(platformPlayer, uuid)) return StopResult.INVALID_PLAYER;
            state = current.withPhaseAndGeneration(Phase.RETURNING, generations.incrementAndGet());
        } while (!spectatingPlayers.replace(uuid, current, state));

        if (state.entryPending) return StopResult.RETURNING;

        if (!teleportBack || !state.location.isWorldLoaded()) {
            boolean restored = finishReturn(platformPlayer, state);
            if (!restored) return StopResult.RESTORE_FAILED;
            if (teleportBack) {
                sendIfOnline(platformPlayer,
                        "Your previous location is unavailable; stopped spectating here.", NamedTextColor.YELLOW);
            }
            return StopResult.STOPPED_HERE;
        }

        beginReturn(platformPlayer, state);
        return StopResult.RETURNING;
    }

    private void beginReturn(PlatformPlayer platformPlayer, SpectateState returning) {
        SpectateState dispatched = returning.returnDispatched(generations.incrementAndGet());
        if (!spectatingPlayers.replace(platformPlayer.getUniqueId(), returning, dispatched)) return;

        teleport(platformPlayer, dispatched.location).whenComplete((success, throwable) -> {
            if (throwable == null && Boolean.TRUE.equals(success)) {
                TimedOutEntry tombstone = timedOutEntries.get(platformPlayer.getUniqueId());
                if (tombstone != null && tombstone.session == dispatched.session) {
                    tombstone.preserve(dispatched);
                    finishReturn(platformPlayer, dispatched);
                    terminalKick(platformPlayer, dispatched.session,
                            "A spectate teleport timed out; reconnect to continue safely.");
                    return;
                }
                boolean finished = finishReturn(platformPlayer, dispatched);
                if (finished && dispatched.notifyFailure) {
                    sendIfOnline(platformPlayer,
                            "Teleport failed; spectating was cancelled. Please try again.", NamedTextColor.RED);
                }
                return;
            }
            TimedOutEntry tombstone = timedOutEntries.get(platformPlayer.getUniqueId());
            if (tombstone != null && tombstone.session == dispatched.session) {
                terminalKick(platformPlayer, dispatched.session,
                        "Spectate entry and safety return failed; reconnect to continue safely.");
            } else {
                makeRetryable(platformPlayer, dispatched,
                        dispatched.notifyFailure
                                ? "Spectate teleport failed and returning also failed. Use /grim stopspectating to retry or /grim stopspectating here to exit safely."
                                : "Teleport back failed. You are still spectating; please try again.");
            }
        });
        scheduleTimeout(platformPlayer,
                () -> handleReturnTimeout(platformPlayer, dispatched,
                        "Teleport back timed out. You are still spectating; please try again."),
                () -> retireSession(platformPlayer.getUniqueId(), dispatched.session),
                () -> handleReturnTimeout(platformPlayer, dispatched,
                        "Teleport back could not be scheduled. You are still spectating; please try again."));
    }

    private synchronized void handleReturnTimeout(PlatformPlayer platformPlayer, SpectateState returning,
                                                  String retryMessage) {
        if (!isCurrent(platformPlayer.getUniqueId(), returning)) return;
        TimedOutEntry timedOut = timedOutEntries.get(platformPlayer.getUniqueId());
        if (timedOut != null && timedOut.session == returning.session) {
            terminalKick(platformPlayer, returning.session,
                    "Spectate teleport timed out twice; reconnect to continue safely.");
        } else {
            makeRetryable(platformPlayer, returning, retryMessage);
        }
    }

    private synchronized void terminalKick(PlatformPlayer platformPlayer, long session, String reason) {
        TimedOutEntry tombstone = timedOutEntries.get(platformPlayer.getUniqueId());
        if (tombstone == null || tombstone.session != session) return;
        SpectateState current = spectatingPlayers.get(platformPlayer.getUniqueId());
        if (current != null && current.session == session) {
            tombstone.preserve(current);
        }
        SpectateState unsafeState = current != null && current.session == session ? current : tombstone.state;
        try {
            try {
                platformPlayer.setGameMode(unsafeState.gameMode);
            } catch (Throwable throwable) {
                logError("Failed to restore game mode before terminal spectate disconnect for "
                        + platformPlayer.getUniqueId(), throwable);
            }
            try {
                platformPlayer.kickPlayer(reason);
            } catch (Throwable throwable) {
                logError("Failed to disconnect player after an untrustworthy spectate teleport; "
                        + "the player remains blocked from spectating until disconnect", throwable);
                sendIfOnline(platformPlayer,
                        "Spectate transport is unsafe. Reconnect before spectating again.", NamedTextColor.RED);
            }
        } finally {
            if (current != null && current.session == session) {
                spectatingPlayers.remove(platformPlayer.getUniqueId(), current);
            }
        }
    }

    private boolean finishTombstonedCorrection(PlatformPlayer platformPlayer, TimedOutEntry timedOut) {
        UUID uuid = platformPlayer.getUniqueId();
        SpectateState restoring = timedOut.state.withPhaseAndGeneration(
                Phase.RESTORING_GAME_MODE, generations.incrementAndGet());
        if (spectatingPlayers.putIfAbsent(uuid, restoring) != null) return false;
        try {
            platformPlayer.setGameMode(restoring.gameMode);
        } catch (Throwable throwable) {
            spectatingPlayers.remove(uuid, restoring);
            return false;
        }
        return spectatingPlayers.remove(uuid, restoring);
    }

    private boolean finishReturn(PlatformPlayer platformPlayer, SpectateState returning) {
        UUID uuid = platformPlayer.getUniqueId();
        if (uuid == null || returning.phase != Phase.RETURNING) return false;
        SpectateState restoring = returning.withPhaseAndGeneration(
                Phase.RESTORING_GAME_MODE, generations.incrementAndGet());
        if (!spectatingPlayers.replace(uuid, returning, restoring)) return false;
        try {
            platformPlayer.setGameMode(restoring.gameMode);
        } catch (Throwable throwable) {
            makeRetryable(platformPlayer, restoring,
                    "Could not restore your previous game mode. Use /grim stopspectating to retry.");
            return false;
        }
        return spectatingPlayers.remove(uuid, restoring);
    }

    private void makeRetryable(PlatformPlayer platformPlayer, SpectateState returning, String message) {
        SpectateState active = returning.withPhaseAndGeneration(Phase.ACTIVE, generations.incrementAndGet());
        if (spectatingPlayers.replace(platformPlayer.getUniqueId(), returning, active)) {
            sendIfOnline(platformPlayer, message, NamedTextColor.RED);
        }
    }

    private boolean isCurrent(UUID uuid, SpectateState expected) {
        return spectatingPlayers.get(uuid) == expected;
    }

    /**
     * Entity-scheduler retirement invalidates callbacks but cannot safely mutate
     * the entity. Preserve the exact matching session until quit cleanup can
     * restore its game mode; stale callbacks for older sessions are ignored.
     */
    private synchronized void retireSession(UUID uuid, long session) {
        SpectateState current = spectatingPlayers.get(uuid);
        TimedOutEntry timedOut = timedOutEntries.get(uuid);
        SpectateState snapshot = current != null && current.session == session
                ? current
                : timedOut != null && timedOut.session == session ? timedOut.state : null;
        if (snapshot == null) return;

        RetiredSession retired = retiredSessions.get(uuid);
        if (retired != null && retired.session != session) return;
        retiredSessions.put(uuid, new RetiredSession(session, snapshot));
        if (current != null && current.session == session) spectatingPlayers.remove(uuid, current);
        if (timedOut != null && timedOut.session == session) timedOutEntries.remove(uuid, timedOut);
    }

    private void scheduleTimeout(PlatformPlayer player, Runnable timeout, Runnable retiredCleanup,
                                 Runnable rejectedLive) {
        AtomicBoolean invoked = new AtomicBoolean();
        Runnable guardedTimeout = () -> {
            if (invoked.compareAndSet(false, true)) timeout.run();
        };
        Runnable guardedRetired = () -> {
            if (invoked.compareAndSet(false, true)) retiredCleanup.run();
        };
        try {
            if (!timeoutScheduler.schedule(player, guardedTimeout, guardedRetired)
                    && invoked.compareAndSet(false, true)) {
                rejectedLive.run();
            }
        } catch (Throwable throwable) {
            if (invoked.compareAndSet(false, true)) rejectedLive.run();
        }
    }

    private static boolean isValidLocation(Location location) {
        return location != null && location.isWorldLoaded()
                && Double.isFinite(location.getX()) && Double.isFinite(location.getY())
                && Double.isFinite(location.getZ()) && Float.isFinite(location.getYaw())
                && Float.isFinite(location.getPitch());
    }

    private static boolean isLocalOnlinePlayer(PlatformPlayer player, UUID expectedUuid) {
        try {
            return player != null && expectedUuid != null && expectedUuid.equals(player.getUniqueId())
                    && player.isOnline() && !player.isExternalPlayer();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean hasSpectatePermission(PlatformPlayer player) {
        try {
            return player.hasPermission("grim.spectate");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isCapturedTargetValid(PlatformPlayer target, UUID targetUuid, Location captured) {
        if (!isLocalOnlinePlayer(target, targetUuid)) return false;
        Location current = target.getLocation();
        return isValidLocation(current) && sameWorld(captured, current)
                && coordinatesMatch(captured, current.getX(), current.getY(), current.getZ());
    }

    private static boolean isAuthoritativeTargetStillValid(SpectateState state) {
        try {
            if (!isLocalOnlinePlayer(state.sourcePlayer, state.sourcePlayer.getUniqueId())) return false;
            Location sourceLocation = state.sourcePlayer.getLocation();
            if (!isValidLocation(sourceLocation) || !sameWorld(sourceLocation, state.targetLocation)) return false;
            return isCapturedTargetValid(state.targetPlayer, state.targetUuid, state.targetLocation);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean coordinatesMatch(Location captured, double x, double y, double z) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                && Math.abs(captured.getX() - x) <= PACKET_DESTINATION_TOLERANCE
                && Math.abs(captured.getY() - y) <= PACKET_DESTINATION_TOLERANCE
                && Math.abs(captured.getZ() - z) <= PACKET_DESTINATION_TOLERANCE;
    }

    private static boolean sameWorld(Location first, Location second) {
        if (!isValidLocation(first) || !isValidLocation(second)) return false;
        if (first.getWorld() == second.getWorld()) return true;
        try {
            UUID firstId = first.getWorld().getUID();
            UUID secondId = second.getWorld().getUID();
            return firstId != null && firstId.equals(secondId);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private CompletableFuture<Boolean> teleport(PlatformPlayer platformPlayer, Location location) {
        try {
            CompletableFuture<Boolean> future = platformPlayer.teleportAsync(location);
            return future == null
                    ? CompletableFuture.failedFuture(new IllegalStateException("Platform returned a null teleport future"))
                    : future;
        } catch (Throwable throwable) {
            return CompletableFuture.failedFuture(throwable);
        }
    }

    private static void sendIfOnline(PlatformPlayer player, String message, NamedTextColor color) {
        try {
            if (player.isOnline()) player.sendMessage(Component.text(message, color));
        } catch (Throwable ignored) {
        }
    }

    private static void logError(String message, Throwable throwable) {
        try {
            LogUtil.error(message, throwable);
        } catch (Throwable ignored) {
            // Cleanup must remain non-throwing even during early shutdown when
            // the platform logger may already be unavailable.
        }
    }

    /** Called when another source changes a settled spectating player's gamemode. */
    public synchronized void handlePlayerStopSpectating(UUID uuid) {
        if (uuid == null) return;
        SpectateState current = spectatingPlayers.get(uuid);
        if (current != null && !isReturning(current.phase) && !current.entryPending) {
            spectatingPlayers.remove(uuid, current);
        }
    }

    public synchronized void handlePlayerStopSpectating(UUID uuid, PlatformPlayer platformPlayer) {
        GameMode requested = platformPlayer == null ? GameMode.SURVIVAL : platformPlayer.getGameMode();
        handlePlayerStopSpectating(uuid, platformPlayer, requested);
    }

    /**
     * During STARTING, serialize the return behind entry while preserving the
     * externally requested non-spectator gamemode as the new return gamemode.
     */
    public synchronized void handlePlayerStopSpectating(UUID uuid, PlatformPlayer platformPlayer, GameMode requested) {
        if (uuid == null) return;
        SpectateState current = spectatingPlayers.get(uuid);
        if (current == null || isReturning(current.phase)) return;
        if (!current.entryPending) {
            spectatingPlayers.remove(uuid, current);
            return;
        }
        SpectateState returning = current.withGameModeAndPhase(
                requested, Phase.RETURNING, generations.incrementAndGet());
        spectatingPlayers.replace(uuid, current, returning);
    }

    public enum StartResult {
        STARTED,
        ALREADY_SPECTATING,
        RETURNING,
        RECONNECT_REQUIRED,
        INVALID_SENDER,
        INVALID_TARGET,
        START_FAILED
    }

    public enum StopResult {
        RETURNING,
        STOPPED_HERE,
        RESTORE_FAILED,
        ALREADY_RETURNING,
        NOT_SPECTATING,
        INVALID_PLAYER
    }

    private enum Phase {
        STARTING,
        ACTIVE,
        RETURNING,
        RESTORING_GAME_MODE
    }

    private static boolean isReturning(Phase phase) {
        return phase == Phase.RETURNING || phase == Phase.RESTORING_GAME_MODE;
    }

    private record SpectateState(GameMode gameMode, Location location, Phase phase, long generation, long session,
                                 boolean entryPending, boolean notifyFailure, PlatformPlayer sourcePlayer,
                                 Object ownerIdentity,
                                 UUID targetUuid, PlatformPlayer targetPlayer, Location targetLocation,
                                 boolean authoritativeTarget) {
        private SpectateState withPhaseAndGeneration(Phase newPhase, long newGeneration) {
            return new SpectateState(gameMode, location, newPhase, newGeneration, session, entryPending,
                    notifyFailure, sourcePlayer, ownerIdentity, targetUuid, targetPlayer, targetLocation, authoritativeTarget);
        }

        private SpectateState withGameModeAndPhase(GameMode newGameMode, Phase newPhase, long newGeneration) {
            return new SpectateState(newGameMode, location, newPhase, newGeneration, session, entryPending,
                    notifyFailure, sourcePlayer, ownerIdentity, targetUuid, targetPlayer, targetLocation, authoritativeTarget);
        }

        private SpectateState entrySettled(Phase newPhase, boolean shouldNotifyFailure) {
            return new SpectateState(gameMode, location, newPhase, generation, session, false,
                    shouldNotifyFailure, sourcePlayer, ownerIdentity, targetUuid, targetPlayer, targetLocation, authoritativeTarget);
        }

        private SpectateState returnDispatched(long newGeneration) {
            return new SpectateState(gameMode, location, Phase.RETURNING, newGeneration, session, false,
                    notifyFailure, sourcePlayer, ownerIdentity, targetUuid, targetPlayer, targetLocation, authoritativeTarget);
        }
    }

    /**
     * Quarantines the exact session whose entry completion is no longer
     * trustworthy. The snapshot remains authoritative even if a successful
     * safety return has already removed the live state before the late entry
     * future completes.
     */
    private static final class TimedOutEntry {
        private final long session;
        private final Location location;
        private volatile SpectateState state;
        private volatile boolean correctionPending;

        private TimedOutEntry(SpectateState state) {
            this.session = state.session;
            this.location = state.location;
            this.state = state;
        }

        private void preserve(SpectateState current) {
            if (current.session == session) state = current;
        }

        private synchronized boolean beginCorrection() {
            if (correctionPending) return false;
            correctionPending = true;
            return true;
        }

        private synchronized boolean finishCorrection() {
            if (!correctionPending) return false;
            correctionPending = false;
            return true;
        }
    }

    private record RetiredSession(long session, SpectateState state) {
    }

    @FunctionalInterface
    interface TimeoutScheduler {
        boolean schedule(PlatformPlayer player, Runnable task, Runnable retired);
    }
}
