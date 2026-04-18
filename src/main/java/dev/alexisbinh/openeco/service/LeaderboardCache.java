/*
 * Copyright 2026 alexisbinh
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.alexisbinh.openeco.service;

import dev.alexisbinh.openeco.model.AccountRecord;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class LeaderboardCache {

    private static final String PRIMARY_BALANCE_CACHE_KEY = "__primary__";

    private final Object lock = new Object();
    private final Map<String, List<AccountRecord>> cachedBalTopByCurrency = new HashMap<>();
    private final Map<String, Long> cacheExpiryByCurrency = new HashMap<>();
    private long cacheTtlMs = 30_000L;
    private long invalidationVersion;

    void setCacheTtlMs(long cacheTtlMs) {
        synchronized (lock) {
            this.cacheTtlMs = Math.max(1L, cacheTtlMs);
            invalidateLocked();
        }
    }

    List<AccountRecord> getSnapshot(Collection<AccountRecord> liveRecords) {
        return getSnapshotInternal(PRIMARY_BALANCE_CACHE_KEY, liveRecords, null);
    }

    List<AccountRecord> getSnapshot(String currencyId, Collection<AccountRecord> liveRecords) {
        return getSnapshotInternal(currencyId, liveRecords, currencyId);
    }

    private List<AccountRecord> getSnapshotInternal(String cacheKey, Collection<AccountRecord> liveRecords,
            String currencyId) {
        while (true) {
            long now = System.currentTimeMillis();
            long observedVersion;
            List<AccountRecord> cached;

            synchronized (lock) {
                observedVersion = invalidationVersion;
                cached = cachedBalTopByCurrency.get(cacheKey);
                long expiry = cacheExpiryByCurrency.getOrDefault(cacheKey, 0L);
                if (cached != null && now < expiry) {
                    return cached;
                }
            }

            List<AccountRecord> sorted = new ArrayList<>(liveRecords.size());
            for (AccountRecord record : liveRecords) {
                synchronized (record) {
                    sorted.add(record.snapshot());
                }
            }
            if (currencyId == null) {
                sorted.sort((left, right) -> right.getBalance().compareTo(left.getBalance()));
            } else {
                sorted.sort((left, right) -> right.getBalance(currencyId).compareTo(left.getBalance(currencyId)));
            }
            List<AccountRecord> snapshot = List.copyOf(sorted);

            synchronized (lock) {
                // If an invalidation raced with this rebuild, discard it and retry.
                if (invalidationVersion != observedVersion) {
                    continue;
                }
                cachedBalTopByCurrency.put(cacheKey, snapshot);
                cacheExpiryByCurrency.put(cacheKey, System.currentTimeMillis() + cacheTtlMs);
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
        cachedBalTopByCurrency.clear();
        cacheExpiryByCurrency.clear();
    }
}