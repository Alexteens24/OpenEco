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

/**
  * Tracks pending flush acknowledgements from backend servers.
  *
  * <p>When the proxy asks a backend to flush a player's account it registers a
  * pending future here. The backend replies with {@code flushed <uuid>} on success or
  * {@code flushfailed <uuid>} when the balance did not reach storage;
  * {@link #acknowledge(UUID)} and {@link #reportFailure(UUID)} complete that future so the
  * caller can allow or deny the server switch.
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

    private final ConcurrentHashMap<UUID, CompletableFuture<FlushOutcome>> pending = new ConcurrentHashMap<>();
    private final long timeoutMs;

    public FlushAckTracker() {
        this(DEFAULT_TIMEOUT_MS);
    }

    FlushAckTracker(long timeoutMs) {
        this.timeoutMs = Math.max(1L, timeoutMs);
    }

    /**
     * Registers a pending flush for {@code uuid} and returns a future that
     * completes normally when the backend replies, the flush fails, or the timeout elapses.
     *
     * <p>Any earlier pending flush for the same player is resolved as
     * {@link FlushOutcome#SUPERSEDED}. Otherwise it would linger until its own timeout and then
     * deny a switch that has already been replaced, showing the player a bogus error.
     */
    public CompletableFuture<FlushOutcome> register(UUID uuid) {
        CompletableFuture<FlushOutcome> previous = pending.remove(uuid);
        if (previous != null) {
            previous.complete(FlushOutcome.SUPERSEDED);
        }

        CompletableFuture<FlushOutcome> inner = new CompletableFuture<>();
        pending.put(uuid, inner);
        return inner
                .completeOnTimeout(FlushOutcome.TIMED_OUT, timeoutMs, TimeUnit.MILLISECONDS)
                .whenComplete((v, ex) -> pending.remove(uuid, inner));
    }

    /** Called when a {@code flushed <uuid>} ack is received from a backend server. */
    public void acknowledge(UUID uuid) {
        complete(uuid, FlushOutcome.ACKNOWLEDGED);
    }

    /**
     * Called when a backend reports {@code flushfailed <uuid>}. The switch must be denied so
     * the destination never loads a balance that was never persisted.
     */
    public void reportFailure(UUID uuid) {
        complete(uuid, FlushOutcome.FLUSH_FAILED);
    }

    private void complete(UUID uuid, FlushOutcome outcome) {
        CompletableFuture<FlushOutcome> future = pending.remove(uuid);
        if (future != null) {
            future.complete(outcome);
        }
    }

    /** Number of in-flight flush acks currently being tracked. */
    public int pendingCount() {
        return pending.size();
    }
}
