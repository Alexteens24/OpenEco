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

import dev.alexisbinh.openeco.model.AccountRecord;
import dev.alexisbinh.openeco.model.TransactionEntry;
import dev.alexisbinh.openeco.model.TransactionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageMigratorIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void migratesAccountsBalancesAndTransactionsFromSqliteToH2() throws Exception {
        Path sourceDir = tempDir.resolve("source");
        Path targetDir = tempDir.resolve("target");
        sourceDir.toFile().mkdirs();
        targetDir.toFile().mkdirs();

        UUID aliceId = UUID.randomUUID();
        UUID bobId = UUID.randomUUID();
        JdbcAccountRepository source = new JdbcAccountRepository(
                DatabaseDialect.SQLITE, sourceDir.toString(), "economy.db", "openeco");
        try {
            AccountRecord alice = new AccountRecord(aliceId, "Alice", new BigDecimal("100.00"), 1L, 2L);
            AccountRecord bob = new AccountRecord(bobId, "Bob", new BigDecimal("50.00"), 3L, 4L);
            source.upsertBatch(List.of(alice, bob));
            source.insertTransaction(new TransactionEntry(
                    TransactionType.GIVE,
                    null,
                    aliceId,
                    new BigDecimal("25.00"),
                    new BigDecimal("75.00"),
                    new BigDecimal("100.00"),
                    10L,
                    "test",
                    "seed",
                    "openeco"));
            source.insertTransaction(new TransactionEntry(
                    TransactionType.PAY_SENT,
                    bobId,
                    aliceId,
                    new BigDecimal("10.00"),
                    new BigDecimal("90.00"),
                    new BigDecimal("100.00"),
                    11L,
                    "test",
                    "pay",
                    "openeco"));
        } finally {
            source.close();
        }

        JdbcAccountRepository sourceRepo = new JdbcAccountRepository(
                DatabaseDialect.SQLITE, sourceDir.toString(), "economy.db", "openeco");
        JdbcAccountRepository targetRepo = new JdbcAccountRepository(
                DatabaseDialect.H2, targetDir.toString(), "economy", "openeco");
        try {
            StorageMigrationReport report = StorageMigrator.migrate(
                    sourceRepo, targetRepo, DatabaseDialect.H2, false, true);
            assertTrue(report.errors().isEmpty());
            assertEquals(2, report.accountsCopied());
            assertEquals(2, report.transactionsCopied());

            List<AccountRecord> migratedAccounts = targetRepo.loadAll();
            assertEquals(2, migratedAccounts.size());
            assertEquals(2, targetRepo.countTransactions());

            AccountRecord migratedAlice = targetRepo.loadAccount(aliceId).orElseThrow();
            assertEquals(0, new BigDecimal("100.00").compareTo(migratedAlice.getBalance("openeco")));
            assertTrue(migratedAlice.isFrozen() == false);

            List<TransactionEntry> history = targetRepo.getTransactions(aliceId, 10, 0);
            assertEquals(2, history.size());
        } finally {
            sourceRepo.close();
            targetRepo.close();
        }
    }

    @Test
    void dryRunDoesNotWriteTargetData() throws Exception {
        Path sourceDir = tempDir.resolve("dry-source");
        sourceDir.toFile().mkdirs();

        JdbcAccountRepository source = new JdbcAccountRepository(
                DatabaseDialect.SQLITE, sourceDir.toString(), "economy.db", "openeco");
        JdbcAccountRepository target = new JdbcAccountRepository(
                DatabaseDialect.H2, tempDir.resolve("dry-target").toString(), "economy", "openeco");
        try {
            UUID id = UUID.randomUUID();
            AccountRecord account = new AccountRecord(id, "Solo", new BigDecimal("1.00"), 1L, 1L);
            source.upsertBatch(List.of(account));

            StorageMigrationReport report = StorageMigrator.migrate(
                    source, target, DatabaseDialect.H2, true, false);
            assertTrue(report.errors().isEmpty());
            assertEquals(1, report.accountsCopied());
            assertEquals(0, target.countAccounts());
        } finally {
            source.close();
            target.close();
        }
    }
}
