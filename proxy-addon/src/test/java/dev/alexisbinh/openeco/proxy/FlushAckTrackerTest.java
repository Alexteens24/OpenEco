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