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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class LeaderboardCache {

    private record Snapshot(List<LeaderboardEntry> entries, Map<UUID, Integer> ranks) {
        private static final Snapshot EMPTY = new Snapshot(List.of(), Map.of());
    }

    private final Map<String, Snapshot> snapshots = new HashMap<>();
    private final Map<String, Long> versions = new HashMap<>();
    private final Set<String> dirtyCurrencies = new HashSet<>();

    synchronized void configureCurrencies(Collection<String> currencyIds) {
        Set<String> configured = Set.copyOf(currencyIds);
        snapshots.keySet().retainAll(configured);
        versions.keySet().retainAll(configured);
        dirtyCurrencies.retainAll(configured);
        for (String currencyId : configured) {
            versions.putIfAbsent(currencyId, 0L);
            dirtyCurrencies.add(currencyId);
        }
    }

    synchronized void markDirty(String currencyId) {
        versions.computeIfPresent(currencyId, (ignored, version) -> version + 1L);
        if (versions.containsKey(currencyId)) {
            dirtyCurrencies.add(currencyId);
        }
    }

    synchronized void markAllDirty() {
        for (String currencyId : versions.keySet()) {
            versions.put(currencyId, versions.get(currencyId) + 1L);
            dirtyCurrencies.add(currencyId);
        }
    }

    synchronized void clearSnapshots() {
        snapshots.clear();
        dirtyCurrencies.clear();
        dirtyCurrencies.addAll(versions.keySet());
    }

    void rebuildAll(Collection<AccountRecord> records) {
        List<String> currencyIds;
        synchronized (this) {
            currencyIds = List.copyOf(versions.keySet());
        }
        rebuild(records, currencyIds);
    }

    void refreshDirty(Collection<AccountRecord> records) {
        List<String> currencyIds;
        synchronized (this) {
            currencyIds = List.copyOf(dirtyCurrencies);
        }
        rebuild(records, currencyIds);
    }

    private void rebuild(Collection<AccountRecord> records, List<String> currencyIds) {
        for (String currencyId : currencyIds) {
            long observedVersion;
            synchronized (this) {
                Long version = versions.get(currencyId);
                if (version == null) continue;
                observedVersion = version;
            }

            ArrayList<LeaderboardEntry> entries = new ArrayList<>(records.size());
            for (AccountRecord record : records) {
                synchronized (record) {
                    entries.add(new LeaderboardEntry(
                            record.getId(), record.getLastKnownName(), record.getBalance(currencyId)));
                }
            }
            entries.sort((left, right) -> {
                int byBalance = right.balance().compareTo(left.balance());
                if (byBalance != 0) return byBalance;
                int byName = left.name().compareToIgnoreCase(right.name());
                if (byName != 0) return byName;
                return left.accountId().compareTo(right.accountId());
            });

            List<LeaderboardEntry> immutableEntries = List.copyOf(entries);
            HashMap<UUID, Integer> ranks = new HashMap<>(Math.max(16, entries.size() * 4 / 3 + 1));
            for (int i = 0; i < entries.size(); i++) {
                ranks.put(entries.get(i).accountId(), i + 1);
            }
            Snapshot snapshot = new Snapshot(immutableEntries, Map.copyOf(ranks));

            synchronized (this) {
                if (!versions.containsKey(currencyId)) continue;
                snapshots.put(currencyId, snapshot);
                if (versions.get(currencyId) == observedVersion) {
                    dirtyCurrencies.remove(currencyId);
                }
            }
        }
    }

    synchronized LeaderboardView page(String currencyId, int offset, int limit) {
        Snapshot snapshot = snapshots.getOrDefault(currencyId, Snapshot.EMPTY);
        int from = Math.min(Math.max(0, offset), snapshot.entries().size());
        int to = Math.min(snapshot.entries().size(), from + Math.max(0, limit));
        return new LeaderboardView(snapshot.entries().size(), snapshot.entries().subList(from, to));
    }

    synchronized LeaderboardEntry entryAtRank(String currencyId, int rank) {
        Snapshot snapshot = snapshots.getOrDefault(currencyId, Snapshot.EMPTY);
        return rank < 1 || rank > snapshot.entries().size() ? null : snapshot.entries().get(rank - 1);
    }

    synchronized int rankOf(String currencyId, UUID accountId) {
        return snapshots.getOrDefault(currencyId, Snapshot.EMPTY).ranks().getOrDefault(accountId, -1);
    }
}
