/*
 * Copyright 2026 alexisbinh
 * Licensed under the Apache License, Version 2.0.
 */
package dev.alexisbinh.openeco.service;

import dev.alexisbinh.openeco.api.NetworkPolicyStateStore;
import dev.alexisbinh.openeco.api.OpenEcoApiException;
import dev.alexisbinh.openeco.storage.MultiWriterRepository;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** JDBC-backed policy state; writes never block a Bukkit region thread. */
public final class NetworkPolicyStateStoreImpl implements NetworkPolicyStateStore, AutoCloseable {
    private final MultiWriterRepository repository;
    private final ThreadPoolExecutor writer;

    public NetworkPolicyStateStoreImpl(MultiWriterRepository repository, String threadPrefix) {
        this.repository = repository;
        this.writer = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1_024), runnable -> {
                    Thread thread = new Thread(runnable, threadPrefix + "-policy-state");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public Optional<String> load(String providerId, UUID subjectId) {
        try {
            return repository.loadPolicyState(providerId, subjectId);
        } catch (SQLException e) {
            throw new OpenEcoApiException("Failed to load network policy state", e);
        }
    }

    @Override
    public CompletionStage<Void> save(String providerId, UUID subjectId, String state) {
        try {
            return CompletableFuture.runAsync(() -> {
                try {
                    repository.savePolicyState(providerId, subjectId, state);
                } catch (SQLException e) {
                    throw new OpenEcoApiException("Failed to save network policy state", e);
                }
            }, writer);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) writer.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writer.shutdownNow();
        }
    }
}
