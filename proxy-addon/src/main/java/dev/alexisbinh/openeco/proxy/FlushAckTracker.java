/*
 * Copyright 2026 alexisbinh
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.alexisbinh.openeco.proxy;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks pending flush acknowledgements from backend servers.
 *
 * <p>When the proxy asks a backend to flush a player's account it registers a
 * pending future here. The backend replies with {@code flushed <uuid> [token]} on success or
 * {@code flushfailed <uuid> [token]} when the balance did not reach storage;
 * {@link #acknowledge(UUID, long)} and {@link #reportFailure(UUID, long)} complete that future
 * so the caller can allow or deny the server switch.
 *
 * <p>Each registration carries a monotonic token that is echoed by the backend. An ack whose
 * token does not match the pending request is ignored: otherwise a late ack for a flush that
 * already timed out could satisfy a newer wait and let the player switch before the newer
 * flush committed. A tokenless ack (older backend) is still accepted for compatibility.
 *
 * <p>Futures automatically resolve after {@value #TIMEOUT_MS} ms so a slow or
 * unresponsive backend never stalls a server switch indefinitely.
 */
public class FlushAckTracker {

    static final long DEFAULT_TIMEOUT_MS = 2_000;

    enum FlushOutcome {
        /** The backend confirmed the balance is durable. */
        ACKNOWLEDGED,
        /** The backend reported that the flush failed. */
        FLUSH_FAILED,
        /** No reply arrived in time. */
        TIMED_OUT,
        /** A newer flush was registered for the same player, so this one no longer matters. */
        SUPERSEDED
    }

    /** A registered flush: the token must be sent to the backend and echoed in its ack. */
    public record PendingFlush(long token, CompletableFuture<FlushOutcome> future) {
    }

    private static final class Entry {
        private final long token;
        private final CompletableFuture<FlushOutcome> future;

        private Entry(long token, CompletableFuture<FlushOutcome> future) {
            this.token = token;
            this.future = future;
        }
    }

    private final ConcurrentHashMap<UUID, Entry> pending = new ConcurrentHashMap<>();
    private final AtomicLong tokenCounter = new AtomicLong();
    /**
     * Guards the pending map against the register/complete race: remove-then-put is not atomic,
     * so two concurrent registrations could orphan a wait that would then deny a switch whose
     * flush actually succeeded.
     */
    private final Object registrationLock = new Object();
    private final long timeoutMs;

    public FlushAckTracker() {
        this(DEFAULT_TIMEOUT_MS);
    }

    FlushAckTracker(long timeoutMs) {
        this.timeoutMs = Math.max(1L, timeoutMs);
    }

    /**
     * Registers a pending flush for {@code uuid} and returns its token and future. The future
     * completes normally when the backend replies, the flush fails, or the timeout elapses.
     *
     * <p>Any earlier pending flush for the same player is resolved as
     * {@link FlushOutcome#SUPERSEDED}. Otherwise it would linger until its own timeout and then
     * deny a switch that has already been replaced, showing the player a bogus error.
     */
    public PendingFlush register(UUID uuid) {
        long token = tokenCounter.incrementAndGet();
        CompletableFuture<FlushOutcome> future = new CompletableFuture<>();
        Entry entry = new Entry(token, future);

        Entry previous;
        synchronized (registrationLock) {
            previous = pending.put(uuid, entry);
        }
        // Completed outside the lock: callbacks re-enter the tracker to remove themselves.
        if (previous != null) {
            previous.future.complete(FlushOutcome.SUPERSEDED);
        }

        future.completeOnTimeout(FlushOutcome.TIMED_OUT, timeoutMs, TimeUnit.MILLISECONDS)
                .whenComplete((outcome, error) -> {
                    synchronized (registrationLock) {
                        pending.remove(uuid, entry);
                    }
                });
        return new PendingFlush(token, future);
    }

    /** Called for a {@code flushed <uuid>} ack without a token (older backend). */
    public void acknowledge(UUID uuid) {
        complete(uuid, null, FlushOutcome.ACKNOWLEDGED);
    }

    /** Called for a {@code flushed <uuid> <token>} ack. */
    public void acknowledge(UUID uuid, long token) {
        complete(uuid, token, FlushOutcome.ACKNOWLEDGED);
    }

    /** Called for a {@code flushfailed <uuid>} report without a token (older backend). */
    public void reportFailure(UUID uuid) {
        complete(uuid, null, FlushOutcome.FLUSH_FAILED);
    }

    /**
     * Called when a backend reports {@code flushfailed <uuid> <token>}. The switch must be
     * denied so the destination never loads a balance that was never persisted.
     */
    public void reportFailure(UUID uuid, long token) {
        complete(uuid, token, FlushOutcome.FLUSH_FAILED);
    }

    private void complete(UUID uuid, Long token, FlushOutcome outcome) {
        Entry entry;
        synchronized (registrationLock) {
            entry = pending.get(uuid);
            if (entry == null || (token != null && entry.token != token)) {
                return; // stale ack for a superseded or already-resolved flush
            }
            pending.remove(uuid, entry);
        }
        entry.future.complete(outcome);
    }

    /** Number of in-flight flush acks currently being tracked. */
    public int pendingCount() {
        return pending.size();
    }
}
