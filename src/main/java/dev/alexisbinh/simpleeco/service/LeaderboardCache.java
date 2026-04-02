package dev.alexisbinh.simpleeco.service;

import dev.alexisbinh.simpleeco.model.AccountRecord;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

final class LeaderboardCache {

    private volatile List<AccountRecord> cachedBalTop;
    private volatile long cacheExpiry;
    private volatile long cacheTtlMs = 30_000L;

    void setCacheTtlMs(long cacheTtlMs) {
        this.cacheTtlMs = Math.max(1L, cacheTtlMs);
        invalidate();
    }

    List<AccountRecord> getSnapshot(Collection<AccountRecord> liveRecords) {
        long now = System.currentTimeMillis();
        List<AccountRecord> cached = cachedBalTop;
        if (cached != null && now < cacheExpiry) {
            return cached;  // serve existing snapshot; balance changes wait for TTL expiry
        }

        List<AccountRecord> sorted = new ArrayList<>(liveRecords.size());
        for (AccountRecord record : liveRecords) {
            synchronized (record) {
                sorted.add(record.snapshot());
            }
        }
        sorted.sort((left, right) -> right.getBalance().compareTo(left.getBalance()));
        sorted = List.copyOf(sorted);
        cachedBalTop = sorted;
        cacheExpiry = now + cacheTtlMs;
        return sorted;
    }

    /**
     * Soft invalidation: marks the cache as stale without dropping it.
     * The current snapshot continues to be served until TTL expires, at which point the
     * next request rebuilds. Use this for balance mutations (high-frequency hot path).
     */
    void markDirty() {
        // No-op: balance mutations don't drop the cached snapshot.
        // The existing snapshot keeps being served until TTL expires, at which point
        // the next request rebuilds with fresh data.
    }

    /**
     * Hard invalidation: immediately drops the cached snapshot so the next request
     * always sees a fresh rebuild. Use this when accounts are created or deleted,
     * where serving a stale list would show wrong entries.
     */
    void invalidate() {
        cachedBalTop = null;
        cacheExpiry = 0L;
    }
}