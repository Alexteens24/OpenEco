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

package dev.alexisbinh.openeco.storage;

import dev.alexisbinh.openeco.model.AccountRecord;
import dev.alexisbinh.openeco.service.LeaderboardView;

import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends TransactionRepository, AutoCloseable {

    @FunctionalInterface
    interface AccountBatchConsumer {
        void accept(List<AccountRecord> records) throws SQLException;
    }

    void loadBatches(int batchSize, AccountBatchConsumer consumer) throws SQLException;

    default List<AccountRecord> loadAll() throws SQLException {
        java.util.ArrayList<AccountRecord> records = new java.util.ArrayList<>();
        loadBatches(500, records::addAll);
        return records;
    }

    Optional<AccountRecord> loadAccount(UUID id) throws SQLException;

    default Optional<AccountRecord> loadAccountByName(String normalizedName) throws SQLException {
        String lookup = normalizedName.trim().toLowerCase(java.util.Locale.ROOT);
        return loadAll().stream()
                .filter(record -> record.getLastKnownName().toLowerCase(java.util.Locale.ROOT).equals(lookup))
                .findFirst();
    }

    default Map<UUID, String> loadNameMap() throws SQLException {
        java.util.LinkedHashMap<UUID, String> result = new java.util.LinkedHashMap<>();
        for (AccountRecord record : loadAll()) result.put(record.getId(), record.getLastKnownName());
        return Map.copyOf(result);
    }

    default Map<UUID, String> loadNames(Collection<UUID> accountIds) throws SQLException {
        java.util.LinkedHashMap<UUID, String> result = new java.util.LinkedHashMap<>();
        for (UUID accountId : accountIds) {
            loadAccount(accountId).ifPresent(record -> result.put(accountId, record.getLastKnownName()));
        }
        return Map.copyOf(result);
    }

    default int countAccounts() throws SQLException {
        return loadAll().size();
    }

    default boolean isNameClaimedByAnother(UUID accountId, String normalizedName) throws SQLException {
        return loadAccountByName(normalizedName).filter(record -> !record.getId().equals(accountId)).isPresent();
    }

    default boolean insertAccount(AccountRecord record) throws SQLException {
        if (loadAccount(record.getId()).isPresent()
                || isNameClaimedByAnother(record.getId(), record.getLastKnownName())) return false;
        upsertBatch(List.of(record));
        return true;
    }

    default boolean renameAccount(UUID accountId, String newName, long updatedAt) throws SQLException {
        Optional<AccountRecord> existing = loadAccount(accountId);
        if (existing.isEmpty() || isNameClaimedByAnother(accountId, newName)) return false;
        AccountRecord record = existing.get();
        record.setLastKnownName(newName);
        upsertBatch(List.of(record.snapshot()));
        return true;
    }

    default LeaderboardView loadLeaderboardPage(String currencyId, int offset, int limit) throws SQLException {
        List<AccountRecord> records = new java.util.ArrayList<>(loadAll());
        records.sort(java.util.Comparator
                .comparing((AccountRecord record) -> record.getBalance(currencyId)).reversed()
                .thenComparing(AccountRecord::getLastKnownName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(AccountRecord::getId));
        int from = Math.min(Math.max(0, offset), records.size());
        int to = Math.min(records.size(), from + Math.max(0, limit));
        List<dev.alexisbinh.openeco.service.LeaderboardEntry> entries = records.subList(from, to).stream()
                .map(record -> new dev.alexisbinh.openeco.service.LeaderboardEntry(
                        record.getId(), record.getLastKnownName(), record.getBalance(currencyId)))
                .toList();
        return new LeaderboardView(records.size(), entries);
    }

    default int loadLeaderboardRank(String currencyId, UUID accountId) throws SQLException {
        List<AccountRecord> records = new java.util.ArrayList<>(loadAll());
        records.sort(java.util.Comparator
                .comparing((AccountRecord record) -> record.getBalance(currencyId)).reversed()
                .thenComparing(AccountRecord::getLastKnownName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(AccountRecord::getId));
        for (int i = 0; i < records.size(); i++) {
            if (records.get(i).getId().equals(accountId)) return i + 1;
        }
        return -1;
    }

    void upsertBatch(Collection<AccountRecord> records) throws SQLException;

    void delete(UUID accountId) throws SQLException;

    void close() throws SQLException;
}
