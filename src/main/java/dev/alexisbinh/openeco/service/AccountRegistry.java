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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

final class AccountRegistry {

    private final ConcurrentHashMap<UUID, AccountRecord> accounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UUID> nameIndex = new ConcurrentHashMap<>();

    void loadAll(List<AccountRecord> records) {
        accounts.clear();
        nameIndex.clear();
        for (AccountRecord record : records) {
            accounts.put(record.getId(), record);
            nameIndex.put(normalizeName(record.getLastKnownName()), record.getId());
        }
    }

    boolean hasAccount(UUID id) {
        return accounts.containsKey(id);
    }

    AccountRecord getLiveRecord(UUID id) {
        return accounts.get(id);
    }

    Optional<AccountRecord> getSnapshot(UUID id) {
        AccountRecord record = accounts.get(id);
        if (record == null) {
            return Optional.empty();
        }
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
        nameIndex.put(newKey, record.getId());

        String oldKey = normalizeName(previous.getLastKnownName());
        if (!oldKey.equals(newKey)) {
            nameIndex.remove(oldKey, record.getId());
        }
        return true;
    }

    void restore(AccountRecord record) {
        accounts.put(record.getId(), record);
        nameIndex.put(normalizeName(record.getLastKnownName()), record.getId());
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