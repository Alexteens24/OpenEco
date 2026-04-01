package dev.alexisbinh.simpleeco.storage;

import dev.alexisbinh.simpleeco.model.AccountRecord;

import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface AccountRepository extends TransactionRepository {

    List<AccountRecord> loadAll() throws SQLException;

    void upsertBatch(Collection<AccountRecord> records) throws SQLException;

    void delete(UUID accountId) throws SQLException;

    void close() throws SQLException;
}
