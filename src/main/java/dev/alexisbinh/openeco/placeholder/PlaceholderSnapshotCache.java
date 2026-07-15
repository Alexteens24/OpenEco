/*
 * Copyright 2026 alexisbinh
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package dev.alexisbinh.openeco.placeholder;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

final class PlaceholderSnapshotCache {

    private static final long IDLE_EXPIRY_NANOS = TimeUnit.MINUTES.toNanos(10);

    private final int maximumSize;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    PlaceholderSnapshotCache(int maximumSize) {
        this.maximumSize = maximumSize;
    }

    String get(String key, long ttlNanos, String fallback, Supplier<String> loader,
               Function<Supplier<String>, CompletableFuture<String>> executor) {
        long now = System.nanoTime();
        Entry entry = entries.computeIfAbsent(key, ignored -> new Entry(fallback, 0L, now));
        entry.lastAccessNanos = now;
        if (now - entry.loadedAtNanos >= ttlNanos && entry.refreshing.compareAndSet(false, true)) {
            CompletableFuture<String> refresh;
            try {
                refresh = executor.apply(loader);
            } catch (RuntimeException error) {
                entry.refreshing.set(false);
                return entry.value;
            }
            refresh.whenComplete((value, error) -> {
                if (error == null && value != null) {
                    entry.value = value;
                    entry.loadedAtNanos = System.nanoTime();
                }
                entry.refreshing.set(false);
            });
        }
        if (entries.size() > maximumSize) trim(now, key);
        return entry.value;
    }

    private void trim(long now, String protectedKey) {
        entries.entrySet().removeIf(candidate -> !candidate.getKey().equals(protectedKey)
                && !candidate.getValue().refreshing.get()
                && now - candidate.getValue().lastAccessNanos >= IDLE_EXPIRY_NANOS);
        if (entries.size() <= maximumSize) return;
        for (Map.Entry<String, Entry> candidate : entries.entrySet()) {
            if (entries.size() <= maximumSize) break;
            if (!candidate.getKey().equals(protectedKey) && !candidate.getValue().refreshing.get()) {
                entries.remove(candidate.getKey(), candidate.getValue());
            }
        }
    }

    private static final class Entry {
        private volatile String value;
        private volatile long loadedAtNanos;
        private volatile long lastAccessNanos;
        private final AtomicBoolean refreshing = new AtomicBoolean();

        private Entry(String value, long loadedAtNanos, long lastAccessNanos) {
            this.value = value;
            this.loadedAtNanos = loadedAtNanos;
            this.lastAccessNanos = lastAccessNanos;
        }
    }
}
