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
