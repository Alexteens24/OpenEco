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
 * pending future here. The backend replies with {@code flushed <uuid>} over the
 * plugin channel; {@link #acknowledge(UUID)} completes that future so the caller
 * can chain the next step (typically a {@code refresh} to the destination server).
 *
 * <p>Futures automatically resolve after {@value #TIMEOUT_MS} ms so a slow or
 * unresponsive backend never stalls a server switch indefinitely.
 */
public class FlushAckTracker {

    static final long DEFAULT_TIMEOUT_MS = 2_000;

    enum FlushOutcome {
        ACKNOWLEDGED,
        TIMED_OUT
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
     * completes normally when either the ack arrives or the timeout elapses.
     */
    public CompletableFuture<FlushOutcome> register(UUID uuid) {
        CompletableFuture<FlushOutcome> inner = new CompletableFuture<>();
        pending.put(uuid, inner);
        return inner
                .completeOnTimeout(FlushOutcome.TIMED_OUT, timeoutMs, TimeUnit.MILLISECONDS)
                .whenComplete((v, ex) -> pending.remove(uuid, inner));
    }

    /** Called when a {@code flushed <uuid>} ack is received from a backend server. */
    public void acknowledge(UUID uuid) {
        CompletableFuture<FlushOutcome> future = pending.remove(uuid);
        if (future != null) {
            future.complete(FlushOutcome.ACKNOWLEDGED);
        }
    }

    /** Number of in-flight flush acks currently being tracked. */
    public int pendingCount() {
        return pending.size();
    }
}
