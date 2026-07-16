/*
 * Copyright 2026 alexisbinh
 * Licensed under the Apache License, Version 2.0.
 */
package dev.alexisbinh.openeco.api;

import java.util.Optional;

public interface ClusterJobCoordinator {
    interface Lease extends AutoCloseable {
        void complete();
        @Override void close();
    }

    Optional<Lease> tryAcquire(String jobId, String runId, long leaseMs);
}
