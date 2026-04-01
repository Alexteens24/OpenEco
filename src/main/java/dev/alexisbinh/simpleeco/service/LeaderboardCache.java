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
            return cached;
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

    void invalidate() {
        cachedBalTop = null;
        cacheExpiry = 0L;
    }
}