package ac.grim.grimac.manager;

import ac.grim.grimac.platform.api.player.PlatformPlayer;
import ac.grim.grimac.platform.api.world.PlatformWorld;
import ac.grim.grimac.utils.math.Location;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.AbstractSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpectateManagerTest {
    private final PlatformWorld loadedWorld = proxyWorld(true);
    private final Location origin = new Location(loadedWorld, 1, 2, 3);
    private final Location target = new Location(loadedWorld, 40, 50, 60);
    private final Deque<Runnable> timeouts = new ArrayDeque<>();

    private SpectateManager manager() {
        return new SpectateManager(timeouts::addLast);
    }

    @Test
    void successfulLifecycleRestoresFirstOriginAndGameMode() {
        SpectateManager manager = manager();
        FakePlayer fake = new FakePlayer(origin, GameMode.CREATIVE);
        CompletableFuture<Boolean> enter = fake.nextTeleport();

        assertEquals(SpectateManager.StartResult.STARTED, manager.enable(fake.player, target));
        assertEquals(GameMode.SPECTATOR, fake.gameMode);
        assertTrue(manager.isLifecycleActive(fake.uuid));
        enter.complete(true);

        CompletableFuture<Boolean> exit = fake.nextTeleport();
        assertEquals(SpectateManager.StopResult.RETURNING, manager.disable(fake.player, true));
        assertEquals(SpectateManager.SPECTATE_TELEPORT_RETURNING,
                manager.classifySpectateTeleport(fake.uuid, UUID.randomUUID(), 0, 0, 0));
        assertEquals(origin, fake.teleportLocations.get(1));
        exit.complete(true);

        assertEquals(GameMode.CREATIVE, fake.gameMode);
        assertFalse(manager.isLifecycleActive(fake.uuid));
        assertFalse(manager.shouldBeHidden(fake.uuid));
    }

    @Test
    void falseAndExceptionalEnterResultsRollbackWithRetryMessage() {
        for (boolean exceptional : List.of(false, true)) {
            SpectateManager manager = manager();
            FakePlayer fake = new FakePlayer(origin, GameMode.SURVIVAL);
            CompletableFuture<Boolean> enter = fake.nextTeleport();
            CompletableFuture<Boolean> rollback = fake.nextTeleport();

            manager.enable(fake.player, target);
            if (exceptional) enter.completeExceptionally(new IllegalStateException("boom"));
            else enter.complete(false);
            rollback.complete(true);

            assertEquals(GameMode.SURVIVAL, fake.gameMode);
            assertFalse(manager.isLifecycleActive(fake.uuid));
            assertEquals(1, fake.messages.size());
        }
    }

    @Test
    void duplicateEnterAndStopDoNotLaunchExtraTeleports() {
        SpectateManager manager = manager();
        FakePlayer fake = new FakePlayer(origin, GameMode.SURVIVAL);
        CompletableFuture<Boolean> enter = fake.nextTeleport();

        assertEquals(SpectateManager.StartResult.STARTED, manager.enable(fake.player, target));
        assertEquals(SpectateManager.StartResult.ALREADY_SPECTATING, manager.enable(fake.player, new Location(loadedWorld, 99, 99, 99)));
        assertEquals(1, fake.teleportLocations.size());
        enter.complete(true);

        CompletableFuture<Boolean> exit = fake.nextTeleport();
        assertEquals(SpectateManager.StopResult.RETURNING, manager.disable(fake.player, true));
        assertEquals(SpectateManager.StopResult.ALREADY_RETURNING, manager.disable(fake.player, true));
        assertEquals(2, fake.teleportLocations.size());
        assertEquals(origin, fake.teleportLocations.get(1));
        exit.complete(true);
    }

    @Test
    void activeLifecycleCannotStartAnotherSpectateSession() {
        SpectateManager manager = manager();
        FakePlayer fake = new FakePlayer(origin, GameMode.SURVIVAL);
        fake.nextTeleport();
        assertEquals(SpectateManager.StartResult.STARTED, manager.enable(fake.player, target));

        assertFalse(manager.canStartSpectating(fake.uuid));
    }

    @Test
    void rapidExitInvalidatesStaleEnterCallback() {
        SpectateManager manager = manager();
        FakePlayer fake = new FakePlayer(origin, GameMode.CREATIVE);
        CompletableFuture<Boolean> enter = fake.nextTeleport();
        manager.enable(fake.player, target);

        CompletableFuture<Boolean> exit = fake.nextTeleport();
        manager.disable(fake.player, true);
        exit.complete(true);
        enter.completeExceptionally(new IllegalStateException("late"));

        assertFalse(manager.isLifecycleActive(fake.uuid));
        assertEquals(GameMode.CREATIVE, fake.gameMode);
        assertTrue(fake.messages.isEmpty());
    }

    @Test
    void staleCallbackCannotRemoveNewGeneration() {
        SpectateManager manager = manager();
        FakePlayer fake = new FakePlayer(origin, GameMode.SURVIVAL);
        FakePlayer spectateTarget = new FakePlayer(target, GameMode.SURVIVAL);
        CompletableFuture<Boolean> firstEnter = fake.nextTeleport();
        manager.enable(fake.player, spectateTarget.player, target);
        firstEnter.complete(true);
        timeouts.removeFirst();

        CompletableFuture<Boolean> staleExit = fake.nextTeleport();
        manager.disable(fake.player, true);
        timeouts.removeFirst().run();

        CompletableFuture<Boolean> successfulExit = fake.nextTeleport();
        manager.disable(fake.player, true);
        successfulExit.complete(true);

        Location secondOrigin = new Location(loadedWorld, 7, 8, 9);
        fake.location = secondOrigin;
        CompletableFuture<Boolean> secondEnter = fake.nextTeleport();
        manager.enable(fake.player, spectateTarget.player, target);
        staleExit.complete(true);

        assertTrue(manager.isLifecycleActive(fake.uuid));
        assertEquals(GameMode.SPECTATOR, fake.gameMode);
        assertEquals(1, fake.messages.size());
        assertEquals(SpectateManager.SPECTATE_TELEPORT_EXPECTED_START,
                manager.classifySpectateTeleport(fake.uuid, spectateTarget.uuid, 40, 50, 60));
        secondEnter.complete(true);
    }

    @Test
    void gameModeAckDuringStartingDefersCleanupUntilSafeReturn() {
        SpectateManager manager = manager();
        FakePlayer fake = new FakePlayer(origin, GameMode.SURVIVAL);
        CompletableFuture<Boolean> enter = fake.nextTeleport();
        CompletableFuture<Boolean> exit = fake.nextTeleport();
        manager.enable(fake.player, target);

        manager.handlePlayerStopSpectating(fake.uuid, fake.player);
        assertTrue(manager.isLifecycleActive(fake.uuid));
        enter.complete(true);
        assertEquals(target, fake.location);
        exit.complete(true);

        assertEquals(origin, fake.location);
        assertFalse(manager.isLifecycleActive(fake.uuid));
    }

    @Test
    void failedReturnBecomesRetryable() {
        SpectateManager manager = manager();
        FakePlayer fake = new FakePlayer(origin, GameMode.SURVIVAL);
        CompletableFuture<Boolean> enter = fake.nextTeleport();
        manager.enable(fake.player, target);
        enter.complete(true);

        CompletableFuture<Boolean> firstExit = fake.nextTeleport();
        manager.disable(fake.player, true);
        firstExit.complete(false);

        assertTrue(manager.isSpectating(fake.uuid));
        assertTrue(manager.shouldBeHidden(fake.uuid));
        assertEquals(1, fake.messages.size());
        fake.nextTeleport();
        assertEquals(SpectateManager.StopResult.RETURNING, manager.disable(fake.player, true));
    }

    @Test
    void disconnectRestoresGameModeWithoutTeleportAndInvalidatesHungCallback() {
        SpectateManager manager = manager();
        FakePlayer fake = new FakePlayer(origin, GameMode.CREATIVE);
        CompletableFuture<Boolean> hungEnter = fake.nextTeleport();
        manager.enable(fake.player, target);

        manager.onQuit(fake.player);
        hungEnter.complete(false);

        assertEquals(GameMode.CREATIVE, fake.gameMode);
        assertFalse(manager.isLifecycleActive(fake.uuid));
        assertEquals(1, fake.teleportLocations.size());
        assertTrue(fake.messages.isEmpty());
    }

    @Test
    void delayedOldDisconnectCannotClearOrRestoreReplacementLifecycle() {
        SpectateManager manager = manager();
        UUID sharedUuid = UUID.randomUUID();
        FakePlayer old = new FakePlayer(sharedUuid, origin, GameMode.CREATIVE);
        FakePlayer replacement = new FakePlayer(sharedUuid, origin, GameMode.SURVIVAL);
        replacement.nextTeleport();
        manager.onLogin(replacement.player);
        assertEquals(SpectateManager.StartResult.STARTED, manager.enable(replacement.player, target));

        manager.onQuit(old.player);

        assertTrue(manager.isLifecycleActive(sharedUuid));
        assertEquals(GameMode.SPECTATOR, replacement.gameMode);
        assertEquals(1, replacement.gameModeChanges);
        assertEquals(GameMode.CREATIVE, old.gameMode);
    }

    @Test
    void delayedOldQuitCannotClearReplacementHideStateDuringLoginPublication() throws Exception {
        SpectateManager manager = manager();
        UUID sharedUuid = UUID.randomUUID();
        FakePlayer old = new FakePlayer(sharedUuid, origin, GameMode.CREATIVE);
        FakePlayer replacement = new FakePlayer(sharedUuid, origin, GameMode.SURVIVAL);
        Object oldIdentity = new Object();
        Object replacementIdentity = new Object();
        BlockingSet<UUID> hiddenPlayers = new BlockingSet<>(hiddenPlayers(manager), sharedUuid);
        setHiddenPlayers(manager, hiddenPlayers);
        manager.onLogin(old.player, oldIdentity);

        hiddenPlayers.blockNextAdd();
        Thread replacementLogin = new Thread(
                () -> manager.onLogin(replacement.player, replacementIdentity), "replacement-login");
        replacementLogin.start();
        hiddenPlayers.awaitBlockedAdd();

        Thread delayedOldQuit = new Thread(
                () -> manager.onQuit(old.player, oldIdentity), "delayed-old-quit");
        delayedOldQuit.start();
        assertEquals(Thread.State.BLOCKED, awaitBlockedOrTerminated(delayedOldQuit));

        hiddenPlayers.releaseAdd();
        replacementLogin.join(TimeUnit.SECONDS.toMillis(5));
        delayedOldQuit.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(replacementLogin.isAlive());
        assertFalse(delayedOldQuit.isAlive());

        assertTrue(manager.shouldBeHidden(sharedUuid));
        assertTrue(hiddenPlayers(manager).contains(sharedUuid));
        assertSame(replacementIdentity, hiddenPlayerOwners(manager).get(sharedUuid));
    }

    @Test
    void connectionTokenProtectsLifecycleWhenPlatformWrapperIsReused() {
        SpectateManager manager = manager();
        FakePlayer replacement = new FakePlayer(origin, GameMode.SURVIVAL);
        Object replacementConnection = new Object();
        replacement.nextTeleport();
        manager.onLogin(replacement.player, replacementConnection);
        assertEquals(SpectateManager.StartResult.STARTED, manager.enable(replacement.player, target));

        manager.onQuit(replacement.player, new Object());

        assertTrue(manager.isLifecycleActive(replacement.uuid));
        assertEquals(GameMode.SPECTATOR, replacement.gameMode);
        assertTrue(manager.shouldBeHidden(replacement.uuid));
    }

    @Test
    void fallbackNativeOwnerCleansOnOrdinaryDisconnect() {
        SpectateManager manager = manager();
        FakePlayer fake = new FakePlayer(origin, GameMode.CREATIVE);
        fake.nextTeleport();
        manager.enable(fake.player, target);

        manager.onQuit(fake.player, new Object(), fake.nativeIdentity);

        assertFalse(manager.isLifecycleActive(fake.uuid));
        assertEquals(GameMode.CREATIVE, fake.gameMode);
    }

    @Test
    void hungEnterCanBeStoppedButRejectsDuplicateEnter() {
        SpectateManager manager = manager();
        FakePlayer fake = new FakePlayer(origin, GameMode.SURVIVAL);
        fake.nextTeleport();
        manager.enable(fake.player, target);

        assertEquals(SpectateManager.StartResult.ALREADY_SPECTATING, manager.enable(fake.player, target));
        fake.nextTeleport();
        assertEquals(SpectateManager.StopResult.RETURNING, manager.disable(fake.player, true));
        assertEquals(SpectateManager.StartResult.RETURNING, manager.enable(fake.player, target));
    }

    @Test
    void enterTimeoutRollsBackAndLateCompletionIsStale() {
        SpectateManager manager = manager();
        FakePlayer fake = new FakePlayer(origin, GameMode.CREATIVE);
        CompletableFuture<Boolean> hungEnter = fake.nextTeleport();
        CompletableFuture<Boolean> rollback = fake.nextTeleport();
        CompletableFuture<Boolean> lateCorrection = fake.nextTeleport();
        manager.enable(fake.player, target);

        timeouts.removeFirst().run();
        rollback.complete(true);
        hungEnter.complete(true);
        lateCorrection.complete(true);

        assertFalse(manager.isLifecycleActive(fake.uuid));
        assertEquals(GameMode.CREATIVE, fake.gameMode);
        assertEquals(1, fake.kicks);
        assertEquals(origin, fake.location);
    }

    @Test
    void returnTimeoutBecomesRetryableAndLateCompletionIsStale() {
        SpectateManager manager = manager();
        FakePlayer fake = new FakePlayer(origin, GameMode.SURVIVAL);
        CompletableFuture<Boolean> enter = fake.nextTeleport();
        manager.enable(fake.player, target);
        enter.complete(true);
        timeouts.removeFirst(); // successful enter's now-stale timeout

        CompletableFuture<Boolean> hungReturn = fake.nextTeleport();
        manager.disable(fake.player, true);

        assertFalse(manager.isSpectating(fake.uuid));
        assertTrue(manager.shouldBeHidden(fake.uuid));
        assertEquals(SpectateManager.SPECTATE_TELEPORT_RETURNING,
                manager.classifySpectateTeleport(fake.uuid, UUID.randomUUID(), 0, 0, 0));
        timeouts.removeFirst().run();
        hungReturn.complete(true);

        assertTrue(manager.isSpectating(fake.uuid));
        assertEquals(GameMode.SPECTATOR, fake.gameMode);
        assertEquals(1, fake.messages.size());
    }

    @Test
    void reentrantGameModePacketHookSeesReturningState() {
        SpectateManager manager = manager();
        FakePlayer fake = new FakePlayer(origin, GameMode.SURVIVAL);
        CompletableFuture<Boolean> enter = fake.nextTeleport();
        manager.enable(fake.player, target);
        enter.complete(true);

        CompletableFuture<Boolean> exit = fake.nextTeleport();
        manager.disable(fake.player, true);
        fake.onSetGameMode = () -> manager.handlePlayerStopSpectating(fake.uuid);
        exit.complete(true);

        assertFalse(manager.isLifecycleActive(fake.uuid));
        assertEquals(GameMode.SURVIVAL, fake.gameMode);
    }

    @Test
    void gameModeRestorationIsVisibleOnlyDuringTheFinalRestoreHook() {
        SpectateManager manager = manager();
        FakePlayer fake = new FakePlayer(origin, GameMode.SURVIVAL);
        CompletableFuture<Boolean> enter = fake.nextTeleport();
        manager.enable(fake.player, target);
        enter.complete(true);

        CompletableFuture<Boolean> exit = fake.nextTeleport();
        manager.disable(fake.player, true);
        AtomicReference<Boolean> hiddenDuringRestore = new AtomicReference<>();
        AtomicReference<Boolean> spectatingDuringRestore = new AtomicReference<>();
        AtomicReference<Boolean> lifecycleDuringRestore = new AtomicReference<>();
        AtomicReference<Integer> classificationDuringRestore = new AtomicReference<>();
        fake.onSetGameMode = () -> {
            hiddenDuringRestore.set(manager.shouldBeHidden(fake.uuid));
            spectatingDuringRestore.set(manager.isSpectating(fake.uuid));
            lifecycleDuringRestore.set(manager.isLifecycleActive(fake.uuid));
            classificationDuringRestore.set(manager.classifySpectateTeleport(
                    fake.uuid, UUID.randomUUID(), 0, 0, 0));
            manager.handlePlayerStopSpectating(fake.uuid);
        };
        exit.complete(true);

        assertFalse(hiddenDuringRestore.get());
        assertFalse(spectatingDuringRestore.get());
        assertTrue(lifecycleDuringRestore.get());
        assertEquals(SpectateManager.SPECTATE_TELEPORT_RETURNING, classificationDuringRestore.get());
        assertFalse(manager.isLifecycleActive(fake.uuid));
        assertFalse(manager.shouldBeHidden(fake.uuid));
    }

    @Test
    void failedGameModeRestorationReturnsToActiveAndHidden() {
        SpectateManager manager = manager();
        FakePlayer fake = new FakePlayer(origin, GameMode.CREATIVE);
        CompletableFuture<Boolean> enter = fake.nextTeleport();
        manager.enable(fake.player, target);
        enter.complete(true);
        fake.throwOnSetGameMode = true;

        assertEquals(SpectateManager.StopResult.RESTORE_FAILED, manager.disable(fake.player, false));

        assertTrue(manager.isSpectating(fake.uuid));
        assertTrue(manager.isLifecycleActive(fake.uuid));
        assertTrue(manager.shouldBeHidden(fake.uuid));
        assertEquals(SpectateManager.SPECTATE_TELEPORT_UNEXPECTED_ACTIVE,
                manager.classifySpectateTeleport(fake.uuid, UUID.randomUUID(), 0, 0, 0));
    }

    @Test
    void rollbackFailureKeepsRecoverableSession() {
        SpectateManager manager = manager();
        FakePlayer fake = new FakePlayer(origin, GameMode.SURVIVAL);
        CompletableFuture<Boolean> enter = fake.nextTeleport();
        CompletableFuture<Boolean> rollback = fake.nextTeleport();
        manager.enable(fake.player, target);

        enter.complete(false);
        rollback.completeExceptionally(new IllegalStateException("rollback failed"));

        assertTrue(manager.isSpectating(fake.uuid));
        assertEquals(GameMode.SPECTATOR, fake.gameMode);
        assertEquals(1, fake.messages.size());
    }

    @Test
    void stopDuringStartingOrdersReturnAfterPhysicalEntry() {
        SpectateManager manager = manager();
        FakePlayer fake = new FakePlayer(origin, GameMode.SURVIVAL);
        CompletableFuture<Boolean> enter = fake.nextTeleport();
        manager.enable(fake.player, target);
        CompletableFuture<Boolean> exit = fake.nextTeleport();

        manager.disable(fake.player, true);
        assertEquals(1, fake.teleportLocations.size());
        enter.complete(true);
        assertEquals(target, fake.location);
        assertEquals(2, fake.teleportLocations.size());
        exit.complete(true);

        assertEquals(origin, fake.location);
        assertFalse(manager.isLifecycleActive(fake.uuid));
    }

    @Test
    void hungForeverEntryStopTimeoutEndsExemptionAndBlocksUnsafeNewSession() {
        SpectateManager manager = manager();
        FakePlayer fake = new FakePlayer(origin, GameMode.CREATIVE);
        fake.nextTeleport(); // never completes
        manager.enable(fake.player, target);
        CompletableFuture<Boolean> timeoutReturn = fake.nextTeleport();
        manager.disable(fake.player, true);

        timeouts.removeFirst().run();
        timeoutReturn.complete(true);

        assertTrue(manager.isLifecycleActive(fake.uuid));
        assertEquals(GameMode.CREATIVE, fake.gameMode);
        assertEquals(SpectateManager.StartResult.RECONNECT_REQUIRED, manager.enable(fake.player, target));
    }

    @Test
    void hungEntryAndHungSafetyReturnFailClosedAndQuarantineUntilDisconnect() {
        SpectateManager manager = manager();
        FakePlayer fake = new FakePlayer(origin, GameMode.CREATIVE);
        fake.nextTeleport(); // entry never completes
        manager.enable(fake.player, target);
        fake.nextTeleport(); // safety return never completes
        manager.disable(fake.player, true);

        timeouts.removeFirst().run(); // entry timeout dispatches safety return
        timeouts.removeFirst().run(); // safety return timeout fails closed

        assertTrue(manager.isLifecycleActive(fake.uuid));
        assertEquals(GameMode.CREATIVE, fake.gameMode);
        assertEquals(1, fake.kicks);
    }

    @Test
    void invalidOrNonFiniteTargetIsRejectedBeforeMutation() {
        SpectateManager manager = manager();
        FakePlayer fake = new FakePlayer(origin, GameMode.CREATIVE);
        Location invalid = new Location(loadedWorld, Double.NaN, 2, 3);

        assertEquals(SpectateManager.StartResult.INVALID_TARGET, manager.enable(fake.player, invalid));
        assertEquals(GameMode.CREATIVE, fake.gameMode);
        assertFalse(manager.isLifecycleActive(fake.uuid));
        assertTrue(fake.teleportLocations.isEmpty());
    }

    @Test
    void targetLogoutAfterDispatchRollsBackInsteadOfBecomingActive() {
        SpectateManager manager = manager();
        FakePlayer fake = new FakePlayer(origin, GameMode.SURVIVAL);
        FakePlayer spectateTarget = new FakePlayer(target, GameMode.SURVIVAL);
        CompletableFuture<Boolean> enter = fake.nextTeleport();
        CompletableFuture<Boolean> rollback = fake.nextTeleport();
        manager.enable(fake.player, spectateTarget.player, target);

        spectateTarget.online = false;
        enter.complete(true);
        rollback.complete(true);

        assertFalse(manager.isLifecycleActive(fake.uuid));
        assertEquals(origin, fake.location);
        assertEquals(GameMode.SURVIVAL, fake.gameMode);
        assertEquals(2, fake.messages.size());
    }

    @Test
    void targetTransferAfterDispatchRollsBackInsteadOfBecomingActive() {
        SpectateManager manager = manager();
        FakePlayer fake = new FakePlayer(origin, GameMode.CREATIVE);
        FakePlayer spectateTarget = new FakePlayer(target, GameMode.SURVIVAL);
        CompletableFuture<Boolean> enter = fake.nextTeleport();
        CompletableFuture<Boolean> rollback = fake.nextTeleport();
        manager.enable(fake.player, spectateTarget.player, target);

        spectateTarget.external = true;
        enter.complete(true);
        rollback.complete(true);

        assertFalse(manager.isLifecycleActive(fake.uuid));
        assertEquals(origin, fake.location);
        assertEquals(GameMode.CREATIVE, fake.gameMode);
    }

    @Test
    void disconnectInvalidatesLifecycleEvenWhenGameModeRestoreThrows() {
        SpectateManager manager = manager();
        FakePlayer fake = new FakePlayer(origin, GameMode.CREATIVE);
        fake.nextTeleport();
        manager.enable(fake.player, target);
        fake.throwOnSetGameMode = true;

        manager.onQuit(fake.player);

        assertFalse(manager.isLifecycleActive(fake.uuid));
    }

    @Test
    void authoritativeClassificationValidatesIdentityPacketAndLiveTarget() {
        SpectateManager manager = manager();
        FakePlayer fake = new FakePlayer(origin, GameMode.SURVIVAL);
        FakePlayer spectateTarget = new FakePlayer(target, GameMode.SURVIVAL);
        CompletableFuture<Boolean> enter = fake.nextTeleport();

        manager.enable(fake.player, spectateTarget.player, target);

        assertEquals(SpectateManager.SPECTATE_TELEPORT_NONE,
                manager.classifySpectateTeleport(null, spectateTarget.uuid, 40, 50, 60));
        assertEquals(SpectateManager.SPECTATE_TELEPORT_EXPECTED_START,
                manager.classifySpectateTeleport(fake.uuid, spectateTarget.uuid, 40, 50, 60));
        assertEquals(SpectateManager.SPECTATE_TELEPORT_UNEXPECTED_ACTIVE,
                manager.classifySpectateTeleport(fake.uuid, UUID.randomUUID(), 40, 50, 60));
        assertEquals(SpectateManager.SPECTATE_TELEPORT_UNEXPECTED_ACTIVE,
                manager.classifySpectateTeleport(fake.uuid, spectateTarget.uuid, 40.01, 50, 60));

        fake.location = new Location(proxyWorld(true), 40, 50, 60);
        assertEquals(SpectateManager.SPECTATE_TELEPORT_UNEXPECTED_ACTIVE,
                manager.classifySpectateTeleport(fake.uuid, spectateTarget.uuid, 40, 50, 60));
        fake.location = origin;

        spectateTarget.location = new Location(loadedWorld, 43, 50, 60);
        assertEquals(SpectateManager.SPECTATE_TELEPORT_UNEXPECTED_ACTIVE,
                manager.classifySpectateTeleport(fake.uuid, spectateTarget.uuid, 40, 50, 60));
        spectateTarget.location = new Location(loadedWorld,
                40 + SpectateManager.PACKET_DESTINATION_TOLERANCE * 2, 50, 60);
        assertEquals(SpectateManager.SPECTATE_TELEPORT_UNEXPECTED_ACTIVE,
                manager.classifySpectateTeleport(fake.uuid, spectateTarget.uuid, 40, 50, 60));

        fake.nextTeleport();
        enter.complete(true);
    }

    @Test
    void locationOnlyCompatibilityEntryIsNeverAuthoritative() {
        SpectateManager manager = manager();
        FakePlayer fake = new FakePlayer(origin, GameMode.SURVIVAL);
        fake.nextTeleport();
        manager.enable(fake.player, target);

        assertEquals(SpectateManager.SPECTATE_TELEPORT_UNEXPECTED_ACTIVE,
                manager.classifySpectateTeleport(fake.uuid, UUID.randomUUID(), 40, 50, 60));
    }

    @Test
    void targetMovementAtAnyMeaningfulDistanceAndWorldChangeRollsBackCoreCompletion() {
        for (Location movedTarget : List.of(
                new Location(loadedWorld, 43, 50, 60),
                new Location(loadedWorld, 40 + SpectateManager.PACKET_DESTINATION_TOLERANCE * 2, 50, 60),
                new Location(proxyWorld(true), 40, 50, 60))) {
            SpectateManager manager = manager();
            FakePlayer fake = new FakePlayer(origin, GameMode.CREATIVE);
            FakePlayer spectateTarget = new FakePlayer(target, GameMode.SURVIVAL);
            CompletableFuture<Boolean> enter = fake.nextTeleport();
            CompletableFuture<Boolean> rollback = fake.nextTeleport();
            manager.enable(fake.player, spectateTarget.player, target);

            spectateTarget.location = movedTarget;
            enter.complete(true);
            rollback.complete(true);

            assertFalse(manager.isLifecycleActive(fake.uuid));
            assertEquals(origin, fake.location);
            assertEquals(GameMode.CREATIVE, fake.gameMode);
        }
    }

    @Test
    void sourceOrTargetToctouInvalidityPreventsMutation() {
        SpectateManager manager = manager();
        FakePlayer fake = new FakePlayer(origin, GameMode.CREATIVE);
        FakePlayer spectateTarget = new FakePlayer(target, GameMode.SURVIVAL);
        fake.external = true;

        assertEquals(SpectateManager.StartResult.INVALID_SENDER,
                manager.enable(fake.player, spectateTarget.player, target));
        assertEquals(GameMode.CREATIVE, fake.gameMode);
        assertTrue(fake.teleportLocations.isEmpty());

        fake.external = false;
        spectateTarget.online = false;
        assertEquals(SpectateManager.StartResult.INVALID_TARGET,
                manager.enable(fake.player, spectateTarget.player, target));
        assertEquals(GameMode.CREATIVE, fake.gameMode);
        assertTrue(fake.teleportLocations.isEmpty());
    }

    @Test
    void permissionRevokedAfterCommandPreflightIsRejectedByManager() {
        SpectateManager manager = manager();
        FakePlayer fake = new FakePlayer(origin, GameMode.CREATIVE);
        FakePlayer spectateTarget = new FakePlayer(target, GameMode.SURVIVAL);
        assertTrue(fake.player.hasPermission("grim.spectate")); // command preflight
        fake.spectatePermission = false;

        assertEquals(SpectateManager.StartResult.INVALID_SENDER,
                manager.enable(fake.player, spectateTarget.player, target));
        assertEquals(GameMode.CREATIVE, fake.gameMode);
        assertTrue(fake.teleportLocations.isEmpty());
    }

    @Test
    void externalGameModeChangeDuringStartingPreservesRequestedModeAfterSuccess() {
        SpectateManager manager = manager();
        FakePlayer fake = new FakePlayer(origin, GameMode.SURVIVAL);
        CompletableFuture<Boolean> enter = fake.nextTeleport();
        CompletableFuture<Boolean> rollback = fake.nextTeleport();
        manager.enable(fake.player, target);
        SpectateManager.SpectateReturnState initial = manager.getSpectateReturnState(fake.uuid);

        manager.handlePlayerStopSpectating(fake.uuid, fake.player, GameMode.ADVENTURE);
        SpectateManager.SpectateReturnState updated = manager.getSpectateReturnState(fake.uuid);
        enter.complete(true);
        rollback.complete(true);

        assertEquals(origin, initial.location());
        assertEquals(GameMode.SURVIVAL, initial.gameMode());
        assertEquals(GameMode.ADVENTURE, updated.gameMode());
        assertTrue(updated.version() > initial.version());
        assertEquals(GameMode.ADVENTURE, fake.gameMode);
        assertFalse(manager.isLifecycleActive(fake.uuid));
    }

    @Test
    void externalGameModeChangeDuringHungStartingPreservesRequestedModeFailClosed() {
        SpectateManager manager = manager();
        FakePlayer fake = new FakePlayer(origin, GameMode.SURVIVAL);
        fake.nextTeleport();
        CompletableFuture<Boolean> safetyReturn = fake.nextTeleport();
        manager.enable(fake.player, target);
        manager.handlePlayerStopSpectating(fake.uuid, fake.player, GameMode.ADVENTURE);

        timeouts.removeFirst().run();
        safetyReturn.complete(true);

        assertEquals(GameMode.ADVENTURE, fake.gameMode);
        assertTrue(manager.isLifecycleActive(fake.uuid));
        assertEquals(1, fake.kicks);
    }

    @Test
    void stopHereReportsRestoreFailureUntilGameModeActuallyRestores() {
        SpectateManager manager = manager();
        FakePlayer fake = new FakePlayer(origin, GameMode.CREATIVE);
        CompletableFuture<Boolean> enter = fake.nextTeleport();
        manager.enable(fake.player, target);
        enter.complete(true);
        fake.throwOnSetGameMode = true;

        assertEquals(SpectateManager.StopResult.RESTORE_FAILED, manager.disable(fake.player, false));
        assertTrue(manager.isLifecycleActive(fake.uuid));
        assertEquals(1, fake.messages.size());

        fake.throwOnSetGameMode = false;
        assertEquals(SpectateManager.StopResult.STOPPED_HERE, manager.disable(fake.player, false));
        assertFalse(manager.isLifecycleActive(fake.uuid));
    }

    @Test
    void retiredStartingSessionIsQuarantinedUntilQuitCanRestoreIt() {
        AtomicReference<Runnable> retired = new AtomicReference<>();
        SpectateManager manager = new SpectateManager((player, task, retiredTask) -> {
            retired.set(retiredTask);
            return true;
        });
        FakePlayer fake = new FakePlayer(origin, GameMode.CREATIVE);
        fake.nextTeleport();
        manager.enable(fake.player, target);
        int teleports = fake.teleportLocations.size();
        int gameModeChanges = fake.gameModeChanges;

        retired.get().run();

        assertTrue(manager.isLifecycleActive(fake.uuid));
        assertTrue(manager.shouldBeHidden(fake.uuid));
        assertEquals(SpectateManager.SPECTATE_TELEPORT_UNEXPECTED_ACTIVE,
                manager.classifySpectateTeleport(fake.uuid, UUID.randomUUID(), 40, 50, 60));
        assertEquals(teleports, fake.teleportLocations.size());
        assertEquals(gameModeChanges, fake.gameModeChanges);
        assertEquals(0, fake.kicks);

        manager.onQuit(fake.player);

        assertFalse(manager.isLifecycleActive(fake.uuid));
        assertEquals(GameMode.CREATIVE, fake.gameMode);
    }

    @Test
    void retiredActiveAndReturningSessionsRestoreOnlyDuringQuit() {
        for (boolean returning : List.of(false, true)) {
            List<Runnable> retiredCallbacks = new ArrayList<>();
            SpectateManager manager = new SpectateManager((player, task, retired) -> {
                retiredCallbacks.add(retired);
                return true;
            });
            FakePlayer fake = new FakePlayer(origin, GameMode.ADVENTURE);
            CompletableFuture<Boolean> entry = fake.nextTeleport();
            manager.enable(fake.player, target);
            entry.complete(true);

            CompletableFuture<Boolean> returnTeleport = null;
            int retiredIndex = 0;
            if (returning) {
                returnTeleport = fake.nextTeleport();
                manager.disable(fake.player, true);
                retiredIndex = 1;
            }
            int gameModeChanges = fake.gameModeChanges;

            retiredCallbacks.get(retiredIndex).run();

            assertTrue(manager.isLifecycleActive(fake.uuid));
            assertTrue(manager.shouldBeHidden(fake.uuid));
            assertEquals(SpectateManager.SPECTATE_TELEPORT_UNEXPECTED_ACTIVE,
                    manager.classifySpectateTeleport(fake.uuid, UUID.randomUUID(), 1, 2, 3));
            assertEquals(gameModeChanges, fake.gameModeChanges);
            if (returnTeleport != null) returnTeleport.complete(true);
            assertEquals(gameModeChanges, fake.gameModeChanges);

            manager.onQuit(fake.player);

            assertFalse(manager.isLifecycleActive(fake.uuid));
            assertEquals(GameMode.ADVENTURE, fake.gameMode);
        }
    }

    @Test
    void retiredTimedOutSessionInvalidatesLateEntryWithoutLosingQuitRestore() {
        List<Runnable> timeoutCallbacks = new ArrayList<>();
        List<Runnable> retiredCallbacks = new ArrayList<>();
        SpectateManager manager = new SpectateManager((player, task, retired) -> {
            timeoutCallbacks.add(task);
            retiredCallbacks.add(retired);
            return true;
        });
        FakePlayer fake = new FakePlayer(origin, GameMode.CREATIVE);
        CompletableFuture<Boolean> originalEntry = fake.nextTeleport();
        CompletableFuture<Boolean> safetyReturn = fake.nextTeleport();
        manager.enable(fake.player, target);

        timeoutCallbacks.get(0).run();
        retiredCallbacks.get(1).run();
        int dispatchedTeleports = fake.teleportLocations.size();

        assertTrue(manager.isLifecycleActive(fake.uuid));
        assertTrue(manager.shouldBeHidden(fake.uuid));
        assertEquals(SpectateManager.SPECTATE_TELEPORT_UNEXPECTED_ACTIVE,
                manager.classifySpectateTeleport(fake.uuid, UUID.randomUUID(), 1, 2, 3));

        originalEntry.complete(true);
        safetyReturn.complete(true);

        assertEquals(dispatchedTeleports, fake.teleportLocations.size());
        assertTrue(manager.isLifecycleActive(fake.uuid));
        manager.onQuit(fake.player);
        assertFalse(manager.isLifecycleActive(fake.uuid));
        assertEquals(GameMode.CREATIVE, fake.gameMode);
    }

    @Test
    void staleRetiredCallbackCannotQuarantineReplacementSession() {
        List<Runnable> retiredCallbacks = new ArrayList<>();
        SpectateManager manager = new SpectateManager((player, task, retired) -> {
            retiredCallbacks.add(retired);
            return true;
        });
        FakePlayer fake = new FakePlayer(origin, GameMode.CREATIVE);
        CompletableFuture<Boolean> firstEntry = fake.nextTeleport();
        manager.enable(fake.player, target);
        firstEntry.complete(true);
        CompletableFuture<Boolean> firstReturn = fake.nextTeleport();
        manager.disable(fake.player, true);
        firstReturn.complete(true);

        CompletableFuture<Boolean> replacementEntry = fake.nextTeleport();
        manager.enable(fake.player, target);
        retiredCallbacks.get(0).run();

        assertTrue(manager.isLifecycleActive(fake.uuid));
        assertEquals(GameMode.SPECTATOR, fake.gameMode);
        assertEquals(SpectateManager.SPECTATE_TELEPORT_UNEXPECTED_ACTIVE,
                manager.classifySpectateTeleport(fake.uuid, UUID.randomUUID(), 40, 50, 60));
        replacementEntry.complete(true);
    }

    @Test
    void rejectedEntitySchedulerFailsClosedIntoTombstoneLifecycle() {
        SpectateManager manager = new SpectateManager((player, task, retired) -> false);
        FakePlayer fake = new FakePlayer(origin, GameMode.CREATIVE);
        fake.nextTeleport();
        fake.nextTeleport();

        manager.enable(fake.player, target);

        assertTrue(manager.isLifecycleActive(fake.uuid));
        assertEquals(1, fake.kicks);
    }

    @Test
    void lateCorrectionSuccessFinishesSessionBeforeHungSafetyReturnTimeout() {
        SpectateManager manager = manager();
        FakePlayer fake = new FakePlayer(origin, GameMode.CREATIVE);
        CompletableFuture<Boolean> originalEntry = fake.nextTeleport();
        fake.nextTeleport(); // safety return remains hung
        CompletableFuture<Boolean> correction = fake.nextTeleport();
        manager.enable(fake.player, target);

        timeouts.removeFirst().run();
        originalEntry.complete(true);
        correction.complete(true);
        timeouts.removeFirst().run(); // stale safety-return timeout

        assertFalse(manager.isLifecycleActive(fake.uuid));
        assertEquals(GameMode.CREATIVE, fake.gameMode);
        assertEquals(origin, fake.location);
        fake.online = true;
        fake.nextTeleport();
        assertEquals(SpectateManager.StartResult.STARTED, manager.enable(fake.player, target));
    }

    @Test
    void hungLateCorrectionHasTerminalTimeout() {
        SpectateManager manager = manager();
        FakePlayer fake = new FakePlayer(origin, GameMode.CREATIVE);
        CompletableFuture<Boolean> originalEntry = fake.nextTeleport();
        fake.nextTeleport(); // safety return hangs
        fake.nextTeleport(); // correction hangs
        manager.enable(fake.player, target);

        timeouts.removeFirst().run();
        originalEntry.complete(true);
        assertEquals(SpectateManager.SPECTATE_TELEPORT_RETURNING,
                manager.classifySpectateTeleport(fake.uuid, UUID.randomUUID(), 1, 2, 3));
        timeouts.removeFirst(); // safety-return timeout; exercise correction timeout specifically
        timeouts.removeFirst().run();

        assertTrue(manager.isLifecycleActive(fake.uuid));
        assertEquals(SpectateManager.SPECTATE_TELEPORT_UNEXPECTED_ACTIVE,
                manager.classifySpectateTeleport(fake.uuid, UUID.randomUUID(), 1, 2, 3));
        assertEquals(1, fake.kicks);
    }

    @Test
    void failedTerminalKickLeavesTombstoneUntilDisconnect() {
        SpectateManager manager = manager();
        FakePlayer fake = new FakePlayer(origin, GameMode.CREATIVE);
        fake.nextTeleport(); // entry hangs
        CompletableFuture<Boolean> safetyReturn = fake.nextTeleport();
        manager.enable(fake.player, target);
        fake.throwOnKick = true;

        timeouts.removeFirst().run();
        safetyReturn.complete(true);

        assertTrue(manager.isLifecycleActive(fake.uuid));
        assertEquals(SpectateManager.StartResult.RECONNECT_REQUIRED, manager.enable(fake.player, target));
        assertEquals(1, fake.messages.size());
        assertTrue(manager.shouldBeHidden(fake.uuid));
        assertEquals(SpectateManager.SPECTATE_TELEPORT_UNEXPECTED_ACTIVE,
                manager.classifySpectateTeleport(fake.uuid, UUID.randomUUID(), 40, 50, 60));

        manager.onQuit(fake.uuid);
        assertFalse(manager.isLifecycleActive(fake.uuid));
        assertFalse(manager.shouldBeHidden(fake.uuid));
        fake.nextTeleport();
        assertEquals(SpectateManager.StartResult.STARTED, manager.enable(fake.player, target));
    }

    @Test
    void failedTerminalKickQuarantinesLateEntryUntilCorrectionAndRestoreComplete() {
        SpectateManager manager = manager();
        FakePlayer fake = new FakePlayer(origin, GameMode.CREATIVE);
        CompletableFuture<Boolean> originalEntry = fake.nextTeleport();
        fake.nextTeleport(); // safety return remains hung
        CompletableFuture<Boolean> correction = fake.nextTeleport();
        manager.enable(fake.player, target);
        fake.throwOnKick = true;

        timeouts.removeFirst().run(); // entry timeout dispatches safety return
        assertEquals(SpectateManager.SPECTATE_TELEPORT_RETURNING,
                manager.classifySpectateTeleport(fake.uuid, UUID.randomUUID(), 1, 2, 3));
        timeouts.removeFirst().run(); // safety return timeout attempts terminal kick

        assertTrue(manager.isLifecycleActive(fake.uuid));
        assertTrue(manager.shouldBeHidden(fake.uuid));
        assertEquals(SpectateManager.SPECTATE_TELEPORT_UNEXPECTED_ACTIVE,
                manager.classifySpectateTeleport(fake.uuid, UUID.randomUUID(), 40, 50, 60));
        assertEquals(SpectateManager.StartResult.RECONNECT_REQUIRED, manager.enable(fake.player, target));
        assertEquals(GameMode.CREATIVE, fake.gameMode);

        originalEntry.complete(true);

        assertEquals(target, fake.location);
        assertTrue(manager.isLifecycleActive(fake.uuid));
        assertTrue(manager.shouldBeHidden(fake.uuid));
        assertEquals(SpectateManager.SPECTATE_TELEPORT_RETURNING,
                manager.classifySpectateTeleport(fake.uuid, UUID.randomUUID(), 1, 2, 3));
        assertEquals(SpectateManager.SPECTATE_TELEPORT_UNEXPECTED_ACTIVE,
                manager.classifySpectateTeleport(fake.uuid, UUID.randomUUID(), 40, 50, 60));
        assertEquals(SpectateManager.SPECTATE_TELEPORT_UNEXPECTED_ACTIVE,
                manager.classifySpectateTeleport(fake.uuid, UUID.randomUUID(), 4, 5, 6));

        AtomicReference<Boolean> hiddenDuringCorrectionRestore = new AtomicReference<>();
        AtomicReference<Boolean> lifecycleDuringCorrectionRestore = new AtomicReference<>();
        AtomicReference<Integer> classificationDuringCorrectionRestore = new AtomicReference<>();
        fake.onSetGameMode = () -> {
            hiddenDuringCorrectionRestore.set(manager.shouldBeHidden(fake.uuid));
            lifecycleDuringCorrectionRestore.set(manager.isLifecycleActive(fake.uuid));
            classificationDuringCorrectionRestore.set(manager.classifySpectateTeleport(
                    fake.uuid, UUID.randomUUID(), 1, 2, 3));
        };
        correction.complete(true);

        assertEquals(origin, fake.location);
        assertEquals(GameMode.CREATIVE, fake.gameMode);
        assertFalse(hiddenDuringCorrectionRestore.get());
        assertTrue(lifecycleDuringCorrectionRestore.get());
        assertEquals(SpectateManager.SPECTATE_TELEPORT_RETURNING,
                classificationDuringCorrectionRestore.get());
        assertFalse(manager.isLifecycleActive(fake.uuid));
        assertFalse(manager.shouldBeHidden(fake.uuid));
        assertEquals(SpectateManager.SPECTATE_TELEPORT_NONE,
                manager.classifySpectateTeleport(fake.uuid, UUID.randomUUID(), 40, 50, 60));
    }

    @Test
    void unavailableOriginOnlyReportsStoppedHereAfterRestore() {
        boolean[] loaded = {true};
        PlatformWorld world = (PlatformWorld) Proxy.newProxyInstance(
                PlatformWorld.class.getClassLoader(), new Class<?>[]{PlatformWorld.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isLoaded" -> loaded[0];
                    case "getName" -> "dynamic";
                    default -> defaultValue(method.getReturnType());
                });
        Location dynamicOrigin = new Location(world, 1, 2, 3);
        SpectateManager manager = manager();
        FakePlayer fake = new FakePlayer(dynamicOrigin, GameMode.ADVENTURE);
        CompletableFuture<Boolean> enter = fake.nextTeleport();
        manager.enable(fake.player, new Location(world, 40, 50, 60));
        enter.complete(true);
        loaded[0] = false;

        assertEquals(SpectateManager.StopResult.STOPPED_HERE, manager.disable(fake.player, true));
        assertEquals(GameMode.ADVENTURE, fake.gameMode);
        assertFalse(manager.isLifecycleActive(fake.uuid));
        assertEquals(1, fake.messages.size());
    }

    private static PlatformWorld proxyWorld(boolean loaded) {
        return (PlatformWorld) Proxy.newProxyInstance(
                PlatformWorld.class.getClassLoader(),
                new Class<?>[]{PlatformWorld.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isLoaded" -> loaded;
                    case "getName" -> "test";
                    case "toString" -> "TestWorld";
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    @SuppressWarnings("unchecked")
    private static Set<UUID> hiddenPlayers(SpectateManager manager) throws ReflectiveOperationException {
        Field field = SpectateManager.class.getDeclaredField("hiddenPlayers");
        field.setAccessible(true);
        return (Set<UUID>) field.get(manager);
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Object> hiddenPlayerOwners(SpectateManager manager) throws ReflectiveOperationException {
        Field field = SpectateManager.class.getDeclaredField("hiddenPlayerOwners");
        field.setAccessible(true);
        return (Map<UUID, Object>) field.get(manager);
    }

    private static void setHiddenPlayers(SpectateManager manager, Set<UUID> hiddenPlayers)
            throws ReflectiveOperationException {
        Field field = SpectateManager.class.getDeclaredField("hiddenPlayers");
        field.setAccessible(true);
        field.set(manager, hiddenPlayers);
    }

    private static Thread.State awaitBlockedOrTerminated(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        Thread.State state;
        do {
            state = thread.getState();
            if (state == Thread.State.BLOCKED || state == Thread.State.TERMINATED) return state;
            Thread.sleep(1);
        } while (System.nanoTime() < deadline);
        return state;
    }

    private static final class BlockingSet<E> extends AbstractSet<E> {
        private final Set<E> delegate;
        private final E blockedElement;
        private final AtomicBoolean blockNextAdd = new AtomicBoolean();
        private final CountDownLatch blockedAdd = new CountDownLatch(1);
        private final CountDownLatch releaseAdd = new CountDownLatch(1);

        private BlockingSet(Set<E> delegate, E blockedElement) {
            this.delegate = delegate;
            this.blockedElement = blockedElement;
        }

        private void blockNextAdd() {
            blockNextAdd.set(true);
        }

        private void awaitBlockedAdd() throws InterruptedException {
            assertTrue(blockedAdd.await(5, TimeUnit.SECONDS));
        }

        private void releaseAdd() {
            releaseAdd.countDown();
        }

        @Override
        public boolean add(E element) {
            boolean added = delegate.add(element);
            if (blockNextAdd.compareAndSet(true, false) && blockedElement.equals(element)) {
                blockedAdd.countDown();
                try {
                    if (!releaseAdd.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("Timed out waiting to release hidden-player publication");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Interrupted while publishing hidden-player state", exception);
                }
            }
            return added;
        }

        @Override
        public Iterator<E> iterator() {
            return delegate.iterator();
        }

        @Override
        public int size() {
            return delegate.size();
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

    private static final class FakePlayer {
        private final UUID uuid;
        private final Deque<CompletableFuture<Boolean>> teleportFutures = new ArrayDeque<>();
        private final List<Location> teleportLocations = new ArrayList<>();
        private final List<Component> messages = new ArrayList<>();
        private Location location;
        private GameMode gameMode;
        private boolean online = true;
        private boolean external;
        private boolean spectatePermission = true;
        private boolean throwOnSetGameMode;
        private boolean throwOnKick;
        private int gameModeChanges;
        private int kicks;
        private final Object nativeIdentity = new Object();
        private Runnable onSetGameMode = () -> {
        };
        private final PlatformPlayer player;

        private FakePlayer(Location location, GameMode gameMode) {
            this(UUID.randomUUID(), location, gameMode);
        }

        private FakePlayer(UUID uuid, Location location, GameMode gameMode) {
            this.uuid = uuid;
            this.location = location;
            this.gameMode = gameMode;
            this.player = (PlatformPlayer) Proxy.newProxyInstance(
                    PlatformPlayer.class.getClassLoader(),
                    new Class<?>[]{PlatformPlayer.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getUniqueId" -> uuid;
                        case "getLocation" -> this.location;
                        case "getGameMode" -> this.gameMode;
                        case "getNative" -> nativeIdentity;
                        case "setGameMode" -> {
                            if (throwOnSetGameMode) throw new IllegalStateException("setGameMode failed");
                            gameModeChanges++;
                            this.gameMode = (GameMode) args[0];
                            onSetGameMode.run();
                            yield null;
                        }
                        case "isOnline" -> online;
                        case "isExternalPlayer" -> external;
                        case "hasPermission" -> spectatePermission;
                        case "kickPlayer" -> {
                            if (throwOnKick) throw new IllegalStateException("kick failed");
                            kicks++;
                            online = false;
                            yield null;
                        }
                        case "teleportAsync" -> {
                            Location destination = (Location) args[0];
                            teleportLocations.add(destination);
                            CompletableFuture<Boolean> future = teleportFutures.removeFirst();
                            future.whenComplete((success, throwable) -> {
                                if (throwable == null && Boolean.TRUE.equals(success)) this.location = destination;
                            });
                            yield future;
                        }
                        case "sendMessage" -> {
                            if (args[0] instanceof Component component) messages.add(component);
                            yield null;
                        }
                        case "toString" -> "FakePlayer[" + uuid + "]";
                        case "hashCode" -> uuid.hashCode();
                        case "equals" -> proxy == args[0];
                        default -> defaultValue(method.getReturnType());
                    }
            );
        }

        private CompletableFuture<Boolean> nextTeleport() {
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            teleportFutures.addLast(future);
            return future;
        }
    }
}
