package ac.grim.grimac.manager;

import ac.grim.grimac.utils.data.SetBackData;
import ac.grim.grimac.utils.data.TeleportData;
import ac.grim.grimac.utils.math.Vector3dm;
import com.github.retrooper.packetevents.util.Vector3d;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

final class SetbackTeleportRewrite {
    private SetbackTeleportRewrite() {
    }

    static Result rewrite(Collection<TeleportData> pending, @Nullable SetBackData required,
                          @Nullable Vector3d lastKnownPosition, @Nullable Vector3dm lastKnownVector,
                          int teleportId, Vector3d rawPosition) {
        boolean matched = false;
        TeleportData originalRaw = null;
        List<TeleportData> rewrittenQueue = new ArrayList<>(pending.size());
        for (TeleportData queued : pending) {
            if (queued.getTeleportId() == teleportId) {
                if (originalRaw == null) originalRaw = queued;
                rewrittenQueue.add(copyTeleportAt(queued, rawPosition));
                matched = true;
            } else {
                rewrittenQueue.add(queued);
            }
        }

        SetBackData rewrittenRequired = required;
        boolean rewriteLastKnown = false;
        Vector3d rewrittenLastKnown = lastKnownPosition;
        if (required != null && required.getTeleportData().getTeleportId() == teleportId) {
            TeleportData relativeReference = originalRaw == null ? required.getTeleportData() : originalRaw;
            Vector3d rewrittenAbsolute = rewriteAbsolute(
                    required.getTeleportData().getLocation(), relativeReference, rawPosition);
            rewrittenRequired = new SetBackData(
                    copyTeleportAt(required.getTeleportData(), rewrittenAbsolute),
                    required.getXRot(),
                    required.getYRot(),
                    required.getVelocity(),
                    required.isVehicle(),
                    required.isPlugin()
            );
            rewrittenRequired.setComplete(required.isComplete());
            rewrittenRequired.setTicksComplete(required.getTicksComplete());
            rewriteLastKnown = true;
            if (lastKnownPosition != null) {
                rewrittenLastKnown = rewriteAbsolute(lastKnownPosition, relativeReference, rawPosition);
            }
            matched = true;
        }

        return new Result(rewrittenQueue, rewrittenRequired, rewrittenLastKnown, lastKnownVector,
                rewriteLastKnown, matched);
    }

    private static Vector3d rewriteAbsolute(Vector3d oldAbsolute, TeleportData oldRaw, Vector3d newRaw) {
        Vector3d oldRawPosition = oldRaw.getLocation();
        return new Vector3d(
                oldRaw.isRelativeX() ? oldAbsolute.getX() + newRaw.getX() - oldRawPosition.getX() : newRaw.getX(),
                oldRaw.isRelativeY() ? oldAbsolute.getY() + newRaw.getY() - oldRawPosition.getY() : newRaw.getY(),
                oldRaw.isRelativeZ() ? oldAbsolute.getZ() + newRaw.getZ() - oldRawPosition.getZ() : newRaw.getZ()
        );
    }

    private static TeleportData copyTeleportAt(TeleportData original, Vector3d position) {
        return new TeleportData(
                position,
                original.getYaw(),
                original.getPitch(),
                original.getVelocity(),
                original.getFlags(),
                original.getTransaction(),
                original.getTeleportId()
        );
    }

    record Result(List<TeleportData> pending, @Nullable SetBackData required,
                  @Nullable Vector3d lastKnownPosition, @Nullable Vector3dm lastKnownVector,
                  boolean rewriteLastKnown, boolean matched) {
    }
}
