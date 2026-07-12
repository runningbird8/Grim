package ac.grim.grimac.manager;

import ac.grim.grimac.utils.data.TeleportData;
import com.github.retrooper.packetevents.protocol.teleport.RelativeFlag;
import com.github.retrooper.packetevents.util.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeleportQueueTrackerTest {
    @Test
    void concurrentSnapshotsNeverObserveClearThenAddRewriteIntermediate() {
        TeleportQueueTracker tracker = new TeleportQueueTracker();
        TeleportData first = teleport(1);
        TeleportData second = teleport(2);
        TeleportData replacementFirst = teleport(3);
        TeleportData replacementSecond = teleport(4);
        List<TeleportData> original = List.of(first, second);
        List<TeleportData> replacement = List.of(replacementFirst, replacementSecond);
        tracker.replaceAll(original);

        CompletableFuture<Void> writer = CompletableFuture.runAsync(() -> {
            for (int i = 0; i < 10_000; i++) {
                tracker.replaceAll((i & 1) == 0 ? replacement : original);
            }
        });

        for (int i = 0; i < 10_000; i++) {
            List<TeleportData> snapshot = tracker.snapshot();
            assertEquals(2, snapshot.size());
            assertTrue(snapshot.equals(original) || snapshot.equals(replacement));
        }
        writer.join();
    }

    private static TeleportData teleport(int id) {
        return new TeleportData(new Vector3d(id, id, id), 0, 0, null,
                RelativeFlag.NONE, id, id);
    }
}
