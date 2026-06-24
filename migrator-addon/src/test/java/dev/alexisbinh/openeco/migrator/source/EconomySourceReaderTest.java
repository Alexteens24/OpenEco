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

package dev.alexisbinh.openeco.migrator.source;

import dev.alexisbinh.openeco.migrator.MigrationTestSupport;
import dev.alexisbinh.openeco.migrator.model.ForeignAccount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EconomySourceReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void essentialsReaderLoadsUserdataBalances() throws Exception {
        UUID aliceId = UUID.randomUUID();
        UUID bobId = UUID.randomUUID();
        Path plugins = tempDir.resolve("plugins");
        MigrationTestSupport.createEssentialsUser(plugins, aliceId, "Alice", "125.50");
        MigrationTestSupport.createEssentialsUser(plugins, bobId, "Bob", "40.00");

        EssentialsUserdataReader reader = new EssentialsUserdataReader();
        MigrationContext context = MigrationTestSupport.context(plugins);

        assertTrue(reader.isAvailable(context));
        List<ForeignAccount> accounts = reader.read(context);

        assertEquals(2, accounts.size());
        ForeignAccount alice = accounts.stream().filter(a -> a.id().equals(aliceId)).findFirst().orElseThrow();
        assertEquals("Alice", alice.name());
        assertEquals(0, new BigDecimal("125.50").compareTo(alice.balance()));
    }

    @Test
    void liteEcoReaderLoadsSqliteAccounts() throws Exception {
        UUID id = UUID.randomUUID();
        Path plugins = tempDir.resolve("plugins");
        MigrationTestSupport.createLiteEcoDatabase(plugins, id, "Trader", 88.25);

        LiteEcoDatabaseReader reader = new LiteEcoDatabaseReader();
        MigrationContext context = MigrationTestSupport.context(plugins);
        List<ForeignAccount> accounts = reader.read(context);

        assertEquals(1, accounts.size());
        assertEquals(id, accounts.getFirst().id());
        assertEquals(0, new BigDecimal("88.25").compareTo(accounts.getFirst().balance()));
    }

    @Test
    void xConomyReaderLoadsSqliteAccounts() throws Exception {
        UUID id = UUID.randomUUID();
        Path plugins = tempDir.resolve("plugins");
        MigrationTestSupport.createXConomyDatabase(plugins, id, "Miner", 15.75);

        XConomyDatabaseReader reader = new XConomyDatabaseReader();
        MigrationContext context = MigrationTestSupport.context(plugins);
        List<ForeignAccount> accounts = reader.read(context);

        assertEquals(1, accounts.size());
        assertEquals("Miner", accounts.getFirst().name());
        assertEquals(0, new BigDecimal("15.75").compareTo(accounts.getFirst().balance()));
    }

    @Test
    void tneReaderLoadsYamlAccounts() throws Exception {
        UUID id = UUID.randomUUID();
        Path plugins = tempDir.resolve("plugins");
        MigrationTestSupport.createTneYamlAccount(plugins, id, "Banker", "350.25");

        TneEconomyReader reader = new TneEconomyReader();
        MigrationContext context = MigrationTestSupport.context(plugins);
        List<ForeignAccount> accounts = reader.read(context);

        assertEquals(1, accounts.size());
        assertEquals(id, accounts.getFirst().id());
        assertEquals("Banker", accounts.getFirst().name());
        assertEquals(0, new BigDecimal("350.25").compareTo(accounts.getFirst().balance()));
    }

    @Test
    void tneReaderLoadsSqliteAccounts() throws Exception {
        UUID id = UUID.randomUUID();
        Path plugins = tempDir.resolve("plugins");
        MigrationTestSupport.createTneDatabase(plugins, id, "Trader", 120.50);

        TneEconomyReader reader = new TneEconomyReader();
        MigrationContext context = MigrationTestSupport.context(plugins);
        List<ForeignAccount> accounts = reader.read(context);

        assertEquals(1, accounts.size());
        assertEquals(0, new BigDecimal("120.50").compareTo(accounts.getFirst().balance()));
    }

    @Test
    void playerPointsReaderLoadsSqliteAccounts() throws Exception {
        UUID id = UUID.randomUUID();
        Path plugins = tempDir.resolve("plugins");
        MigrationTestSupport.createPlayerPointsDatabase(plugins, id, "Collector", 5000);

        PlayerPointsReader reader = new PlayerPointsReader();
        MigrationContext context = MigrationTestSupport.context(plugins);
        List<ForeignAccount> accounts = reader.read(context);

        assertEquals(1, accounts.size());
        assertEquals("Collector", accounts.getFirst().name());
        assertEquals(0, new BigDecimal("5000").compareTo(accounts.getFirst().balance()));
    }

    @Test
    void playerPointsReaderLoadsLegacyYaml() throws Exception {
        UUID id = UUID.randomUUID();
        Path plugins = tempDir.resolve("plugins");
        MigrationTestSupport.createPlayerPointsLegacyStorage(plugins, id, 250);

        PlayerPointsReader reader = new PlayerPointsReader();
        MigrationContext context = MigrationTestSupport.context(plugins);
        List<ForeignAccount> accounts = reader.read(context);

        assertEquals(1, accounts.size());
        assertEquals(id, accounts.getFirst().id());
        assertEquals(0, new BigDecimal("250").compareTo(accounts.getFirst().balance()));
    }

    @Test
    void playerPointsReaderLoadsModernSqliteFileName() throws Exception {
        UUID id = UUID.randomUUID();
        Path plugins = tempDir.resolve("plugins");
        Path db = plugins.resolve("PlayerPoints").resolve("playerpoints.db");
        db.getParent().toFile().mkdirs();
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db);
             java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE pp_points (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        uuid TEXT NOT NULL UNIQUE,
                        points INTEGER NOT NULL
                    )
                    """);
            stmt.execute("INSERT INTO pp_points (uuid, points) VALUES ('" + id + "', 900)");
        }

        PlayerPointsReader reader = new PlayerPointsReader();
        MigrationContext context = MigrationTestSupport.context(plugins);
        List<ForeignAccount> accounts = reader.read(context);

        assertEquals(1, accounts.size());
        assertEquals(0, new BigDecimal("900").compareTo(accounts.getFirst().balance()));
    }

    @Test
    void cmiReaderLoadsSqliteAccounts() throws Exception {
        UUID id = UUID.randomUUID();
        Path plugins = tempDir.resolve("plugins");
        MigrationTestSupport.createCmiDatabase(plugins, id, "Builder", 200.00);

        CmiDatabaseReader reader = new CmiDatabaseReader();
        MigrationContext context = MigrationTestSupport.context(plugins);
        List<ForeignAccount> accounts = reader.read(context);

        assertEquals(1, accounts.size());
        assertEquals("Builder", accounts.getFirst().name());
        assertEquals(0, new BigDecimal("200.00").compareTo(accounts.getFirst().balance()));
    }
}
