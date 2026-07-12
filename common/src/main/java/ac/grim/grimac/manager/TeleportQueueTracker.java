package ac.grim.grimac.manager;

import ac.grim.grimac.utils.data.TeleportData;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Serializes compound queue rewrites and snapshots for Grim's internal readers. */
final class TeleportQueueTracker {
    private final ConcurrentLinkedQueue<TeleportData> queue = new ConcurrentLinkedQueue<>();

    synchronized void add(TeleportData data) {
        queue.add(data);
    }

    synchronized TeleportData peek() {
        return queue.peek();
    }

    synchronized TeleportData poll() {
        return queue.poll();
    }

    synchronized boolean isEmpty() {
        return queue.isEmpty();
    }

    synchronized List<TeleportData> snapshot() {
        return List.copyOf(queue);
    }

    synchronized void replaceAll(Collection<TeleportData> replacement) {
        queue.clear();
        queue.addAll(replacement);
    }

    ConcurrentLinkedQueue<TeleportData> compatibilityView() {
        return queue;
    }
}
