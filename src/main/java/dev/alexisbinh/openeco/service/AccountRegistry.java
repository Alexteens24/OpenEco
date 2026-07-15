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
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

final class AccountRegistry {

    private final ConcurrentHashMap<UUID, AccountRecord> accounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UUID> nameIndex = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> lastAccessNanos = new ConcurrentHashMap<>();
    private final Set<UUID> onlineAccounts = ConcurrentHashMap.newKeySet();

    void clear() {
        accounts.clear();
        nameIndex.clear();
        lastAccessNanos.clear();
        onlineAccounts.clear();
    }

    boolean addLoaded(AccountRecord record) {
        return create(record);
    }

    boolean hasAccount(UUID id) {
        return accounts.containsKey(id);
    }

    int size() {
        return accounts.size();
    }

    AccountRecord getLiveRecord(UUID id) {
        AccountRecord record = accounts.get(id);
        if (record != null) touch(id);
        return record;
    }

    Optional<AccountRecord> getSnapshot(UUID id) {
        AccountRecord record = accounts.get(id);
        if (record == null) {
            return Optional.empty();
        }
        touch(id);
        synchronized (record) {
            return Optional.of(record.snapshot());
        }
    }

    Optional<AccountRecord> findSnapshotByName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        UUID id = nameIndex.get(normalizeName(name));
        if (id == null) {
            return Optional.empty();
        }
        return getSnapshot(id);
    }

    boolean isNameClaimedByAnother(UUID id, String name) {
        UUID owner = nameIndex.get(normalizeName(name));
        return owner != null && !owner.equals(id);
    }

    boolean create(AccountRecord record) {
        AccountRecord existing = accounts.putIfAbsent(record.getId(), record);
        if (existing != null) {
            return false;
        }
        UUID owner = nameIndex.putIfAbsent(normalizeName(record.getLastKnownName()), record.getId());
        if (owner != null && !owner.equals(record.getId())) {
            accounts.remove(record.getId(), record);
            return false;
        }
        touch(record.getId());
        return true;
    }

    boolean rename(AccountRecord record, String newName) {
        String oldName = record.getLastKnownName();
        String oldKey = normalizeName(oldName);
        String newKey = normalizeName(newName);
        if (oldKey.equals(newKey)) {
            record.setLastKnownName(newName);
            return true;
        }

        UUID owner = nameIndex.putIfAbsent(newKey, record.getId());
        if (owner != null && !owner.equals(record.getId())) {
            return false;
        }

        nameIndex.remove(oldKey, record.getId());
        record.setLastKnownName(newName);
        return true;
    }

    boolean remove(UUID id, AccountRecord record) {
        if (!accounts.remove(id, record)) {
            return false;
        }
        nameIndex.remove(normalizeName(record.getLastKnownName()), id);
        lastAccessNanos.remove(id);
        onlineAccounts.remove(id);
        return true;
    }

    boolean replace(AccountRecord record) {
        AccountRecord previous = accounts.get(record.getId());
        if (previous == null) {
            return create(record);
        }

        String newKey = normalizeName(record.getLastKnownName());
        UUID owner = nameIndex.get(newKey);
        if (owner != null && !owner.equals(record.getId())) {
            return false;
        }

        accounts.put(record.getId(), record);
        touch(record.getId());
        nameIndex.put(newKey, record.getId());

        String oldKey = normalizeName(previous.getLastKnownName());
        if (!oldKey.equals(newKey)) {
            nameIndex.remove(oldKey, record.getId());
        }
        return true;
    }

    boolean refreshInPlace(AccountRecord freshRecord) {
        AccountRecord live = accounts.get(freshRecord.getId());
        if (live == null) {
            return create(freshRecord);
        }

        String newKey = normalizeName(freshRecord.getLastKnownName());
        UUID owner = nameIndex.get(newKey);
        if (owner != null && !owner.equals(freshRecord.getId())) {
            return false;
        }

        String oldKey = normalizeName(live.getLastKnownName());
        live.overwriteFrom(freshRecord);
        nameIndex.put(newKey, freshRecord.getId());
        if (!oldKey.equals(newKey)) {
            nameIndex.remove(oldKey, freshRecord.getId());
        }
        return true;
    }

    void restore(AccountRecord record) {
        accounts.put(record.getId(), record);
        nameIndex.put(normalizeName(record.getLastKnownName()), record.getId());
        touch(record.getId());
    }

    boolean isLive(UUID id, AccountRecord record) {
        return accounts.get(id) == record;
    }

    Map<UUID, String> getUUIDNameMap() {
        Map<UUID, String> map = new HashMap<>(accounts.size());
        for (AccountRecord record : accounts.values()) {
            map.put(record.getId(), record.getLastKnownName());
        }
        return Collections.unmodifiableMap(map);
    }

    List<String> getAccountNames() {
        List<String> names = new ArrayList<>(accounts.size());
        for (AccountRecord record : accounts.values()) {
            names.add(record.getLastKnownName());
        }
        return names;
    }

    Collection<AccountRecord> liveRecords() {
        return accounts.values();
    }

    void markOnline(UUID id) {
        if (!accounts.containsKey(id)) return;
        onlineAccounts.add(id);
        touch(id);
    }

    void markOffline(UUID id) {
        onlineAccounts.remove(id);
        if (accounts.containsKey(id)) touch(id);
    }

    List<String> getCachedAccountNames() {
        return getAccountNames();
    }

    int evict(long expireAfterNanos, int maximumSize) {
        long now = System.nanoTime();
        List<Map.Entry<UUID, Long>> candidates = new ArrayList<>(lastAccessNanos.entrySet());
        candidates.sort(Map.Entry.comparingByValue());
        int removed = 0;
        for (Map.Entry<UUID, Long> candidate : candidates) {
            boolean expired = now - candidate.getValue() >= expireAfterNanos;
            boolean oversized = accounts.size() > maximumSize;
            if (!expired && !oversized) continue;
            UUID id = candidate.getKey();
            if (onlineAccounts.contains(id)) continue;
            AccountRecord record = accounts.get(id);
            if (record == null) continue;
            synchronized (record) {
                if (record.isDirty() || onlineAccounts.contains(id)) continue;
                if (remove(id, record)) removed++;
            }
        }
        return removed;
    }

    private void touch(UUID id) {
        lastAccessNanos.put(id, System.nanoTime());
    }

    void syncCurrencies(String currencyId, Function<String, String> canonicalizer) {
        for (AccountRecord record : accounts.values()) {
            synchronized (record) {
                boolean changed = record.canonicalizeCurrencyIds(canonicalizer);
                record.setPrimaryCurrencyId(currencyId);
                if (changed) {
                    record.markDirty();
                }
            }
        }
    }

    static String normalizeName(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
