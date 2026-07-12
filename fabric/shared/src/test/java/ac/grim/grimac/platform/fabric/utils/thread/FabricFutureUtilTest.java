package ac.grim.grimac.platform.fabric.utils.thread;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FabricFutureUtilTest {
    @Test
    void supplierFailureCompletesFutureExceptionally() {
        var future = FabricFutureUtil.supplyWithScheduler(
                () -> { throw new IllegalStateException("teleport failed"); }, Runnable::run);

        CompletionException failure = assertThrows(CompletionException.class, future::join);
        assertEquals("teleport failed", failure.getCause().getMessage());
    }

    @Test
    void schedulerRejectionCompletesFutureExceptionally() {
        var future = FabricFutureUtil.supplyWithScheduler(() -> true,
                task -> { throw new IllegalStateException("scheduler rejected"); });

        CompletionException failure = assertThrows(CompletionException.class, future::join);
        assertEquals("scheduler rejected", failure.getCause().getMessage());
    }
}
