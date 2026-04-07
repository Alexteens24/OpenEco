package dev.alexisbinh.openeco.proxy;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Tracks pending flush acknowledgements from backend servers.
 *
 * <p>When the proxy asks a backend to flush a player's account it registers a
 * pending future here. The backend replies with {@code flushed <uuid>} over the
 * plugin channel; {@link #acknowledge(UUID)} completes that future so the caller
 * can chain the next step (typically a {@code refresh} to the destination server).
 *
 * <p>Futures automatically resolve after {@value #TIMEOUT_MS} ms so a slow or
 * unresponsive backend never stalls a server switch indefinitely.
 */
public class FlushAckTracker {

    static final long TIMEOUT_MS = 2_000;

    private final ConcurrentHashMap<UUID, CompletableFuture<Void>> pending = new ConcurrentHashMap<>();

    /**
     * Registers a pending flush for {@code uuid} and returns a future that
     * completes normally when either the ack arrives or the timeout elapses.
     */
    public CompletableFuture<Void> register(UUID uuid) {
        CompletableFuture<Void> inner = new CompletableFuture<>();
        pending.put(uuid, inner);
        return inner
                .orTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .exceptionally(FlushAckTracker::silenceTimeout)   // timeout → complete normally
                .whenComplete((v, ex) -> pending.remove(uuid, inner));
    }

    /** Called when a {@code flushed <uuid>} ack is received from a backend server. */
    public void acknowledge(UUID uuid) {
        CompletableFuture<Void> future = pending.remove(uuid);
        if (future != null) {
            future.complete(null);
        }
    }

    /** Number of in-flight flush acks currently being tracked. */
    public int pendingCount() {
        return pending.size();
    }

    private static Void silenceTimeout(Throwable ex) {
        if (ex instanceof TimeoutException) return null; // expected — just proceed
        throw ex instanceof RuntimeException re ? re : new RuntimeException(ex);
    }
}
