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
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class StorageMigrator {

    private static final int ACCOUNT_BATCH_SIZE = 100;
    private static final int TRANSACTION_BATCH_SIZE = 500;

    private StorageMigrator() {
    }

    public static JdbcAccountRepository openLocalSource(
            FileConfiguration config,
            File dataFolder,
            String defaultCurrencyId) throws SQLException {
        DatabaseDialect dialect = DatabaseDialect.fromConfig(
                config.getString("storage.migration.source-type", "sqlite"));
        if (!dialect.isLocal()) {
            throw new IllegalArgumentException("storage.migration.source-type must be sqlite or h2");
        }

        String folder = config.getString("storage.migration.source-folder", "").trim();
        String dataPath = folder.isEmpty() ? dataFolder.getAbsolutePath() : folder;
        String configuredFile = config.getString("storage.migration.source-file", "").trim();
        String filename = switch (dialect) {
            case H2 -> configuredFile.isEmpty()
                    ? config.getString("storage.h2.file", "economy")
                    : configuredFile;
            default -> configuredFile.isEmpty()
                    ? config.getString("storage.sqlite.file", "economy.db")
                    : configuredFile;
        };
        return new JdbcAccountRepository(dialect, dataPath, filename, defaultCurrencyId);
    }

    public static JdbcAccountRepository openRemoteTarget(
            DatabaseDialect dialect,
            FileConfiguration config,
            String defaultCurrencyId) throws SQLException {
        if (dialect.isLocal()) {
            throw new IllegalArgumentException("Target dialect must be mysql, mariadb, or postgresql");
        }
        return new JdbcAccountRepository(
                RemoteStorageDataSource.create(dialect, config),
                dialect,
                defaultCurrencyId);
    }

    public static StorageMigrationStats scan(JdbcAccountRepository repository) throws SQLException {
        return new StorageMigrationStats(repository.countAccounts(), repository.countTransactions());
    }

    public static StorageMigrationReport migrate(
            JdbcAccountRepository source,
            JdbcAccountRepository target,
            DatabaseDialect targetDialect,
            boolean dryRun,
            boolean overwrite) throws SQLException {
        StorageMigrationReport report = new StorageMigrationReport(targetDialect, dryRun);

        StorageMigrationStats sourceStats = scan(source);
        StorageMigrationStats targetStats = scan(target);
        if (targetStats.accounts() > 0 || targetStats.transactions() > 0) {
            if (!overwrite) {
                report.addError("Target already has "
                        + targetStats.accounts() + " account(s) and "
                        + targetStats.transactions() + " transaction(s). Use --overwrite to replace them.");
                return report;
            }
            if (!dryRun) {
                target.clearAllData();
            }
        }

        if (dryRun) {
            report.addAccountsCopied(sourceStats.accounts());
            report.addTransactionsCopied(sourceStats.transactions());
            return report;
        }

        List<AccountRecord> accounts = source.loadAll();
        for (int offset = 0; offset < accounts.size(); offset += ACCOUNT_BATCH_SIZE) {
            int end = Math.min(offset + ACCOUNT_BATCH_SIZE, accounts.size());
            target.upsertBatch(accounts.subList(offset, end));
            report.addAccountsCopied(end - offset);
        }

        long txOffset = 0;
        while (true) {
            List<TransactionEntry> batch = source.loadTransactions(TRANSACTION_BATCH_SIZE, txOffset);
            if (batch.isEmpty()) {
                break;
            }
            target.insertTransactionsBatch(batch);
            report.addTransactionsCopied(batch.size());
            txOffset += batch.size();
        }

        return report;
    }

    public static List<DatabaseDialect> supportedTargets() {
        return List.of(DatabaseDialect.MYSQL, DatabaseDialect.MARIADB, DatabaseDialect.POSTGRESQL);
    }

    public static DatabaseDialect parseTargetDialect(String raw) {
        DatabaseDialect dialect = DatabaseDialect.fromConfig(raw);
        if (dialect.isLocal()) {
            throw new IllegalArgumentException("Target must be mysql, mariadb, or postgresql");
        }
        return dialect;
    }

    public static List<String> formatTargetChoices() {
        List<String> labels = new ArrayList<>(3);
        for (DatabaseDialect dialect : supportedTargets()) {
            labels.add(dialect.name().toLowerCase(Locale.ROOT));
        }
        return labels;
    }
}
