package dev.alexisbinh.openeco.storage;

import dev.alexisbinh.openeco.model.AccountRecord;
import dev.alexisbinh.openeco.model.TransactionEntry;
import dev.alexisbinh.openeco.model.TransactionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JdbcAccountRepositoryIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void upsertLoadHistoryAndDeleteWorkAgainstH2() throws Exception {
        JdbcAccountRepository repository = new JdbcAccountRepository(DatabaseDialect.H2, tempDir.toString(), "economy-test");
        try {
            UUID accountId = UUID.randomUUID();
            AccountRecord account = new AccountRecord(accountId, "Alice", new BigDecimal("10.00"), 100L, 200L);
            TransactionEntry transaction = new TransactionEntry(
                    TransactionType.GIVE,
                    null,
                    accountId,
                    new BigDecimal("5.00"),
                    new BigDecimal("10.00"),
                    new BigDecimal("15.00"),
                    300L);

            repository.upsertBatch(List.of(account));
            repository.insertTransaction(transaction);

            List<AccountRecord> loadedAccounts = repository.loadAll();
            assertEquals(1, loadedAccounts.size());
            assertEquals("Alice", loadedAccounts.getFirst().getLastKnownName());
            assertEquals(0, new BigDecimal("10.00").compareTo(loadedAccounts.getFirst().getBalance()));

            List<TransactionEntry> history = repository.getTransactions(accountId, 10, 0);
            assertEquals(1, history.size());
            assertEquals(TransactionType.GIVE, history.getFirst().getType());
            assertEquals(1, repository.countTransactions(accountId));

            repository.delete(accountId);

            assertTrue(repository.loadAll().isEmpty());
            assertEquals(0, repository.countTransactions(accountId));
        } finally {
            repository.close();
        }
    }

    @Test
    void pruneTransactionsDeletesEntriesOlderThanCutoff() throws Exception {
        JdbcAccountRepository repository = new JdbcAccountRepository(DatabaseDialect.H2, tempDir.toString(), "prune-test");
        try {
            UUID accountId = UUID.randomUUID();
            repository.upsertBatch(List.of(new AccountRecord(accountId, "Alice", BigDecimal.ZERO, 1L, 1L)));

            long old = 1_000L;
            long recent = System.currentTimeMillis();
            long cutoff = recent - 1_000L; // everything before 1 second ago is "old"

            repository.insertTransaction(txAt(accountId, old));
            repository.insertTransaction(txAt(accountId, recent));

            int deleted = repository.pruneTransactions(cutoff);

            assertEquals(1, deleted);
            List<TransactionEntry> remaining = repository.getTransactions(accountId, 10, 0);
            assertEquals(1, remaining.size());
            assertEquals(recent, remaining.getFirst().getTimestamp());
        } finally {
            repository.close();
        }
    }

    @Test
    void pruneTransactionsReturnsZeroWhenNothingMatches() throws Exception {
        JdbcAccountRepository repository = new JdbcAccountRepository(DatabaseDialect.H2, tempDir.toString(), "prune-empty-test");
        try {
            UUID accountId = UUID.randomUUID();
            repository.upsertBatch(List.of(new AccountRecord(accountId, "Bob", BigDecimal.ZERO, 1L, 1L)));
            repository.insertTransaction(txAt(accountId, System.currentTimeMillis()));

            int deleted = repository.pruneTransactions(1L); // cutoff = epoch start, nothing matches

            assertEquals(0, deleted);
            assertEquals(1, repository.countTransactions(accountId));
        } finally {
            repository.close();
        }
    }

    @Test
    void pruneTransactionsDeletesAllEntriesWhenCutoffIsInFuture() throws Exception {
        JdbcAccountRepository repository = new JdbcAccountRepository(DatabaseDialect.H2, tempDir.toString(), "prune-all-test");
        try {
            UUID accountId = UUID.randomUUID();
            repository.upsertBatch(List.of(new AccountRecord(accountId, "Carol", BigDecimal.ZERO, 1L, 1L)));
            repository.insertTransaction(txAt(accountId, 1_000L));
            repository.insertTransaction(txAt(accountId, 2_000L));
            repository.insertTransaction(txAt(accountId, 3_000L));

            int deleted = repository.pruneTransactions(Long.MAX_VALUE);

            assertEquals(3, deleted);
            assertEquals(0, repository.countTransactions(accountId));
        } finally {
            repository.close();
        }
    }

    @Test
    void historyPaginationReturnsCorrectSlice() throws Exception {
        JdbcAccountRepository repository = new JdbcAccountRepository(DatabaseDialect.H2, tempDir.toString(), "pagination-test");
        try {
            UUID accountId = UUID.randomUUID();
            repository.upsertBatch(List.of(new AccountRecord(accountId, "Dave", BigDecimal.ZERO, 1L, 1L)));

            for (int i = 1; i <= 5; i++) {
                repository.insertTransaction(txAt(accountId, (long) i * 1_000L));
            }

            // Page size 2: page 1 → the 2 newest entries (ts=5000, ts=4000)
            List<TransactionEntry> page1 = repository.getTransactions(accountId, 2, 0);
            assertEquals(2, page1.size());
            assertEquals(5_000L, page1.get(0).getTimestamp());
            assertEquals(4_000L, page1.get(1).getTimestamp());

            // Page 2: offset 2 → ts=3000, ts=2000
            List<TransactionEntry> page2 = repository.getTransactions(accountId, 2, 2);
            assertEquals(2, page2.size());
            assertEquals(3_000L, page2.get(0).getTimestamp());

            // Offset past all entries → empty
            List<TransactionEntry> empty = repository.getTransactions(accountId, 2, 100);
            assertTrue(empty.isEmpty());
        } finally {
            repository.close();
        }
    }

    @Test
    void loadAllPreservesUpdatedAtWhenPersistedBalancesExist() throws Exception {
        JdbcAccountRepository repository = new JdbcAccountRepository(DatabaseDialect.H2, tempDir.toString(), "updated-at-load-test");
        try {
            UUID accountId = UUID.randomUUID();
            AccountRecord account = new AccountRecord(
                    accountId,
                    "Eli",
                    "openeco",
                    java.util.Map.of("openeco", new BigDecimal("10.00"), "gems", new BigDecimal("3")),
                    100L,
                    200L);
            account.setFrozen(true);

            repository.upsertBatch(List.of(account));

            AccountRecord loaded = repository.loadAll().getFirst();
            assertEquals(200L, loaded.getUpdatedAt());
            assertTrue(loaded.isFrozen());
            assertEquals(0, new BigDecimal("10.00").compareTo(loaded.getBalance("openeco")));
            assertEquals(0, new BigDecimal("3").compareTo(loaded.getBalance("gems")));
        } finally {
            repository.close();
        }
    }

    @Test
    void historyCurrencyFiltersIgnoreStoredCase() throws Exception {
        JdbcAccountRepository repository = new JdbcAccountRepository(DatabaseDialect.H2, tempDir.toString(), "history-case-filter-test");
        try {
            UUID accountId = UUID.randomUUID();
            repository.upsertBatch(List.of(new AccountRecord(accountId, "Finn", BigDecimal.ZERO, 1L, 1L)));
            repository.insertTransaction(new TransactionEntry(
                    TransactionType.GIVE,
                    null,
                    accountId,
                    BigDecimal.ONE,
                    BigDecimal.ZERO,
                    BigDecimal.ONE,
                    1_000L,
                    null,
                    null,
                    "gems"));

            assertEquals(1, repository.countTransactions(accountId, "GEMS"));
            assertEquals(1, repository.getTransactions(accountId, 10, 0, "GEMS").size());
        } finally {
            repository.close();
        }
    }

    @Test
    void insertTransactionMakesSameMillisecondEntriesMonotonicPerAccount() throws Exception {
        JdbcAccountRepository repository = new JdbcAccountRepository(DatabaseDialect.H2, tempDir.toString(), "history-monotonic-ts-test");
        try {
            UUID accountId = UUID.randomUUID();
            repository.upsertBatch(List.of(new AccountRecord(accountId, "Gina", BigDecimal.ZERO, 1L, 1L)));

            repository.insertTransaction(txAt(accountId, 1_000L, TransactionType.GIVE));
            repository.insertTransaction(txAt(accountId, 1_000L, TransactionType.TAKE));
            repository.insertTransaction(txAt(accountId, 1_000L, TransactionType.RESET));

            List<Long> timestamps = repository.getTransactions(accountId, 10, 0).stream()
                    .map(TransactionEntry::getTimestamp)
                    .toList();

            assertEquals(List.of(1_002L, 1_001L, 1_000L), timestamps);
            assertEquals(List.of(1_002L, 1_001L), repository.getTransactions(accountId, 2, 0).stream()
                    .map(TransactionEntry::getTimestamp)
                    .toList());
            assertEquals(List.of(1_000L), repository.getTransactions(accountId, 2, 2).stream()
                    .map(TransactionEntry::getTimestamp)
                    .toList());
        } finally {
            repository.close();
        }
    }

    @Test
    void upsertBatchOnNoOpIsIdempotent() throws Exception {
        JdbcAccountRepository repository = new JdbcAccountRepository(DatabaseDialect.H2, tempDir.toString(), "upsert-empty-test");
        try {
            // Should not throw even with empty collection
            repository.upsertBatch(List.of());
            assertTrue(repository.loadAll().isEmpty());
        } finally {
            repository.close();
        }
    }

    private static TransactionEntry txAt(UUID accountId, long ts) {
        return new TransactionEntry(TransactionType.GIVE, null, accountId,
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, ts);
    }

    private static TransactionEntry txAt(UUID accountId, long ts, TransactionType type) {
        return new TransactionEntry(type, null, accountId,
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, ts);
    }

    @Test
    void filteredGetTransactionsByType() throws Exception {
        JdbcAccountRepository repository = new JdbcAccountRepository(DatabaseDialect.H2, tempDir.toString(), "filter-type-test");
        try {
            UUID accountId = UUID.randomUUID();
            repository.upsertBatch(List.of(new AccountRecord(accountId, "Eve", BigDecimal.ZERO, 1L, 1L)));

            repository.insertTransaction(txAt(accountId, 1000L, TransactionType.GIVE));
            repository.insertTransaction(txAt(accountId, 2000L, TransactionType.TAKE));
            repository.insertTransaction(txAt(accountId, 3000L, TransactionType.GIVE));

            List<TransactionEntry> onlyGive = repository.getTransactions(
                    accountId, 10, 0, TransactionType.GIVE, 0, Long.MAX_VALUE);
            assertEquals(2, onlyGive.size());
            assertTrue(onlyGive.stream().allMatch(e -> e.getType() == TransactionType.GIVE));

            int countGive = repository.countTransactions(accountId, TransactionType.GIVE, 0, Long.MAX_VALUE);
            assertEquals(2, countGive);

            int countTake = repository.countTransactions(accountId, TransactionType.TAKE, 0, Long.MAX_VALUE);
            assertEquals(1, countTake);
        } finally {
            repository.close();
        }
    }

    @Test
    void filteredGetTransactionsByTimeRange() throws Exception {
        JdbcAccountRepository repository = new JdbcAccountRepository(DatabaseDialect.H2, tempDir.toString(), "filter-time-test");
        try {
            UUID accountId = UUID.randomUUID();
            repository.upsertBatch(List.of(new AccountRecord(accountId, "Frank", BigDecimal.ZERO, 1L, 1L)));

            repository.insertTransaction(txAt(accountId, 1000L));
            repository.insertTransaction(txAt(accountId, 2000L));
            repository.insertTransaction(txAt(accountId, 3000L));
            repository.insertTransaction(txAt(accountId, 4000L));

            List<TransactionEntry> range = repository.getTransactions(
                    accountId, 10, 0, null, 2000L, 3000L);
            assertEquals(2, range.size());
            assertTrue(range.stream().allMatch(e -> e.getTimestamp() >= 2000L && e.getTimestamp() <= 3000L));

            int count = repository.countTransactions(accountId, null, 2000L, 3000L);
            assertEquals(2, count);
        } finally {
            repository.close();
        }
    }

    @Test
    void filteredGetTransactionsWithNoFilterReturnsAll() throws Exception {
        JdbcAccountRepository repository = new JdbcAccountRepository(DatabaseDialect.H2, tempDir.toString(), "filter-none-test");
        try {
            UUID accountId = UUID.randomUUID();
            repository.upsertBatch(List.of(new AccountRecord(accountId, "Grace", BigDecimal.ZERO, 1L, 1L)));

            repository.insertTransaction(txAt(accountId, 1000L, TransactionType.GIVE));
            repository.insertTransaction(txAt(accountId, 2000L, TransactionType.TAKE));

            List<TransactionEntry> all = repository.getTransactions(
                    accountId, 10, 0, null, 0, Long.MAX_VALUE);
            assertEquals(2, all.size());

            int count = repository.countTransactions(accountId, null, 0, Long.MAX_VALUE);
            assertEquals(2, count);
        } finally {
            repository.close();
        }
    }

    @Test
    void metadataRoundTripsThroughHistoryStorage() throws Exception {
        JdbcAccountRepository repository = new JdbcAccountRepository(DatabaseDialect.H2, tempDir.toString(), "metadata-roundtrip-test");
        try {
            UUID accountId = UUID.randomUUID();
            repository.upsertBatch(List.of(new AccountRecord(accountId, "Helen", BigDecimal.ZERO, 1L, 1L)));
            repository.insertTransaction(new TransactionEntry(
                    TransactionType.GIVE,
                    null,
                    accountId,
                    BigDecimal.ONE,
                    BigDecimal.ZERO,
                    BigDecimal.ONE,
                    1_000L,
                    "QuestAddon",
                    "Daily reward"));

            TransactionEntry stored = repository.getTransactions(accountId, 10, 0).getFirst();

            assertEquals("QuestAddon", stored.getSource());
            assertEquals("Daily reward", stored.getNote());
        } finally {
            repository.close();
        }
    }

    @Test
    void existingTransactionsTableIsUpgradedWithMetadataColumns() throws Exception {
        String filename = "legacy-metadata-schema-test";
        try (Connection connection = DriverManager.getConnection(DatabaseDialect.H2.getJdbcUrl(tempDir.toString(), filename));
             Statement stmt = connection.createStatement()) {
            stmt.execute("""
                CREATE TABLE accounts (
                    id         VARCHAR(36)   NOT NULL PRIMARY KEY,
                    name       VARCHAR(16)   NOT NULL,
                    balance    DECIMAL(30,8) NOT NULL DEFAULT 0,
                    created_at BIGINT        NOT NULL,
                    updated_at BIGINT        NOT NULL
                )
                """);
            stmt.execute("""
                CREATE TABLE transactions (
                    type           VARCHAR(16)   NOT NULL,
                    counterpart_id VARCHAR(36),
                    target_id      VARCHAR(36)   NOT NULL,
                    amount         DECIMAL(30,8) NOT NULL,
                    balance_before DECIMAL(30,8) NOT NULL,
                    balance_after  DECIMAL(30,8) NOT NULL,
                    ts             BIGINT        NOT NULL
                )
                """);
        }

        JdbcAccountRepository repository = new JdbcAccountRepository(DatabaseDialect.H2, tempDir.toString(), filename);
        try {
            UUID accountId = UUID.randomUUID();
            repository.upsertBatch(List.of(new AccountRecord(accountId, "Iris", BigDecimal.ZERO, 1L, 1L)));
            repository.insertTransaction(new TransactionEntry(
                    TransactionType.GIVE,
                    null,
                    accountId,
                    BigDecimal.ONE,
                    BigDecimal.ZERO,
                    BigDecimal.ONE,
                    2_000L,
                    "QuestAddon",
                    "Migrated schema still stores metadata"));

            TransactionEntry stored = repository.getTransactions(accountId, 10, 0).getFirst();

            assertEquals("QuestAddon", stored.getSource());
            assertEquals("Migrated schema still stores metadata", stored.getNote());
        } finally {
            repository.close();
        }
    }

    @Test
    void existingSqliteTransactionsTableIsUpgradedWithMetadataColumns() throws Exception {
        String filename = "legacy-sqlite-metadata-schema-test.db";
        try (Connection connection = DriverManager.getConnection(DatabaseDialect.SQLITE.getJdbcUrl(tempDir.toString(), filename));
             Statement stmt = connection.createStatement()) {
            stmt.execute("""
                CREATE TABLE accounts (
                    id         VARCHAR(36)   NOT NULL PRIMARY KEY,
                    name       VARCHAR(16)   NOT NULL,
                    balance    DECIMAL(30,8) NOT NULL DEFAULT 0,
                    created_at BIGINT        NOT NULL,
                    updated_at BIGINT        NOT NULL
                )
                """);
            stmt.execute("""
                CREATE TABLE transactions (
                    type           VARCHAR(16)   NOT NULL,
                    counterpart_id VARCHAR(36),
                    target_id      VARCHAR(36)   NOT NULL,
                    amount         DECIMAL(30,8) NOT NULL,
                    balance_before DECIMAL(30,8) NOT NULL,
                    balance_after  DECIMAL(30,8) NOT NULL,
                    ts             BIGINT        NOT NULL
                )
                """);
        }

        JdbcAccountRepository repository = new JdbcAccountRepository(DatabaseDialect.SQLITE, tempDir.toString(), filename);
        try {
            UUID accountId = UUID.randomUUID();
            repository.upsertBatch(List.of(new AccountRecord(accountId, "Judy", BigDecimal.ZERO, 1L, 1L)));
            repository.insertTransaction(new TransactionEntry(
                    TransactionType.GIVE,
                    null,
                    accountId,
                    BigDecimal.ONE,
                    BigDecimal.ZERO,
                    BigDecimal.ONE,
                    2_500L,
                    "QuestAddon",
                    "SQLite schema still stores metadata"));

            TransactionEntry stored = repository.getTransactions(accountId, 10, 0).getFirst();

            assertEquals("QuestAddon", stored.getSource());
            assertEquals("SQLite schema still stores metadata", stored.getNote());
        } finally {
            repository.close();
        }
    }

    @Test
    void legacyH2SchemaBackfillsDefaultCurrencyBalancesAndHistory() throws Exception {
        String filename = "legacy-h2-currency-backfill-test";
        UUID accountId = UUID.randomUUID();
        try (Connection connection = DriverManager.getConnection(DatabaseDialect.H2.getJdbcUrl(tempDir.toString(), filename));
             Statement stmt = connection.createStatement()) {
            stmt.execute("""
                CREATE TABLE accounts (
                    id         VARCHAR(36)   NOT NULL PRIMARY KEY,
                    name       VARCHAR(16)   NOT NULL,
                    balance    DECIMAL(30,8) NOT NULL DEFAULT 0,
                    created_at BIGINT        NOT NULL,
                    updated_at BIGINT        NOT NULL
                )
                """);
            stmt.execute("""
                CREATE TABLE transactions (
                    type           VARCHAR(16)   NOT NULL,
                    counterpart_id VARCHAR(36),
                    target_id      VARCHAR(36)   NOT NULL,
                    amount         DECIMAL(30,8) NOT NULL,
                    balance_before DECIMAL(30,8) NOT NULL,
                    balance_after  DECIMAL(30,8) NOT NULL,
                    ts             BIGINT        NOT NULL
                )
                """);
            stmt.execute("""
                INSERT INTO accounts(id,name,balance,created_at,updated_at)
                VALUES('%s','Alice',42.50,100,200)
                """.formatted(accountId));
            stmt.execute("""
                INSERT INTO transactions(type,counterpart_id,target_id,amount,balance_before,balance_after,ts)
                VALUES('GIVE',NULL,'%s',2.50,40.00,42.50,300)
                """.formatted(accountId));
        }

        JdbcAccountRepository repository = new JdbcAccountRepository(DatabaseDialect.H2, tempDir.toString(), filename, "gems");
        try {
            List<AccountRecord> loadedAccounts = repository.loadAll();
            assertEquals(1, loadedAccounts.size());
            assertEquals(0, new BigDecimal("42.50").compareTo(loadedAccounts.getFirst().getBalance()));

            try (Connection connection = DriverManager.getConnection(DatabaseDialect.H2.getJdbcUrl(tempDir.toString(), filename));
                 PreparedStatement balancePs = connection.prepareStatement(
                         "SELECT balance FROM account_balances WHERE account_id=? AND currency_id=?");
                 PreparedStatement currencyPs = connection.prepareStatement(
                         "SELECT currency_id FROM transactions WHERE target_id=?")) {
                balancePs.setString(1, accountId.toString());
                balancePs.setString(2, "gems");
                try (ResultSet balanceRs = balancePs.executeQuery()) {
                    assertTrue(balanceRs.next());
                    assertEquals(0, new BigDecimal("42.50").compareTo(balanceRs.getBigDecimal(1)));
                }

                currencyPs.setString(1, accountId.toString());
                try (ResultSet currencyRs = currencyPs.executeQuery()) {
                    assertTrue(currencyRs.next());
                    assertEquals("gems", currencyRs.getString(1));
                }
            }
        } finally {
            repository.close();
        }
    }

    @Test
    void legacySqliteSchemaBackfillsDefaultCurrencyBalancesAndHistory() throws Exception {
        String filename = "legacy-sqlite-currency-backfill-test.db";
        UUID accountId = UUID.randomUUID();
        try (Connection connection = DriverManager.getConnection(DatabaseDialect.SQLITE.getJdbcUrl(tempDir.toString(), filename));
             Statement stmt = connection.createStatement()) {
            stmt.execute("""
                CREATE TABLE accounts (
                    id         VARCHAR(36)   NOT NULL PRIMARY KEY,
                    name       VARCHAR(16)   NOT NULL,
                    balance    DECIMAL(30,8) NOT NULL DEFAULT 0,
                    created_at BIGINT        NOT NULL,
                    updated_at BIGINT        NOT NULL
                )
                """);
            stmt.execute("""
                CREATE TABLE transactions (
                    type           VARCHAR(16)   NOT NULL,
                    counterpart_id VARCHAR(36),
                    target_id      VARCHAR(36)   NOT NULL,
                    amount         DECIMAL(30,8) NOT NULL,
                    balance_before DECIMAL(30,8) NOT NULL,
                    balance_after  DECIMAL(30,8) NOT NULL,
                    ts             BIGINT        NOT NULL
                )
                """);
            stmt.execute("""
                INSERT INTO accounts(id,name,balance,created_at,updated_at)
                VALUES('%s','Alice',42.50,100,200)
                """.formatted(accountId));
            stmt.execute("""
                INSERT INTO transactions(type,counterpart_id,target_id,amount,balance_before,balance_after,ts)
                VALUES('GIVE',NULL,'%s',2.50,40.00,42.50,300)
                """.formatted(accountId));
        }

        JdbcAccountRepository repository = new JdbcAccountRepository(DatabaseDialect.SQLITE, tempDir.toString(), filename, "gems");
        try {
            List<AccountRecord> loadedAccounts = repository.loadAll();
            assertEquals(1, loadedAccounts.size());
            assertEquals(0, new BigDecimal("42.50").compareTo(loadedAccounts.getFirst().getBalance()));

            try (Connection connection = DriverManager.getConnection(DatabaseDialect.SQLITE.getJdbcUrl(tempDir.toString(), filename));
                 PreparedStatement balancePs = connection.prepareStatement(
                         "SELECT balance FROM account_balances WHERE account_id=? AND currency_id=?");
                 PreparedStatement currencyPs = connection.prepareStatement(
                         "SELECT currency_id FROM transactions WHERE target_id=?")) {
                balancePs.setString(1, accountId.toString());
                balancePs.setString(2, "gems");
                try (ResultSet balanceRs = balancePs.executeQuery()) {
                    assertTrue(balanceRs.next());
                    assertEquals(0, new BigDecimal("42.50").compareTo(balanceRs.getBigDecimal(1)));
                }

                currencyPs.setString(1, accountId.toString());
                try (ResultSet currencyRs = currencyPs.executeQuery()) {
                    assertTrue(currencyRs.next());
                    assertEquals("gems", currencyRs.getString(1));
                }
            }
        } finally {
            repository.close();
        }
    }
}
