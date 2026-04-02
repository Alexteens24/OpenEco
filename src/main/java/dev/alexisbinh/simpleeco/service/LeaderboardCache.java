package dev.alexisbinh.simpleeco.service;

import dev.alexisbinh.simpleeco.model.AccountRecord;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

final class LeaderboardCache {

    private final Object lock = new Object();
    private List<AccountRecord> cachedBalTop;
    private long cacheExpiry;
    private long cacheTtlMs = 30_000L;
    private long cacheVersion;
    private long invalidationVersion;

    void setCacheTtlMs(long cacheTtlMs) {
        synchronized (lock) {
            this.cacheTtlMs = Math.max(1L, cacheTtlMs);
            invalidateLocked();
        }
    }

    List<AccountRecord> getSnapshot(Collection<AccountRecord> liveRecords) {
        while (true) {
            long now = System.currentTimeMillis();
            long observedVersion;
            List<AccountRecord> cached;

            synchronized (lock) {
                observedVersion = invalidationVersion;
                cached = cachedBalTop;
                if (cached != null && cacheVersion == observedVersion && now < cacheExpiry) {
                    return cached;
                }
            }

            List<AccountRecord> sorted = new ArrayList<>(liveRecords.size());
            for (AccountRecord record : liveRecords) {
                synchronized (record) {
                    sorted.add(record.snapshot());
                }
            }
            sorted.sort((left, right) -> right.getBalance().compareTo(left.getBalance()));
            List<AccountRecord> snapshot = List.copyOf(sorted);

            synchronized (lock) {
                // If an invalidation raced with this rebuild, discard it and retry.
                if (invalidationVersion != observedVersion) {
                    continue;
                }
                cachedBalTop = snapshot;
                cacheVersion = observedVersion;
                cacheExpiry = System.currentTimeMillis() + cacheTtlMs;
                return snapshot;
            }
        }
    }

    void markDirty() {
        invalidate();
    }

    void invalidate() {
        synchronized (lock) {
            invalidateLocked();
        }
    }

    private void invalidateLocked() {
        invalidationVersion++;
        cachedBalTop = null;
        cacheExpiry = 0L;
        cacheVersion = invalidationVersion;
    }
}