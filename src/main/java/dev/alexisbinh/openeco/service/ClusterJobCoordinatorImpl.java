/*
 * Copyright 2026 alexisbinh
 * Licensed under the Apache License, Version 2.0.
 */
package dev.alexisbinh.openeco.service;

import dev.alexisbinh.openeco.api.ClusterJobCoordinator;
import dev.alexisbinh.openeco.storage.MultiWriterRepository;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

public final class ClusterJobCoordinatorImpl implements ClusterJobCoordinator {
    private final MultiWriterRepository repository;
    private final String ownerId = UUID.randomUUID().toString();
    private final Logger log;

    public ClusterJobCoordinatorImpl(MultiWriterRepository repository, Logger log) {
        this.repository = repository;
        this.log = log;
    }

    @Override
    public Optional<Lease> tryAcquire(String jobId, String runId, long leaseMs) {
        if (repository == null) return Optional.of(new LocalLease());
        if (jobId == null || !jobId.matches("[a-z0-9_.-]{1,64}")) throw new IllegalArgumentException("Invalid jobId");
        if (runId == null || runId.isBlank() || runId.length() > 96) throw new IllegalArgumentException("Invalid runId");
        long now = System.currentTimeMillis();
        try {
            if (!repository.tryAcquireJobLease(jobId, runId, ownerId, now, now + Math.max(1_000L, leaseMs))) {
                return Optional.empty();
            }
            return Optional.of(new DatabaseLease(jobId, runId));
        } catch (SQLException e) {
            log.warning("Failed to acquire cluster job lease " + jobId + "/" + runId + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    private final class DatabaseLease implements Lease {
        private final String jobId;
        private final String runId;
        private final AtomicBoolean completed = new AtomicBoolean();
        DatabaseLease(String jobId, String runId) { this.jobId = jobId; this.runId = runId; }
        @Override public void complete() {
            if (!completed.compareAndSet(false, true)) return;
            try { repository.completeJobLease(jobId, runId, ownerId); }
            catch (SQLException e) { log.warning("Failed to complete cluster job lease: " + e.getMessage()); }
        }
        @Override public void close() { }
    }

    private static final class LocalLease implements Lease {
        @Override public void complete() { }
        @Override public void close() { }
    }
}
