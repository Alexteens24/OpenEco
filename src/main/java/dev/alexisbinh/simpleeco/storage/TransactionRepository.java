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

    List<TransactionEntry> getTransactions(UUID targetId, int limit, int offset,
            @Nullable TransactionType type, long fromMs, long toMs) throws SQLException;

    int countTransactions(UUID targetId) throws SQLException;

    int countTransactions(UUID targetId, @Nullable TransactionType type,
            long fromMs, long toMs) throws SQLException;

    /**
     * Deletes all transactions with {@code ts < cutoffMs}.
     *
     * @return number of rows deleted
     */
    int pruneTransactions(long cutoffMs) throws SQLException;
}
