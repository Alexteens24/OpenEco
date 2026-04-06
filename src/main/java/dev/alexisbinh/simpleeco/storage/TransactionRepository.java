package dev.alexisbinh.simpleeco.storage;

import dev.alexisbinh.simpleeco.model.TransactionEntry;
import dev.alexisbinh.simpleeco.model.TransactionType;
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

    List<TransactionEntry> getTransactions(UUID targetId, int limit, int offset,
            @Nullable TransactionType type, long fromMs, long toMs) throws SQLException;

        default List<TransactionEntry> getTransactions(UUID targetId, int limit, int offset,
                        @Nullable TransactionType type, long fromMs, long toMs, @Nullable String currencyId) throws SQLException {
                return getTransactions(targetId, limit, offset, type, fromMs, toMs);
        }

    int countTransactions(UUID targetId) throws SQLException;

        default int countTransactions(UUID targetId, @Nullable String currencyId) throws SQLException {
                return countTransactions(targetId, null, 0L, Long.MAX_VALUE, currencyId);
        }

    int countTransactions(UUID targetId, @Nullable TransactionType type,
            long fromMs, long toMs) throws SQLException;

        default int countTransactions(UUID targetId, @Nullable TransactionType type,
                        long fromMs, long toMs, @Nullable String currencyId) throws SQLException {
                return countTransactions(targetId, type, fromMs, toMs);
        }

    /**
     * Deletes all transactions with {@code ts < cutoffMs}.
     *
     * @return number of rows deleted
     */
    int pruneTransactions(long cutoffMs) throws SQLException;
}
