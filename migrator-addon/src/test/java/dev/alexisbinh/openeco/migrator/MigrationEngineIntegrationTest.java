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

package dev.alexisbinh.openeco.migrator;

import dev.alexisbinh.openeco.api.OpenEcoApi;
import dev.alexisbinh.openeco.api.OpenEcoApiImpl;
import dev.alexisbinh.openeco.api.TransferResult;
import dev.alexisbinh.openeco.migrator.model.MigrationReport;
import dev.alexisbinh.openeco.migrator.model.MigrationSource;
import dev.alexisbinh.openeco.migrator.source.MigrationContext;
import dev.alexisbinh.openeco.service.AccountService;
import dev.alexisbinh.openeco.storage.DatabaseDialect;
import dev.alexisbinh.openeco.storage.JdbcAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationEngineIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void migratesEssentialsAccountsAndSupportsTransfer() throws Exception {
        UUID aliceId = UUID.randomUUID();
        UUID bobId = UUID.randomUUID();
        Path plugins = tempDir.resolve("plugins");
        MigrationTestSupport.createEssentialsUser(plugins, aliceId, "Alice", "100.00");
        MigrationTestSupport.createEssentialsUser(plugins, bobId, "Bob", "50.00");

        JdbcAccountRepository repository = new JdbcAccountRepository(
                DatabaseDialect.H2, tempDir.resolve("openeco").toString(), "economy");
        try {
            AccountService service = MigrationTestSupport.newOpenEcoService(repository);
            service.loadAll();
            OpenEcoApi api = new OpenEcoApiImpl(service);

            MigrationEngine engine = new MigrationEngine(
                    api,
                    MigrationTestSupport.context(plugins),
                    "openeco");

            MigrationReport report = engine.migrate(MigrationSource.ESSENTIALS, false, false);
            assertEquals(2, report.scanned());
            assertEquals(2, report.created());
            assertEquals(0, report.failed());

            assertEquals(0, new BigDecimal("100.00").compareTo(api.getBalance(aliceId)));
            assertEquals(0, new BigDecimal("50.00").compareTo(api.getBalance(bobId)));

            TransferResult transfer = api.transfer(aliceId, bobId, new BigDecimal("25.00"));
            assertEquals(TransferResult.Status.SUCCESS, transfer.status());
            assertEquals(0, new BigDecimal("75.00").compareTo(api.getBalance(aliceId)));
            assertEquals(0, new BigDecimal("75.00").compareTo(api.getBalance(bobId)));

            service.shutdown();
        } finally {
            repository.close();
        }
    }

    @Test
    void dryRunDoesNotCreateAccounts() throws Exception {
        UUID id = UUID.randomUUID();
        Path plugins = tempDir.resolve("plugins");
        MigrationTestSupport.createLiteEcoDatabase(plugins, id, "Solo", 42.00);

        JdbcAccountRepository repository = new JdbcAccountRepository(
                DatabaseDialect.H2, tempDir.resolve("openeco-dry").toString(), "economy");
        try {
            AccountService service = MigrationTestSupport.newOpenEcoService(repository);
            service.loadAll();
            OpenEcoApi api = new OpenEcoApiImpl(service);

            MigrationEngine engine = new MigrationEngine(
                    api,
                    MigrationTestSupport.context(plugins),
                    "openeco");

            MigrationReport preview = engine.migrate(MigrationSource.LITECO, true, false);
            assertEquals(1, preview.scanned());
            // A preview has to evaluate what a real run would do. It used to report zero for
            // every counter, so "scan, dry-run, then run" told the operator nothing.
            assertEquals(1, preview.created(), "the preview must predict the real outcome");
            assertEquals(0, preview.failed());
            assertFalse(api.hasAccount(id), "a preview must not create anything");

            // The strongest guarantee: the counters of a preview and of the real run agree.
            MigrationReport actual = engine.migrate(MigrationSource.LITECO, false, false);
            assertEquals(preview.scanned(), actual.scanned());
            assertEquals(preview.created(), actual.created());
            assertEquals(preview.failed(), actual.failed());
            assertTrue(api.hasAccount(id), "the real run creates the account");

            service.shutdown();
        } finally {
            repository.close();
        }
    }
}
