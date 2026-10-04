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

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlushAckTrackerTest {

    @Test
    void acknowledgeCompletesPendingFlush() throws Exception {
        FlushAckTracker tracker = new FlushAckTracker(250);
        UUID accountId = UUID.randomUUID();

        var future = tracker.register(accountId);
        tracker.acknowledge(accountId);

        assertEquals(FlushAckTracker.FlushOutcome.ACKNOWLEDGED, future.get(200, TimeUnit.MILLISECONDS));
        assertEquals(0, tracker.pendingCount());
    }

    @Test
    void timeoutCompletesAsTimedOut() throws Exception {
        FlushAckTracker tracker = new FlushAckTracker(25);
        UUID accountId = UUID.randomUUID();

        var future = tracker.register(accountId);

        assertEquals(FlushAckTracker.FlushOutcome.TIMED_OUT, future.get(500, TimeUnit.MILLISECONDS));
        assertEquals(0, tracker.pendingCount());
    }

    /**
     * A backend that reports a failed flush must resolve the wait as a failure, not as an
     * acknowledgement, so the server switch is denied instead of loading a stale balance.
     */
    @Test
    void reportFailureCompletesAsFlushFailed() throws Exception {
        FlushAckTracker tracker = new FlushAckTracker(2_000);
        UUID accountId = UUID.randomUUID();

        var future = tracker.register(accountId);
        tracker.reportFailure(accountId);

        assertEquals(FlushAckTracker.FlushOutcome.FLUSH_FAILED, future.get(200, TimeUnit.MILLISECONDS));
        assertEquals(0, tracker.pendingCount());
    }

    /**
     * Registering twice for the same player resolves the first wait immediately. Left alone it
     * would sit until its own timeout and then deny a switch that had already been replaced,
     * showing the player an error while they are already on the new server.
     */
    @Test
    void reRegisteringSupersedesTheEarlierWait() throws Exception {
        FlushAckTracker tracker = new FlushAckTracker(5_000);
        UUID accountId = UUID.randomUUID();

        var first = tracker.register(accountId);
        var second = tracker.register(accountId);

        assertEquals(FlushAckTracker.FlushOutcome.SUPERSEDED, first.get(200, TimeUnit.MILLISECONDS));

        tracker.acknowledge(accountId);
        assertEquals(FlushAckTracker.FlushOutcome.ACKNOWLEDGED, second.get(200, TimeUnit.MILLISECONDS));
        assertEquals(0, tracker.pendingCount());
    }

    @Test
    void unknownAckIsIgnored() throws Exception {
        FlushAckTracker tracker = new FlushAckTracker(25);
        UUID accountId = UUID.randomUUID();
        var future = tracker.register(accountId);

        tracker.acknowledge(UUID.randomUUID());
        tracker.reportFailure(UUID.randomUUID());

        assertEquals(FlushAckTracker.FlushOutcome.TIMED_OUT, future.get(500, TimeUnit.MILLISECONDS));
    }
}