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

import dev.alexisbinh.openeco.model.TransactionEntry;
import dev.alexisbinh.openeco.model.TransactionType;
import org.jetbrains.annotations.Nullable;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

public interface TransactionRepository {

    void insertTransaction(TransactionEntry entry) throws SQLException;

    List<TransactionEntry> getTransactions(UUID targetId, int limit, int offset) throws SQLException;

    default List<TransactionEntry> getTransactions(UUID targetId, int limit, int offset,
            @Nullable String currencyId) throws SQLException {
        return getTransactions(targetId, limit, offset, null, 0L, Long.MAX_VALUE, currencyId);
    }

    default List<TransactionEntry> getTransactions(UUID targetId, int limit, int offset,
            @Nullable TransactionType type, long fromMs, long toMs) throws SQLException {
        return getTransactions(targetId, limit, offset, type, fromMs, toMs, null);
    }

    List<TransactionEntry> getTransactions(UUID targetId, int limit, int offset,
            @Nullable TransactionType type, long fromMs, long toMs, @Nullable String currencyId) throws SQLException;

    int countTransactions(UUID targetId) throws SQLException;

    default int countTransactions(UUID targetId, @Nullable String currencyId) throws SQLException {
        return countTransactions(targetId, null, 0L, Long.MAX_VALUE, currencyId);
    }

    default int countTransactions(UUID targetId, @Nullable TransactionType type,
            long fromMs, long toMs) throws SQLException {
        return countTransactions(targetId, type, fromMs, toMs, null);
    }

    int countTransactions(UUID targetId, @Nullable TransactionType type,
            long fromMs, long toMs, @Nullable String currencyId) throws SQLException;

    /**
     * Deletes all transactions with {@code ts < cutoffMs}.
     *
     * @return number of rows deleted
     */
    int pruneTransactions(long cutoffMs) throws SQLException;
}
