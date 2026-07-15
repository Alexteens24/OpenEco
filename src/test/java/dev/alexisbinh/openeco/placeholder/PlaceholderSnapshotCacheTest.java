/*
 * Copyright 2026 alexisbinh
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package dev.alexisbinh.openeco.placeholder;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlaceholderSnapshotCacheTest {

    @Test
    void coldLookupReturnsFallbackAndPublishesCompletedSnapshot() {
        PlaceholderSnapshotCache cache = new PlaceholderSnapshotCache(10);
        CompletableFuture<String> pending = new CompletableFuture<>();

        assertEquals("0", cache.get("player:balance", 1L, "0", () -> "12", ignored -> pending));

        pending.complete("12");
        assertEquals("12", cache.get("player:balance", Long.MAX_VALUE, "0", () -> "ignored",
                supplier -> CompletableFuture.completedFuture(supplier.get())));
    }

    @Test
    void concurrentColdRequestsCoalesceOntoOneRefresh() {
        PlaceholderSnapshotCache cache = new PlaceholderSnapshotCache(10);
        CompletableFuture<String> pending = new CompletableFuture<>();
        AtomicInteger submissions = new AtomicInteger();

        assertEquals("false", cache.get("player:frozen", 1L, "false", () -> "true", supplier -> {
            submissions.incrementAndGet();
            return pending;
        }));
        assertEquals("false", cache.get("player:frozen", 1L, "false", () -> "true", supplier -> {
            submissions.incrementAndGet();
            return pending;
        }));

        assertEquals(1, submissions.get());
    }
}
