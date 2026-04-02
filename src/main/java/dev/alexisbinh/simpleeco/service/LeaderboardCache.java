package dev.alexisbinh.simpleeco.service;

import dev.alexisbinh.simpleeco.model.AccountRecord;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

final class LeaderboardCache {

    private volatile List<AccountRecord> cachedBalTop;
    private volatile long cacheExpiry;
    private volatile long cacheTtlMs = 30_000L;
    // Set to true by balance mutations so the next expired-cache request rebuilds with
    // fresh data, but the current snapshot keeps being served until TTL runs out.
    private volatile boolean dirty = false;

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

        dirty = false;
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
        dirty = true;
    }

    /**
     * Hard invalidation: immediately drops the cached snapshot so the next request
     * always sees a fresh rebuild. Use this when accounts are created or deleted,
     * where serving a stale list would show wrong entries.
     */
    void invalidate() {
        dirty = false;
        cachedBalTop = null;
        cacheExpiry = 0L;
    }
}