package ac.grim.grimac.platform.fabric.utils.thread;

import ac.grim.grimac.GrimAPI;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class FabricFutureUtil {
    public static <U> CompletableFuture<U> supplySync(Supplier<U> entityTeleportSupplier) {
        return supplyWithScheduler(entityTeleportSupplier, task ->
                GrimAPI.INSTANCE.getScheduler().getGlobalRegionScheduler()
                        .run(GrimAPI.INSTANCE.getGrimPlugin(), task));
    }

    static <U> CompletableFuture<U> supplyWithScheduler(Supplier<U> supplier, Consumer<Runnable> scheduler) {
        CompletableFuture<U> ret = new CompletableFuture<>();
        try {
            scheduler.accept(() -> {
                try {
                    ret.complete(supplier.get());
                } catch (Throwable throwable) {
                    ret.completeExceptionally(throwable);
                }
            });
        } catch (Throwable throwable) {
            ret.completeExceptionally(throwable);
        }
        return ret;
    }
}
