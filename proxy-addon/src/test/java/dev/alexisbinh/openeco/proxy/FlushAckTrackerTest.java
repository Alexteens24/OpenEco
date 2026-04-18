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
}