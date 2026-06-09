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

import dev.alexisbinh.openeco.migrator.model.ForeignAccount;
import dev.alexisbinh.openeco.migrator.model.MigrationSource;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class LiteEcoDatabaseReader implements EconomySourceReader {

    @Override
    public MigrationSource source() {
        return MigrationSource.LITECO;
    }

    @Override
    public String describeLocation(MigrationContext context) {
        Path db = resolveDatabase(context);
        return db == null ? "plugins/LiteEco/database.db" : db.toString();
    }

    @Override
    public boolean isAvailable(MigrationContext context) {
        return resolveDatabase(context) != null;
    }

    @Override
    public List<ForeignAccount> read(MigrationContext context) throws IOException {
        Path database = resolveDatabase(context);
        if (database == null) {
            throw new IOException("LiteEco database not found under plugins/LiteEco");
        }

        try (Connection conn = SqliteSupport.open(database)) {
            List<String> tables = resolveAccountTables(conn);
            if (tables.isEmpty()) {
                throw new IOException("No LiteEco account tables found in " + database);
            }

            List<ForeignAccount> accounts = new ArrayList<>();
            for (String table : tables) {
                String uuidColumn = SqliteSupport.firstExistingColumn(conn, table, "uuid", "player_uuid", "UID");
                String nameColumn = SqliteSupport.firstExistingColumn(conn, table, "username", "name", "playername");
                String balanceColumn = SqliteSupport.firstExistingColumn(conn, table, "money", "balance");
                if (uuidColumn == null || balanceColumn == null) {
                    continue;
                }
                if (nameColumn == null) {
                    nameColumn = uuidColumn;
                }
                for (SqliteSupport.ForeignAccountRow row : SqliteSupport.queryAccounts(
                        conn, table, uuidColumn, nameColumn, balanceColumn)) {
                    accounts.add(new ForeignAccount(row.id(), row.name(), row.balance()));
                }
            }
            return List.copyOf(accounts);
        } catch (Exception e) {
            throw new IOException("Failed to read LiteEco database: " + e.getMessage(), e);
        }
    }

    private static Path resolveDatabase(MigrationContext context) {
        Path override = context.resolveOverride(
                "liteeco-database",
                context.pluginsFolder().resolve("LiteEco").resolve("database.db"));
        if (override.toFile().isFile()) {
            return override;
        }
        return SqliteSupport.firstExistingFile(
                context.pluginsFolder().resolve("LiteEco").resolve("database.db"),
                context.pluginsFolder().resolve("LiteEco").resolve("economy.db"));
    }

    private static List<String> resolveAccountTables(Connection conn) throws Exception {
        Set<String> tables = SqliteSupport.listTables(conn);
        List<String> matches = new ArrayList<>();
        for (String table : tables) {
            String lower = table.toLowerCase(Locale.ROOT);
            if (lower.equals("lite_eco") || lower.startsWith("lite_eco_")) {
                matches.add(table);
            }
        }
        return matches;
    }
}
