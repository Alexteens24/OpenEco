/*
 * Copyright 2026 alexisbinh
 * Licensed under the Apache License, Version 2.0.
 */
package dev.alexisbinh.openeco.storage;

import com.zaxxer.hikari.HikariDataSource;
import dev.alexisbinh.openeco.model.TransactionType;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "OPEN_ECO_REMOTE_TESTS", matches = "true")
class RemoteMultiWriterIntegrationTest {

    @Test
    void authoritativeWritesRunOnEveryRemoteDialect() throws Exception {
        verifyDialect(DatabaseDialect.MYSQL, 13306, "root", "test");
        verifyDialect(DatabaseDialect.MARIADB, 13307, "root", "test");
        verifyDialect(DatabaseDialect.POSTGRESQL, 15432, "postgres", "test");
    }

    private static void verifyDialect(
            DatabaseDialect dialect, int port, String username, String password) throws Exception {
        try (JdbcAccountRepository first = repository(dialect, port, username, password);
             JdbcAccountRepository second = repository(dialect, port, username, password)) {
            UUID accountId = UUID.randomUUID();
            long now = System.currentTimeMillis();
            String prefix = dialect.name().substring(0, 2).toLowerCase(Locale.ROOT);
            var created = first.createAccount(UUID.randomUUID(), accountId, prefix + "First",
                    Map.of("coins", new BigDecimal("20")), "coins", now);
            assertEquals(MultiWriterRepository.MutationStatus.SUCCESS, created.status(), dialect.name());

            try (var executor = Executors.newFixedThreadPool(4)) {
                List<Callable<MultiWriterRepository.MutationStatus>> writes =
                        java.util.stream.IntStream.range(0, 4)
                                .<Callable<MultiWriterRepository.MutationStatus>>mapToObj(index -> () ->
                                        (index % 2 == 0 ? first : second).mutateBalance(
                                                new MultiWriterRepository.BalanceMutationRequest(
                                                        UUID.randomUUID(), accountId, "coins",
                                                        MultiWriterRepository.BalanceMutationKind.WITHDRAW,
                                                        new BigDecimal("5"), null, TransactionType.TAKE,
                                                        now + index + 1, "remote-test", null)).status())
                                .toList();
                for (var result : executor.invokeAll(writes)) {
                    assertEquals(MultiWriterRepository.MutationStatus.SUCCESS, result.get(), dialect.name());
                }
            }
            assertEquals(0, BigDecimal.ZERO.compareTo(
                    first.loadAccount(accountId).orElseThrow().getBalance("coins")), dialect.name());

            long beforeDeleteVersion = first.loadAccount(accountId).orElseThrow().getVersion();
            var deleted = first.deleteAccount(UUID.randomUUID(), accountId, now + 10);
            var recreated = second.createAccount(UUID.randomUUID(), accountId, prefix + "Again",
                    Map.of("coins", BigDecimal.ONE), "coins", now + 11);
            assertEquals(MultiWriterRepository.MutationStatus.SUCCESS, recreated.status(), dialect.name());
            assertTrue(deleted.account().getVersion() > beforeDeleteVersion, dialect.name());
            assertTrue(recreated.account().getVersion() > deleted.account().getVersion(), dialect.name());

            UUID once = UUID.randomUUID();
            var onceRequest = new MultiWriterRepository.BalanceMutationRequest(
                    once, accountId, "coins", MultiWriterRepository.BalanceMutationKind.DEPOSIT,
                    BigDecimal.ONE, null, TransactionType.GIVE, now + 12, "interest", null);
            assertEquals(MultiWriterRepository.MutationStatus.SUCCESS,
                    first.mutateBalance(onceRequest).status(), dialect.name());
            assertEquals(MultiWriterRepository.MutationStatus.ALREADY_APPLIED,
                    second.mutateBalance(onceRequest).status(), dialect.name());
            assertEquals(accountId,
                    first.findAppliedBalanceMutation(once).orElseThrow().accountId(), dialect.name());
            assertTrue(first.loadAccounts(List.of(accountId, UUID.randomUUID())).get(accountId).isPresent(),
                    dialect.name());

            UUID recipient = UUID.randomUUID();
            first.createAccount(UUID.randomUUID(), recipient, prefix + "Recv",
                    Map.of("coins", BigDecimal.ZERO), "coins", now + 13);
            var constraints = List.of(
                    new MultiWriterRepository.RollingPolicyConstraint(
                            "remote-strict", BigDecimal.ONE, 60_000L),
                    new MultiWriterRepository.RollingPolicyConstraint(
                            "remote-loose", BigDecimal.TEN, 60_000L));
            assertEquals(MultiWriterRepository.MutationStatus.SUCCESS,
                    first.transfer(new MultiWriterRepository.TransferMutationRequest(
                            UUID.randomUUID(), accountId, recipient, "coins",
                            BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO,
                            null, 0L, false, constraints, now + 14)).status(), dialect.name());

            String runId = UUID.randomUUID().toString();
            assertTrue(first.tryAcquireJobLease("remote-test", runId, "writer-a",
                    now, now + 5_000), dialect.name());
            assertEquals(false, second.tryAcquireJobLease("remote-test", runId, "writer-b",
                    now + 1, now + 5_001), dialect.name());
            first.completeJobLease("remote-test", runId, "writer-a");
        }
    }

    private static JdbcAccountRepository repository(
            DatabaseDialect dialect, int port, String username, String password) throws Exception {
        String section = dialect.name().toLowerCase(Locale.ROOT);
        YamlConfiguration config = new YamlConfiguration();
        config.set("storage." + section + ".host", "127.0.0.1");
        config.set("storage." + section + ".port", port);
        config.set("storage." + section + ".database", "openeco");
        config.set("storage." + section + ".username", username);
        config.set("storage." + section + ".password", password);
        config.set("storage." + section + ".pool-size", 4);
        config.set("storage." + section + ".connection-timeout-seconds", 10);
        HikariDataSource dataSource = RemoteStorageDataSource.create(dialect, config);
        return new JdbcAccountRepository(dataSource, dialect, "coins");
    }
}
