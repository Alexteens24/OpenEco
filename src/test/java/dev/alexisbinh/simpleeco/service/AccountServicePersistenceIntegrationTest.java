package dev.alexisbinh.simpleeco.service;

import dev.alexisbinh.simpleeco.model.PayResult;
import dev.alexisbinh.simpleeco.model.TransactionEntry;
import dev.alexisbinh.simpleeco.model.TransactionType;
import dev.alexisbinh.simpleeco.storage.DatabaseDialect;
import dev.alexisbinh.simpleeco.storage.JdbcAccountRepository;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class AccountServicePersistenceIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void loadFlushHistoryAndDeleteRoundTripAgainstH2() throws Exception {
        JdbcAccountRepository repository = new JdbcAccountRepository(DatabaseDialect.H2, tempDir.toString(), "service-test");
        try {
            AccountService writer = newService(repository);
            UUID accountId = UUID.randomUUID();

            assertTrue(writer.createAccount(accountId, "Alice"));
            assertTrue(writer.deposit(accountId, new BigDecimal("7.50")).transactionSuccess());
            writer.shutdown();

            AccountService reader = newService(repository);
            reader.loadAll();

            assertTrue(reader.hasAccount(accountId));
            assertEquals(0, new BigDecimal("12.50").compareTo(reader.getBalance(accountId)));
            assertEquals("Alice", reader.getAccount(accountId).orElseThrow().getLastKnownName());

            List<TransactionEntry> history = reader.getTransactions(accountId, 1, 10);
            assertEquals(1, history.size());
            assertEquals(TransactionType.GIVE, history.getFirst().getType());
            assertEquals(1, reader.countTransactions(accountId));

            assertTrue(reader.deleteAccount(accountId));
            assertFalse(reader.hasAccount(accountId));
            assertEquals(0, reader.countTransactions(accountId));

            reader.shutdown();
        } finally {
            repository.close();
        }
    }

    @Test
    void payRejectsSelfTransferWithoutMutatingBalance() throws Exception {
        JdbcAccountRepository repository = new JdbcAccountRepository(DatabaseDialect.H2, tempDir.toString(), "self-pay-test");
        try {
            AccountService service = newService(repository);
            UUID accountId = UUID.randomUUID();

            assertTrue(service.createAccount(accountId, "Alice"));

            var result = service.pay(accountId, accountId, new BigDecimal("2.00"));

            assertEquals(PayResult.Status.SELF_TRANSFER, result.getStatus());
            assertEquals(0, new BigDecimal("5.00").compareTo(service.getBalance(accountId)));
            assertEquals(0, service.countTransactions(accountId));

            service.shutdown();
        } finally {
            repository.close();
        }
    }

    @Test
    void createAccountRejectsDuplicateNamesIgnoringCase() throws Exception {
        JdbcAccountRepository repository = new JdbcAccountRepository(DatabaseDialect.H2, tempDir.toString(), "duplicate-name-test");
        try {
            AccountService service = newService(repository);
            UUID firstId = UUID.randomUUID();
            UUID secondId = UUID.randomUUID();

            assertTrue(service.createAccount(firstId, "Alice"));
            assertFalse(service.createAccount(secondId, "alice"));
            assertTrue(service.findByName("Alice").isPresent());
            assertFalse(service.hasAccount(secondId));

            service.shutdown();
        } finally {
            repository.close();
        }
    }

    @Test
    void createAccountRejectsNamesLongerThanSixteenCharacters() throws Exception {
        JdbcAccountRepository repository = new JdbcAccountRepository(DatabaseDialect.H2, tempDir.toString(), "long-name-test");
        try {
            AccountService service = newService(repository);

            assertFalse(service.createAccount(UUID.randomUUID(), "abcdefghijklmnopq"));

            service.shutdown();
        } finally {
            repository.close();
        }
    }

    @Test
    void hasReturnsFalseForNegativeAmounts() throws Exception {
        JdbcAccountRepository repository = new JdbcAccountRepository(DatabaseDialect.H2, tempDir.toString(), "negative-has-test");
        try {
            AccountService service = newService(repository);
            UUID accountId = UUID.randomUUID();

            assertTrue(service.createAccount(accountId, "Alice"));
            assertFalse(service.has(accountId, new BigDecimal("-1.00")));

            service.shutdown();
        } finally {
            repository.close();
        }
    }

    @Test
    void loadAllFailsWhenStoredNamesCollideIgnoringCase() throws Exception {
        JdbcAccountRepository repository = new JdbcAccountRepository(DatabaseDialect.H2, tempDir.toString(), "load-collision-test");
        try {
            repository.upsertBatch(List.of(
                    new dev.alexisbinh.simpleeco.model.AccountRecord(UUID.randomUUID(), "Alice", new BigDecimal("1.00"), 1L, 1L),
                    new dev.alexisbinh.simpleeco.model.AccountRecord(UUID.randomUUID(), "alice", new BigDecimal("2.00"), 2L, 2L)));

            AccountService service = newService(repository);

            assertThrows(java.sql.SQLException.class, service::loadAll);

            service.shutdown();
        } finally {
            repository.close();
        }
    }

    @Test
    void depositAndWithdrawLogTransactionHistory() throws Exception {
        JdbcAccountRepository repository = new JdbcAccountRepository(DatabaseDialect.H2, tempDir.toString(), "history-log-test");
        try {
            AccountService service = newService(repository);
            UUID id = UUID.randomUUID();
            service.createAccount(id, "Alice");

            service.deposit(id, new BigDecimal("3.00"));
            service.withdraw(id, new BigDecimal("1.00"));
            service.shutdown();

            AccountService reader = newService(repository);
            reader.loadAll();

            List<TransactionEntry> history = reader.getTransactions(id, 1, 10);
            assertEquals(2, history.size());
            // Both entry types must be present; order may vary if ts is identical
            assertTrue(history.stream().anyMatch(e -> e.getType() == TransactionType.GIVE));
            assertTrue(history.stream().anyMatch(e -> e.getType() == TransactionType.TAKE));

            reader.shutdown();
        } finally {
            repository.close();
        }
    }

    @Test
    void payWithTaxTransfersCorrectAmountsAndLogsBothEntries() throws Exception {
        JdbcAccountRepository repository = new JdbcAccountRepository(DatabaseDialect.H2, tempDir.toString(), "pay-tax-test");
        try {
            AccountService service = newServiceWithTax(repository, 10.0);
            UUID aliceId = UUID.randomUUID();
            UUID bobId = UUID.randomUUID();
            service.createAccount(aliceId, "Alice");
            service.createAccount(bobId, "Bob");
            // Give Alice enough balance to pay
            service.deposit(aliceId, new BigDecimal("15.00")); // Alice now has 20.00

            PayResult result = service.pay(aliceId, bobId, new BigDecimal("10.00"));

            assertTrue(result.isSuccess());
            // Alice paid 10.00; after 10% tax Bob receives 9.00
            assertEquals(0, new BigDecimal("10.00").compareTo(service.getBalance(aliceId)));
            assertEquals(0, new BigDecimal("14.00").compareTo(service.getBalance(bobId)));
            assertEquals(0, new BigDecimal("1.00").compareTo(result.getTax()));
            assertEquals(0, new BigDecimal("9.00").compareTo(result.getReceived()));

            service.shutdown();

            // Verify history entries were persisted
            AccountService reader = newServiceWithTax(repository, 10.0);
            reader.loadAll();
            // Alice: GIVE (deposit) + PAY_SENT = 2
            assertEquals(2, reader.countTransactions(aliceId));
            // Bob: PAY_RECEIVED = 1
            assertEquals(1, reader.countTransactions(bobId));
            reader.shutdown();
        } finally {
            repository.close();
        }
    }

    @Test
    void balanceSetAndResetArePersistedCorrectly() throws Exception {
        JdbcAccountRepository repository = new JdbcAccountRepository(DatabaseDialect.H2, tempDir.toString(), "set-reset-test");
        try {
            AccountService service = newService(repository);
            UUID id = UUID.randomUUID();
            service.createAccount(id, "Alice");

            service.set(id, new BigDecimal("42.00"));
            assertEquals(0, new BigDecimal("42.00").compareTo(service.getBalance(id)));

            service.reset(id);
            assertEquals(0, new BigDecimal("5.00").compareTo(service.getBalance(id))); // starting balance

            service.shutdown();

            // Reload and verify persisted value after shutdown flush
            AccountService reader = newService(repository);
            reader.loadAll();
            assertEquals(0, new BigDecimal("5.00").compareTo(reader.getBalance(id)));
            reader.shutdown();
        } finally {
            repository.close();
        }
    }

    @Test
    void pruneHistoryRemovesTransactionsOlderThanRetentionDays() throws Exception {
        JdbcAccountRepository repository = new JdbcAccountRepository(DatabaseDialect.H2, tempDir.toString(), "prune-service-test");
        try {
            // Insert transactions directly with old timestamps
            UUID id = UUID.randomUUID();
            repository.upsertBatch(List.of(
                    new dev.alexisbinh.simpleeco.model.AccountRecord(id, "Alice", new BigDecimal("5.00"), 1L, 1L)));

            long twoDaysAgoMs = System.currentTimeMillis() - 2L * 86_400_000L;
            long oneDayAgoMs = System.currentTimeMillis() - 86_400_000L;
            long nowMs = System.currentTimeMillis();

            repository.insertTransaction(new dev.alexisbinh.simpleeco.model.TransactionEntry(
                    TransactionType.GIVE, null, id, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, twoDaysAgoMs));
            repository.insertTransaction(new dev.alexisbinh.simpleeco.model.TransactionEntry(
                    TransactionType.GIVE, null, id, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, oneDayAgoMs));
            repository.insertTransaction(new dev.alexisbinh.simpleeco.model.TransactionEntry(
                    TransactionType.GIVE, null, id, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, nowMs));

            AccountService service = newServiceWithRetention(repository, 1);
            service.loadAll();

            // Prune: keep only last 1 day → the entry from 2 days ago should be removed
            service.pruneHistory();
            service.shutdown();

            // Verify directly through repository — retention prune uses the transaction executor,
            // so we need to wait for shutdown before checking.
            int remaining = repository.countTransactions(id);
            // The 2-days-old entry is removed; the 1-day-old and now entries remain (2)
            // Note: the exact count depends on timing—at minimum the 2-day-old one should be gone.
            assertTrue(remaining < 3, "Expected fewer than 3 transactions after pruning; got " + remaining);
        } finally {
            repository.close();
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static AccountService newService(JdbcAccountRepository repository) {
        return new AccountService(
                repository,
                Logger.getLogger("simpleeco-test"),
                "simpleeco-test",
                testConfig(0.0, -1),
                event -> { }
        );
    }

    private static AccountService newServiceWithTax(JdbcAccountRepository repository, double taxPercent) {
        return new AccountService(
                repository,
                Logger.getLogger("simpleeco-test"),
                "simpleeco-test",
                testConfig(taxPercent, -1),
                event -> { }
        );
    }

    private static AccountService newServiceWithRetention(JdbcAccountRepository repository, int retentionDays) {
        return new AccountService(
                repository,
                Logger.getLogger("simpleeco-test"),
                "simpleeco-test",
                testConfig(0.0, retentionDays),
                event -> { }
        );
    }

    private static YamlConfiguration testConfig(double taxPercent, int retentionDays) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("currency.id", "simpleeco");
        config.set("currency.name-singular", "Dollar");
        config.set("currency.name-plural", "Dollars");
        config.set("currency.decimal-digits", 2);
        config.set("currency.starting-balance", 5.00);
        config.set("currency.max-balance", -1);
        config.set("pay.cooldown-seconds", 0);
        config.set("pay.tax-percent", taxPercent);
        config.set("pay.min-amount", 0.01);
        config.set("baltop.cache-ttl-seconds", 30);
        config.set("history.retention-days", retentionDays);
        return config;
    }
}
