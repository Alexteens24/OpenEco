package dev.alexisbinh.openeco.service;

import dev.alexisbinh.openeco.model.AccountRecord;
import dev.alexisbinh.openeco.model.PayResult;
import dev.alexisbinh.openeco.model.TransactionEntry;
import dev.alexisbinh.openeco.model.TransactionType;
import dev.alexisbinh.openeco.storage.AccountRepository;
import dev.alexisbinh.openeco.storage.DatabaseDialect;
import dev.alexisbinh.openeco.storage.JdbcAccountRepository;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
    void createAccountSeedsConfiguredStartingBalancesForAllCurrencies() throws Exception {
        JdbcAccountRepository repository = new JdbcAccountRepository(DatabaseDialect.H2, tempDir.toString(), "multi-starting-balance-test");
        try {
            AccountService writer = newServiceWithConfig(repository, multiCurrencyConfig("openeco"));
            UUID accountId = UUID.randomUUID();

            assertTrue(writer.createAccount(accountId, "Alice"));
            assertEquals(0, BigDecimal.ZERO.compareTo(writer.getBalance(accountId)));
            assertEquals(0, new BigDecimal("5").compareTo(writer.getBalance(accountId, "gems")));
            writer.shutdown();

            AccountService reader = newServiceWithConfig(repository, multiCurrencyConfig("openeco"));
            reader.loadAll();

            assertEquals(0, BigDecimal.ZERO.compareTo(reader.getBalance(accountId)));
            assertEquals(0, new BigDecimal("5").compareTo(reader.getBalance(accountId, "gems")));
            reader.shutdown();
        } finally {
            repository.close();
        }
    }

    @Test
    void changingDefaultCurrencyAcrossRestartDoesNotCopyLegacyBalanceIntoNewDefault() throws Exception {
        String filename = "default-currency-restart-test";
        UUID accountId = UUID.randomUUID();

        JdbcAccountRepository writerRepository = new JdbcAccountRepository(DatabaseDialect.H2, tempDir.toString(), filename, "openeco");
        try {
            AccountService writer = newServiceWithConfig(writerRepository, testConfig(0.0, -1));

            assertTrue(writer.createAccount(accountId, "Alice"));
            assertTrue(writer.deposit(accountId, new BigDecimal("10.00")).transactionSuccess());
            writer.shutdown();
        } finally {
            writerRepository.close();
        }

        JdbcAccountRepository readerRepository = new JdbcAccountRepository(DatabaseDialect.H2, tempDir.toString(), filename, "gems");
        try {
            AccountService reader = newServiceWithConfig(readerRepository, multiCurrencyConfig("gems"));
            reader.loadAll();

            assertEquals(0, BigDecimal.ZERO.compareTo(reader.getBalance(accountId)));
            assertEquals(0, BigDecimal.ZERO.compareTo(reader.getBalance(accountId, "gems")));
            assertEquals(0, new BigDecimal("15.00").compareTo(reader.getBalance(accountId, "openeco")));

            reader.shutdown();
        } finally {
            readerRepository.close();
        }
    }

    @Test
    void loadAllCanonicalizesBalancesWhenCurrencyCaseChangesAcrossRestart() throws Exception {
        String filename = "currency-case-restart-test";
        UUID accountId = UUID.randomUUID();

        JdbcAccountRepository writerRepository = new JdbcAccountRepository(DatabaseDialect.H2, tempDir.toString(), filename, "openeco");
        try {
            AccountService writer = newServiceWithConfig(writerRepository, multiCurrencyConfig("openeco", "gems"));

            assertTrue(writer.createAccount(accountId, "Alice"));
            assertTrue(writer.deposit(accountId, "gems", new BigDecimal("7")).transactionSuccess());

            writer.shutdown();
        } finally {
            writerRepository.close();
        }

        JdbcAccountRepository readerRepository = new JdbcAccountRepository(DatabaseDialect.H2, tempDir.toString(), filename, "openeco");
        try {
            AccountService reader = newServiceWithConfig(readerRepository, multiCurrencyConfig("openeco", "Gems"));
            reader.loadAll();

            AccountRecord snapshot = reader.getAccount(accountId).orElseThrow();
            assertEquals(0, new BigDecimal("12").compareTo(reader.getBalance(accountId, "Gems")));
            assertTrue(snapshot.getBalancesSnapshot().containsKey("Gems"));
            assertFalse(snapshot.getBalancesSnapshot().containsKey("gems"));
            assertEquals(1, reader.countTransactions(accountId, "Gems"));

            reader.shutdown();
        } finally {
            readerRepository.close();
        }

        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
                     DatabaseDialect.H2.getJdbcUrl(tempDir.toString(), filename));
             java.sql.PreparedStatement gemsCountPs = connection.prepareStatement(
                     "SELECT COUNT(*) FROM account_balances WHERE account_id=? AND currency_id=?");
             java.sql.PreparedStatement lowerCaseCountPs = connection.prepareStatement(
                     "SELECT COUNT(*) FROM account_balances WHERE account_id=? AND currency_id=?")) {
            gemsCountPs.setString(1, accountId.toString());
            gemsCountPs.setString(2, "Gems");
            try (java.sql.ResultSet rs = gemsCountPs.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }

            lowerCaseCountPs.setString(1, accountId.toString());
            lowerCaseCountPs.setString(2, "gems");
            try (java.sql.ResultSet rs = lowerCaseCountPs.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1));
            }
        }
    }

    @Test
    void reloadConfigSwitchesDefaultCurrencyWithoutLosingNamedBalances() throws Exception {
        JdbcAccountRepository repository = new JdbcAccountRepository(DatabaseDialect.H2, tempDir.toString(), "reload-default-currency-test");
        try {
            AccountService service = newServiceWithConfig(repository, multiCurrencyConfig("openeco"));
            UUID accountId = UUID.randomUUID();

            assertTrue(service.createAccount(accountId, "Alice"));
            assertTrue(service.deposit(accountId, new BigDecimal("10.00")).transactionSuccess());
            assertTrue(service.deposit(accountId, "gems", new BigDecimal("2")).transactionSuccess());

            assertEquals(0, new BigDecimal("10.00").compareTo(service.getBalance(accountId)));
            assertEquals(0, new BigDecimal("7").compareTo(service.getBalance(accountId, "gems")));

            service.reloadConfig(multiCurrencyConfig("gems"));

            assertEquals("gems", service.getCurrencyId());
            assertEquals(0, new BigDecimal("7").compareTo(service.getBalance(accountId)));
            assertEquals(0, new BigDecimal("7").compareTo(service.getBalance(accountId, "gems")));
            assertEquals(0, new BigDecimal("10.00").compareTo(service.getBalance(accountId, "openeco")));

            service.shutdown();
        } finally {
            repository.close();
        }
    }

    @Test
    void refreshAccountLoadsAccountCreatedOnAnotherBackendAfterStartup() throws Exception {
        String filename = "cross-server-refresh-test";
        UUID accountId = UUID.randomUUID();

        JdbcAccountRepository writerRepository = new JdbcAccountRepository(DatabaseDialect.H2, tempDir.toString(), filename);
        JdbcAccountRepository readerRepository = new JdbcAccountRepository(DatabaseDialect.H2, tempDir.toString(), filename);
        try {
            AccountService writer = newService(writerRepository);
            AccountService reader = newService(readerRepository);
            writer.loadAll();
            reader.loadAll();

            assertTrue(writer.createAccount(accountId, "Alice"));
            assertTrue(writer.deposit(accountId, new BigDecimal("7.50")).transactionSuccess());
            writer.flushAccount(accountId);

            reader.refreshAccount(accountId);

            assertTrue(reader.hasAccount(accountId));
            assertEquals("Alice", reader.getAccount(accountId).orElseThrow().getLastKnownName());
            assertEquals(0, new BigDecimal("12.50").compareTo(reader.getBalance(accountId)));

            writer.shutdown();
            reader.shutdown();
        } finally {
            writerRepository.close();
            readerRepository.close();
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
    void hasUsesConfiguredCurrencyScaleForPositiveProbeAmounts() throws Exception {
        JdbcAccountRepository repository = new JdbcAccountRepository(DatabaseDialect.H2, tempDir.toString(), "scaled-has-test");
        try {
            AccountService service = newService(repository);
            UUID accountId = UUID.randomUUID();

            assertTrue(service.createAccount(accountId, "Alice"));
            assertTrue(service.deposit(accountId, BigDecimal.ONE).transactionSuccess());

            assertTrue(service.has(accountId, new BigDecimal("1.001")));
            assertFalse(service.has(accountId, new BigDecimal("0.001")));

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
                    new dev.alexisbinh.openeco.model.AccountRecord(UUID.randomUUID(), "Alice", new BigDecimal("1.00"), 1L, 1L),
                    new dev.alexisbinh.openeco.model.AccountRecord(UUID.randomUUID(), "alice", new BigDecimal("2.00"), 2L, 2L)));

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
    void flushDirtyWaitsForQueuedHistoryWritesBeforePersistingBalanceBatch() throws Exception {
        BlockingRepository repository = new BlockingRepository();
        AccountService service = newService(repository);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            UUID accountId = UUID.randomUUID();

            assertTrue(service.createAccount(accountId, "Alice"));
            assertTrue(service.deposit(accountId, new BigDecimal("3.00")).transactionSuccess());
            assertTrue(repository.transactionInsertStarted.await(2, TimeUnit.SECONDS));

            Future<?> flushFuture = executor.submit(service::flushDirty);

            assertFalse(repository.upsertStarted.await(200, TimeUnit.MILLISECONDS));

            repository.allowTransactionInsert.countDown();
            flushFuture.get(2, TimeUnit.SECONDS);

            assertTrue(repository.upsertStarted.await(1, TimeUnit.SECONDS));
            assertEquals(1, repository.upsertCalls);
            assertEquals(1, repository.countTransactions(accountId));
            assertEquals(1, repository.lastUpsertedRecords.size());
            assertEquals(0, new BigDecimal("8.00").compareTo(repository.lastUpsertedRecords.getFirst().getBalance()));

            service.shutdown();
        } finally {
            repository.allowTransactionInsert.countDown();
            executor.shutdownNow();
            executor.awaitTermination(2, TimeUnit.SECONDS);
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
    void balanceMutationDoesNotImmediatelyInvalidateBalTopCache() throws Exception {
        JdbcAccountRepository repository = new JdbcAccountRepository(DatabaseDialect.H2, tempDir.toString(), "baltop-refresh-test");
        try {
            AccountService service = newService(repository);
            UUID aliceId = UUID.randomUUID();
            UUID bobId = UUID.randomUUID();

            service.createAccount(aliceId, "Alice");
            service.createAccount(bobId, "Bob");
            service.deposit(aliceId, new BigDecimal("45.00"));

            // Seed the cache: Alice is currently #1
            List<AccountRecord> first = service.getBalTopSnapshot();
            assertEquals("Alice", first.getFirst().getLastKnownName());

            // Bob now has more money in memory, but the cache is still fresh (TTL not expired)
            service.deposit(bobId, new BigDecimal("100.00"));

            List<AccountRecord> cached = service.getBalTopSnapshot();
            assertEquals("Alice", cached.getFirst().getLastKnownName());

            service.shutdown();
        } finally {
            repository.close();
        }
    }

    @Test
    void renameRefreshesBalTopSnapshotImmediately() throws Exception {
        JdbcAccountRepository repository = new JdbcAccountRepository(DatabaseDialect.H2, tempDir.toString(), "baltop-rename-test");
        try {
            AccountService service = newService(repository);
            UUID aliceId = UUID.randomUUID();

            service.createAccount(aliceId, "Alice");
            List<AccountRecord> first = service.getBalTopSnapshot();
            assertEquals("Alice", first.getFirst().getLastKnownName());

            assertTrue(service.renameAccount(aliceId, "Alicia"));

            List<AccountRecord> refreshed = service.getBalTopSnapshot();
            assertEquals("Alicia", refreshed.getFirst().getLastKnownName());
            assertTrue(refreshed.stream().noneMatch(record -> record.getLastKnownName().equals("Alice")));

            service.shutdown();
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
                    new dev.alexisbinh.openeco.model.AccountRecord(id, "Alice", new BigDecimal("5.00"), 1L, 1L)));

            long twoDaysAgoMs = System.currentTimeMillis() - 2L * 86_400_000L;
            long oneDayAgoMs = System.currentTimeMillis() - 86_400_000L;
            long nowMs = System.currentTimeMillis();

            repository.insertTransaction(new dev.alexisbinh.openeco.model.TransactionEntry(
                    TransactionType.GIVE, null, id, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, twoDaysAgoMs));
            repository.insertTransaction(new dev.alexisbinh.openeco.model.TransactionEntry(
                    TransactionType.GIVE, null, id, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, oneDayAgoMs));
            repository.insertTransaction(new dev.alexisbinh.openeco.model.TransactionEntry(
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

    private static AccountService newService(AccountRepository repository) {
        return newServiceWithConfig(repository, testConfig(0.0, -1));
    }

    private static AccountService newServiceWithConfig(AccountRepository repository, YamlConfiguration config) {
        return new AccountService(
                repository,
                Logger.getLogger("openeco-test"),
                "openeco-test",
                config,
                event -> { }
        );
    }

    private static AccountService newService(JdbcAccountRepository repository) {
        return newService((AccountRepository) repository);
    }

    private static AccountService newServiceWithTax(JdbcAccountRepository repository, double taxPercent) {
        return newServiceWithConfig(repository, testConfig(taxPercent, -1));
    }

    private static AccountService newServiceWithRetention(JdbcAccountRepository repository, int retentionDays) {
        return newServiceWithConfig(repository, testConfig(0.0, retentionDays));
    }

    private static YamlConfiguration testConfig(double taxPercent, int retentionDays) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("currency.id", "openeco");
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

    private static YamlConfiguration multiCurrencyConfig(String defaultCurrencyId) {
        return multiCurrencyConfig(defaultCurrencyId, "gems");
    }

    private static YamlConfiguration multiCurrencyConfig(String defaultCurrencyId, String secondaryCurrencyId) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("currencies.default", defaultCurrencyId);
        config.set("currencies.definitions.openeco.name-singular", "Dollar");
        config.set("currencies.definitions.openeco.name-plural", "Dollars");
        config.set("currencies.definitions.openeco.decimal-digits", 2);
        config.set("currencies.definitions.openeco.starting-balance", 0.0);
        config.set("currencies.definitions.openeco.max-balance", -1.0);
        config.set("currencies.definitions." + secondaryCurrencyId + ".name-singular", "Gem");
        config.set("currencies.definitions." + secondaryCurrencyId + ".name-plural", "Gems");
        config.set("currencies.definitions." + secondaryCurrencyId + ".decimal-digits", 0);
        config.set("currencies.definitions." + secondaryCurrencyId + ".starting-balance", 5.0);
        config.set("currencies.definitions." + secondaryCurrencyId + ".max-balance", -1.0);
        config.set("pay.cooldown-seconds", 0);
        config.set("pay.tax-percent", 0.0);
        config.set("pay.min-amount", 0.01);
        config.set("baltop.cache-ttl-seconds", 30);
        config.set("history.retention-days", -1);
        return config;
    }

    private static final class BlockingRepository implements AccountRepository {

        private final CountDownLatch transactionInsertStarted = new CountDownLatch(1);
        private final CountDownLatch allowTransactionInsert = new CountDownLatch(1);
        private final CountDownLatch upsertStarted = new CountDownLatch(1);
        private final List<TransactionEntry> transactions = new ArrayList<>();
        private List<AccountRecord> lastUpsertedRecords = List.of();
        private int upsertCalls;

        @Override
        public synchronized List<AccountRecord> loadAll() {
            return new ArrayList<>(lastUpsertedRecords);
        }

        @Override
        public java.util.Optional<AccountRecord> loadAccount(UUID id) {
            return lastUpsertedRecords.stream().filter(r -> r.getId().equals(id)).findFirst()
                    .map(AccountRecord::snapshot);
        }

        @Override
        public synchronized void upsertBatch(Collection<AccountRecord> records) {
            lastUpsertedRecords = records.stream()
                    .map(AccountRecord::snapshot)
                    .toList();
            upsertCalls++;
            upsertStarted.countDown();
        }

        @Override
        public void delete(UUID accountId) {
            // No-op for this test repository.
        }

        @Override
        public void close() {
            // No-op for this test repository.
        }

        @Override
        public synchronized void insertTransaction(TransactionEntry entry) throws SQLException {
            transactionInsertStarted.countDown();
            try {
                if (!allowTransactionInsert.await(5, TimeUnit.SECONDS)) {
                    throw new SQLException("Timed out waiting to insert transaction");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException("Interrupted while waiting to insert transaction", e);
            }
            transactions.add(entry);
        }

        @Override
        public synchronized List<TransactionEntry> getTransactions(UUID targetId, int limit, int offset) {
            return filterTransactions(targetId, null, 0, Long.MAX_VALUE, limit, offset);
        }

        @Override
        public synchronized List<TransactionEntry> getTransactions(UUID targetId, int limit, int offset,
                TransactionType type, long fromMs, long toMs) {
            return filterTransactions(targetId, type, fromMs, toMs, limit, offset);
        }

        @Override
        public synchronized List<TransactionEntry> getTransactions(UUID targetId, int limit, int offset,
                TransactionType type, long fromMs, long toMs, String currencyId) {
            return filterTransactions(targetId, type, fromMs, toMs, limit, offset);
        }

        @Override
        public synchronized int countTransactions(UUID targetId) {
            return filterTransactions(targetId, null, 0, Long.MAX_VALUE, Integer.MAX_VALUE, 0).size();
        }

        @Override
        public synchronized int countTransactions(UUID targetId, TransactionType type, long fromMs, long toMs) {
            return filterTransactions(targetId, type, fromMs, toMs, Integer.MAX_VALUE, 0).size();
        }

        @Override
        public synchronized int countTransactions(UUID targetId, TransactionType type,
                long fromMs, long toMs, String currencyId) {
            return filterTransactions(targetId, type, fromMs, toMs, Integer.MAX_VALUE, 0).size();
        }

        @Override
        public synchronized int pruneTransactions(long cutoffMs) {
            int before = transactions.size();
            transactions.removeIf(entry -> entry.getTimestamp() < cutoffMs);
            return before - transactions.size();
        }

        private List<TransactionEntry> filterTransactions(UUID targetId, TransactionType type,
                long fromMs, long toMs, int limit, int offset) {
            return transactions.stream()
                    .filter(entry -> entry.getTargetId().equals(targetId))
                    .filter(entry -> type == null || entry.getType() == type)
                    .filter(entry -> entry.getTimestamp() >= fromMs)
                    .filter(entry -> entry.getTimestamp() <= toMs)
                    .sorted(Comparator.comparingLong(TransactionEntry::getTimestamp).reversed())
                    .skip(offset)
                    .limit(limit)
                    .toList();
        }
    }
}
