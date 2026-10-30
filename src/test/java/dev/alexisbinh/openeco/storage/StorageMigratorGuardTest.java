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

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code StorageMigrator} moves a live economy between backends, so the two failure modes that
 * matter are both destructive: copying a database onto itself, and wiping the target before the
 * source has been read.
 */
class StorageMigratorGuardTest {

    @TempDir
    Path tempDir;

    private JdbcAccountRepository open(String name) throws SQLException {
        return new JdbcAccountRepository(DatabaseDialect.H2, tempDir.toString(), name);
    }

    private static void seed(JdbcAccountRepository repository, String name, String balance) throws SQLException {
        UUID id = UUID.randomUUID();
        repository.upsertBatch(java.util.List.of(new dev.alexisbinh.openeco.model.AccountRecord(
                id, name, "openeco",
                new java.util.HashMap<>(java.util.Map.of("openeco", new java.math.BigDecimal(balance))),
                1L, 1L)));
    }

    private static boolean succeeded(StorageMigrationReport report) {
        return report.errors().isEmpty();
    }

    /**
     * The regression: pointing {@code storage.migration.source-*} at the file the server is
     * already running on made the migrator open a second connection to it, then clear it. In
     * lazy mode there is no in-memory copy to rewrite, so the running economy was simply gone.
     */
    @Test
    void refusesToMigrateADatabaseOntoItself() throws Exception {
        JdbcAccountRepository live = open("economy");
        JdbcAccountRepository sameFileAgain = open("economy");
        try {
            seed(live, "Alice", "500.00");

            StorageMigrationReport report = StorageMigrator.migrate(
                    live, sameFileAgain, DatabaseDialect.H2, false, true);

            assertFalse(succeeded(report), () -> "errors=" + report.errors());
            assertTrue(report.errors().stream().anyMatch(e -> e.contains("same database")),
                    () -> "errors=" + report.errors());

            // The data is still there, which is the entire point.
            assertEquals(1, StorageMigrator.scan(live).accounts());
        } finally {
            live.close();
            sameFileAgain.close();
        }
    }

    @Test
    void distinctTargetsStillMigrate() throws Exception {
        JdbcAccountRepository source = open("source");
        JdbcAccountRepository target = open("target");
        try {
            seed(source, "Alice", "500.00");
            seed(source, "Bob", "250.00");

            StorageMigrationReport report = StorageMigrator.migrate(
                    source, target, DatabaseDialect.H2, false, true);

            assertTrue(succeeded(report), () -> "errors=" + report.errors());
            assertEquals(2, StorageMigrator.scan(target).accounts());
            assertEquals(2, StorageMigrator.scan(source).accounts(), "the source must be left intact");
        } finally {
            source.close();
            target.close();
        }
    }

    /** A dry run reports what would happen and changes nothing. */
    @Test
    void dryRunLeavesBothSidesUntouched() throws Exception {
        JdbcAccountRepository source = open("dry-source");
        JdbcAccountRepository target = open("dry-target");
        try {
            seed(source, "Alice", "500.00");
            seed(target, "Existing", "42.00");

            StorageMigrationReport report = StorageMigrator.migrate(
                    source, target, DatabaseDialect.H2, true, true);

            assertTrue(succeeded(report), () -> "errors=" + report.errors());
            assertEquals(1, StorageMigrator.scan(target).accounts(), "a dry run must not overwrite");
            assertEquals(1, StorageMigrator.scan(source).accounts());
        } finally {
            source.close();
            target.close();
        }
    }

    /**
     * The regression: an empty source (a typo'd {@code storage.migration.source-file} creates a
     * brand-new empty database) combined with {@code --overwrite} used to clear a populated
     * target, copy zero rows, and report a clean success.
     */
    @Test
    void refusesToClearAPopulatedTargetWhenTheSourceIsEmpty() throws Exception {
        JdbcAccountRepository source = open("empty-source");
        JdbcAccountRepository target = open("protected-target");
        try {
            seed(target, "Existing", "42.00");

            StorageMigrationReport report = StorageMigrator.migrate(
                    source, target, DatabaseDialect.H2, false, true);

            assertFalse(succeeded(report), () -> "errors=" + report.errors());
            assertTrue(report.errors().stream().anyMatch(e -> e.contains("no accounts")),
                    () -> "errors=" + report.errors());
            assertEquals(1, StorageMigrator.scan(target).accounts(), "the target must be untouched");
        } finally {
            source.close();
            target.close();
        }
    }

    /**
     * A failure halfway through the copy must not leave a half-migrated target: missing
     * accounts silently read as zero. The target is cleared so a re-run starts from a
     * known state.
     */
    @Test
    void failedCopyClearsThePartiallyMigratedTarget() throws Exception {
        JdbcAccountRepository source = open("large-source");
        AtomicInteger writes = new AtomicInteger();
        JdbcAccountRepository target = new JdbcAccountRepository(DatabaseDialect.H2, tempDir.toString(), "fail-target") {
            @Override
            public void upsertBatch(Collection<dev.alexisbinh.openeco.model.AccountRecord> records) throws SQLException {
                if (writes.incrementAndGet() == 3) {
                    throw new SQLException("simulated copy failure");
                }
                super.upsertBatch(records);
            }
        };
        try {
            seed(target, "Existing", "42.00"); // target write #1

            List<dev.alexisbinh.openeco.model.AccountRecord> bulk = new ArrayList<>();
            for (int i = 0; i < 150; i++) {
                bulk.add(new dev.alexisbinh.openeco.model.AccountRecord(
                        UUID.randomUUID(), "P" + i, "openeco",
                        new HashMap<>(Map.of("openeco", new BigDecimal("1.00"))), 1L, 1L));
            }
            source.upsertBatch(bulk);
            // During migrate: target write #2 is the first 100 accounts, write #3 is the second
            // batch, which fails and must trigger the cleanup.

            assertThrows(SQLException.class, () -> StorageMigrator.migrate(
                    source, target, DatabaseDialect.H2, false, true));

            assertEquals(0, StorageMigrator.scan(target).accounts(),
                    "the partially migrated target must be cleared so a re-run is safe");
        } finally {
            source.close();
            target.close();
        }
    }

    /** A configured source file that does not exist must fail before SQLite/H2 creates it. */
    @Test
    void openLocalSourceRejectsAMissingConfiguredFile() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("storage.migration.source-type", "sqlite");
        config.set("storage.migration.source-file", "does-not-exist.db");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> StorageMigrator.openLocalSource(config, tempDir.toFile(), "openeco"));
        assertTrue(error.getMessage().contains("does-not-exist.db"),
                () -> "message=" + error.getMessage());
    }

    /** Without --overwrite an existing target is refused, never silently merged or wiped. */
    @Test
    void refusesToOverwriteWithoutTheFlag() throws Exception {
        JdbcAccountRepository source = open("busy-source");
        JdbcAccountRepository target = open("busy-target");
        try {
            seed(source, "Alice", "500.00");
            seed(target, "Existing", "42.00");

            StorageMigrationReport report = StorageMigrator.migrate(
                    source, target, DatabaseDialect.H2, false, false);

            assertFalse(succeeded(report));
            assertTrue(report.errors().stream().anyMatch(e -> e.contains("--overwrite")),
                    () -> "errors=" + report.errors());
            assertEquals(1, StorageMigrator.scan(target).accounts(), "the target must be untouched");
        } finally {
            source.close();
            target.close();
        }
    }
}