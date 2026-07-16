/*
 * Copyright 2026 alexisbinh
 * Licensed under the Apache License, Version 2.0.
 */
package dev.alexisbinh.openeco.api;

import java.util.Optional;

public interface ClusterJobCoordinator {
    interface Lease extends AutoCloseable {
        /** Extends this lease using database time. Returns false if ownership was lost. */
        default boolean renew() { return true; }
        void complete();
        @Override void close();
    }

    Optional<Lease> tryAcquire(String jobId, String runId, long leaseMs);

    /** Authoritative network time used for deterministic run buckets. */
    default long currentTimeMillis() { return System.currentTimeMillis(); }
}
