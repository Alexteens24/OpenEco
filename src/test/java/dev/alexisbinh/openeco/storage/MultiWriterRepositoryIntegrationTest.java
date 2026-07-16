package dev.alexisbinh.openeco.storage;

import dev.alexisbinh.openeco.model.TransactionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiWriterRepositoryIntegrationTest {
    @TempDir Path tempDir;

    @Test
    void concurrentWithdrawalsNeverOverspendAndChangesAreDurable() throws Exception {
        try (JdbcAccountRepository first = new JdbcAccountRepository(
                     DatabaseDialect.H2, tempDir.toString(), "multi-writer", "coins");
             JdbcAccountRepository second = new JdbcAccountRepository(
                     DatabaseDialect.H2, tempDir.toString(), "multi-writer", "coins")) {
            UUID accountId = UUID.randomUUID();
            assertEquals(MultiWriterRepository.MutationStatus.SUCCESS,
                    first.createAccount(UUID.randomUUID(), accountId, "Alice",
                            Map.of("coins", new BigDecimal("100.00")), "coins", System.currentTimeMillis()).status());

            try (var executor = Executors.newFixedThreadPool(8)) {
                var tasks = java.util.stream.IntStream.range(0, 20)
                        .<Callable<MultiWriterRepository.MutationStatus>>mapToObj(i -> () -> {
                            JdbcAccountRepository repository = i % 2 == 0 ? first : second;
                            return repository.mutateBalance(new MultiWriterRepository.BalanceMutationRequest(
                                    UUID.randomUUID(), accountId, "coins",
                                    MultiWriterRepository.BalanceMutationKind.WITHDRAW,
                                    new BigDecimal("10.00"), null, TransactionType.TAKE,
                                    System.currentTimeMillis(), "test", null)).status();
                        }).toList();
                long successes = executor.invokeAll(tasks).stream()
                        .filter(future -> {
                            try { return future.get() == MultiWriterRepository.MutationStatus.SUCCESS; }
                            catch (Exception e) { throw new RuntimeException(e); }
                        }).count();
                assertEquals(10L, successes);
            }

            assertEquals(0, BigDecimal.ZERO.compareTo(first.loadAccount(accountId).orElseThrow().getBalance("coins")));
            assertTrue(first.currentChangeSequence() >= 11L);
            assertTrue(first.loadChangesAfter(0L, 100).stream().anyMatch(
                    change -> change.accountId().equals(accountId)));
        }
    }

    @Test
    void concurrentDepositsAccumulateWithoutLostUpdates() throws Exception {
        try (JdbcAccountRepository first = new JdbcAccountRepository(
                     DatabaseDialect.H2, tempDir.toString(), "deposits", "coins");
             JdbcAccountRepository second = new JdbcAccountRepository(
                     DatabaseDialect.H2, tempDir.toString(), "deposits", "coins")) {
            UUID accountId = UUID.randomUUID();
            first.createAccount(UUID.randomUUID(), accountId, "Bob",
                    Map.of("coins", BigDecimal.ZERO), "coins", System.currentTimeMillis());

            try (var executor = Executors.newFixedThreadPool(8)) {
                var tasks = java.util.stream.IntStream.range(0, 100)
                        .<Callable<MultiWriterRepository.MutationStatus>>mapToObj(i -> () ->
                                (i % 2 == 0 ? first : second).mutateBalance(
                                        new MultiWriterRepository.BalanceMutationRequest(
                                                UUID.randomUUID(), accountId, "coins",
                                                MultiWriterRepository.BalanceMutationKind.DEPOSIT,
                                                BigDecimal.ONE, null, TransactionType.GIVE,
                                                System.currentTimeMillis(), "test", null)).status()).toList();
                for (var result : executor.invokeAll(tasks)) {
                    assertEquals(MultiWriterRepository.MutationStatus.SUCCESS, result.get());
                }
            }
            assertEquals(0, new BigDecimal("100.00").compareTo(
                    second.loadAccount(accountId).orElseThrow().getBalance("coins")));
        }
    }

    @Test
    void concurrentTransfersCannotSpendTheSameBalanceTwice() throws Exception {
        try (JdbcAccountRepository first = repository("transfers");
             JdbcAccountRepository second = repository("transfers")) {
            UUID sender = UUID.randomUUID();
            UUID recipient = UUID.randomUUID();
            long now = System.currentTimeMillis();
            first.createAccount(UUID.randomUUID(), sender, "Sender", Map.of("coins", new BigDecimal("50")), "coins", now);
            first.createAccount(UUID.randomUUID(), recipient, "Recipient", Map.of("coins", BigDecimal.ZERO), "coins", now);

            try (var executor = Executors.newFixedThreadPool(8)) {
                var tasks = java.util.stream.IntStream.range(0, 20)
                        .<Callable<MultiWriterRepository.MutationStatus>>mapToObj(i -> () ->
                                (i % 2 == 0 ? first : second).transfer(transferRequest(
                                        sender, recipient, new BigDecimal("10"), now + i,
                                        0L, false, null, null, 0L)).status()).toList();
                long successes = executor.invokeAll(tasks).stream().filter(result -> {
                    try { return result.get() == MultiWriterRepository.MutationStatus.SUCCESS; }
                    catch (Exception e) { throw new RuntimeException(e); }
                }).count();
                assertEquals(5L, successes);
            }

            assertEquals(0, BigDecimal.ZERO.compareTo(first.loadAccount(sender).orElseThrow().getBalance("coins")));
            assertEquals(0, new BigDecimal("50").compareTo(first.loadAccount(recipient).orElseThrow().getBalance("coins")));
        }
    }

    @Test
    void cooldownAndRollingLimitAreSharedAcrossWriters() throws Exception {
        try (JdbcAccountRepository first = repository("policies");
             JdbcAccountRepository second = repository("policies")) {
            UUID sender = UUID.randomUUID();
            UUID recipient = UUID.randomUUID();
            long now = System.currentTimeMillis();
            first.createAccount(UUID.randomUUID(), sender, "PolicySender", Map.of("coins", new BigDecimal("100")), "coins", now);
            first.createAccount(UUID.randomUUID(), recipient, "PolicyRecipient", Map.of("coins", BigDecimal.ZERO), "coins", now);

            assertEquals(MultiWriterRepository.MutationStatus.SUCCESS,
                    first.transfer(transferRequest(sender, recipient, new BigDecimal("10"), now,
                            5_000L, true, "daily-pay", new BigDecimal("25"), 60_000L)).status());
            assertEquals(MultiWriterRepository.MutationStatus.COOLDOWN,
                    second.transfer(transferRequest(sender, recipient, BigDecimal.ONE, now + 1,
                            5_000L, true, "daily-pay", new BigDecimal("25"), 60_000L)).status());

            assertEquals(MultiWriterRepository.MutationStatus.SUCCESS,
                    second.transfer(transferRequest(sender, recipient, new BigDecimal("10"), now + 5_001,
                            5_000L, true, "daily-pay", new BigDecimal("25"), 60_000L)).status());
            assertEquals(MultiWriterRepository.MutationStatus.POLICY_REJECTED,
                    first.transfer(transferRequest(sender, recipient, new BigDecimal("6"), now + 10_002,
                            5_000L, true, "daily-pay", new BigDecimal("25"), 60_000L)).status());
        }
    }

    @Test
    void exchangeUpdatesBothCurrenciesOrNeither() throws Exception {
        try (JdbcAccountRepository repository = repository("exchange")) {
            UUID account = UUID.randomUUID();
            long now = System.currentTimeMillis();
            repository.createAccount(UUID.randomUUID(), account, "Trader",
                    Map.of("coins", new BigDecimal("25"), "gems", new BigDecimal("2")), "coins", now);

            var success = repository.exchange(new MultiWriterRepository.ExchangeMutationRequest(
                    UUID.randomUUID(), account, "coins", "gems", new BigDecimal("10"),
                    new BigDecimal("3"), new BigDecimal("10"), now + 1, "test"));
            assertEquals(MultiWriterRepository.MutationStatus.SUCCESS, success.status());
            assertEquals(0, new BigDecimal("15").compareTo(success.fromAfter()));
            assertEquals(0, new BigDecimal("5").compareTo(success.toAfter()));

            var rejected = repository.exchange(new MultiWriterRepository.ExchangeMutationRequest(
                    UUID.randomUUID(), account, "coins", "gems", BigDecimal.ONE,
                    new BigDecimal("10"), new BigDecimal("10"), now + 2, "test"));
            assertEquals(MultiWriterRepository.MutationStatus.BALANCE_LIMIT, rejected.status());
            var stored = repository.loadAccount(account).orElseThrow();
            assertEquals(0, new BigDecimal("15").compareTo(stored.getBalance("coins")));
            assertEquals(0, new BigDecimal("5").compareTo(stored.getBalance("gems")));
        }
    }

    @Test
    void clusterLeaseHasOneOwnerAndCompletedRunsCannotRepeat() throws Exception {
        try (JdbcAccountRepository first = repository("leases");
             JdbcAccountRepository second = repository("leases")) {
            long now = System.currentTimeMillis();
            assertTrue(first.tryAcquireJobLease("interest", "run-1", "server-a", now, now + 5_000));
            assertEquals(false, second.tryAcquireJobLease("interest", "run-1", "server-b", now + 1, now + 5_001));
            first.completeJobLease("interest", "run-1", "server-a");
            assertEquals(false, second.tryAcquireJobLease("interest", "run-1", "server-b", now + 10_000, now + 15_000));

            assertTrue(first.tryAcquireJobLease("interest", "run-2", "server-a", now, now + 1));
            assertTrue(second.tryAcquireJobLease("interest", "run-2", "server-b", now + 2, now + 5_000));
        }
    }

    @Test
    void pruningKeepsLatestChangeAsAGapSentinel() throws Exception {
        try (JdbcAccountRepository repository = repository("change-prune")) {
            long now = System.currentTimeMillis();
            repository.createAccount(UUID.randomUUID(), UUID.randomUUID(), "First",
                    Map.of("coins", BigDecimal.ZERO), "coins", now);
            repository.createAccount(UUID.randomUUID(), UUID.randomUUID(), "Second",
                    Map.of("coins", BigDecimal.ZERO), "coins", now + 1);

            assertEquals(1, repository.pruneAccountChanges(Long.MAX_VALUE));
            var remaining = repository.loadChangesAfter(0L, 10);
            assertEquals(1, remaining.size());
            assertEquals(repository.currentChangeSequence(), remaining.getFirst().sequence());
        }
    }

    private JdbcAccountRepository repository(String filename) throws Exception {
        return new JdbcAccountRepository(DatabaseDialect.H2, tempDir.toString(), filename, "coins");
    }

    private static MultiWriterRepository.TransferMutationRequest transferRequest(
            UUID sender, UUID recipient, BigDecimal amount, long timestamp,
            long cooldownMs, boolean applyCooldown, String policyId,
            BigDecimal rollingMaximum, long rollingWindowMs) {
        return new MultiWriterRepository.TransferMutationRequest(
                UUID.randomUUID(), sender, recipient, "coins", amount, amount, BigDecimal.ZERO,
                null, cooldownMs, applyCooldown, policyId, rollingMaximum, rollingWindowMs, timestamp);
    }
}
