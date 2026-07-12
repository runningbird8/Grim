package ac.grim.grimac.manager;

import ac.grim.grimac.utils.data.SetBackData;
import ac.grim.grimac.utils.data.TeleportData;
import ac.grim.grimac.utils.math.Vector3dm;
import com.github.retrooper.packetevents.protocol.teleport.RelativeFlag;
import com.github.retrooper.packetevents.util.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SetbackTeleportRewriteTest {
    @Test
    void idZeroRewritesQueueRequiredAndLastKnownWhilePreservingMetadata() {
        Vector3d velocity = new Vector3d(0.1, 0.2, 0.3);
        RelativeFlag flags = RelativeFlag.X.or(RelativeFlag.YAW).or(RelativeFlag.DELTA_Z);
        TeleportData matching = new TeleportData(new Vector3d(1, 2, 3), 14, 27, velocity, flags, 91, 0);
        TeleportData other = new TeleportData(new Vector3d(4, 5, 6), 1, 2, null, RelativeFlag.NONE, 92, 8);
        Vector3dm setbackVelocity = new Vector3dm(0.4, 0.5, 0.6);
        SetBackData required = new SetBackData(matching, 31, 42, setbackVelocity, true, true);
        required.setComplete(true);
        required.setTicksComplete(5);
        Vector3dm lastKnownVector = new Vector3dm(0.7, 0.8, 0.9);

        SetbackTeleportRewrite.Result result = SetbackTeleportRewrite.rewrite(
                List.of(matching, other), required, matching.getLocation(), lastKnownVector,
                0, new Vector3d(100, 200, 300));

        assertTrue(result.matched());
        assertTrue(result.rewriteLastKnown());
        assertSame(lastKnownVector, result.lastKnownVector());
        assertEquals(new Vector3d(100, 200, 300), result.pending().get(0).getLocation());
        assertSame(other, result.pending().get(1));

        TeleportData rewritten = result.required().getTeleportData();
        assertEquals(new Vector3d(100, 200, 300), rewritten.getLocation());
        assertEquals(14, rewritten.getYaw());
        assertEquals(27, rewritten.getPitch());
        assertSame(velocity, rewritten.getVelocity());
        assertSame(flags, rewritten.getFlags());
        assertEquals(91, rewritten.getTransaction());
        assertEquals(0, rewritten.getTeleportId());
        assertSame(setbackVelocity, result.required().getVelocity());
        assertTrue(result.required().isVehicle());
        assertTrue(result.required().isPlugin());
        assertTrue(result.required().isComplete());
        assertEquals(5, result.required().getTicksComplete());
    }

    @Test
    void missingIdLeavesAllTrackingUntouched() {
        TeleportData data = new TeleportData(new Vector3d(1, 2, 3), 0, 0, null, RelativeFlag.NONE, 4, 5);
        SetBackData required = new SetBackData(data, 0, 0, null, false, false);

        SetbackTeleportRewrite.Result result = SetbackTeleportRewrite.rewrite(
                List.of(data), required, data.getLocation(), new Vector3dm(),
                6, new Vector3d(7, 8, 9));

        assertFalse(result.matched());
        assertFalse(result.rewriteLastKnown());
        assertSame(data, result.pending().get(0));
        assertSame(required, result.required());
    }

    @Test
    void mixedRelativeAxesRewriteRawQueueAndAbsoluteSafetyPositions() {
        RelativeFlag flags = RelativeFlag.X.or(RelativeFlag.Z).or(RelativeFlag.YAW);
        TeleportData raw = new TeleportData(
                new Vector3d(1, 20, -3), 4, 5, null, flags, 10, 0);
        TeleportData absoluteRequired = new TeleportData(
                new Vector3d(101, 20, 197), 0, 0, null,
                RelativeFlag.YAW.or(RelativeFlag.PITCH), 10, 0);
        SetBackData required = new SetBackData(
                absoluteRequired, 6, 7, new Vector3dm(1, 2, 3), false, true);
        Vector3d lastKnown = new Vector3d(101, 20, 197);

        SetbackTeleportRewrite.Result result = SetbackTeleportRewrite.rewrite(
                List.of(raw), required, lastKnown, new Vector3dm(4, 5, 6),
                0, new Vector3d(6, 30, -10));

        // Public packet semantics remain raw: relative X/Z are not converted.
        assertEquals(new Vector3d(6, 30, -10), result.pending().get(0).getLocation());
        // Internal absolute safety state applies deltas to relative axes and
        // lands the absolute Y axis exactly at the replacement raw value.
        assertEquals(new Vector3d(106, 30, 190), result.required().getTeleportData().getLocation());
        assertEquals(new Vector3d(106, 30, 190), result.lastKnownPosition());
        assertEquals(6, result.required().getXRot());
        assertEquals(7, result.required().getYRot());
        assertTrue(result.required().isPlugin());
    }
}
