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
import java.util.Set;

public final class CmiDatabaseReader implements EconomySourceReader {

    @Override
    public MigrationSource source() {
        return MigrationSource.CMI;
    }

    @Override
    public String describeLocation(MigrationContext context) {
        Path db = resolveDatabase(context);
        return db == null ? "plugins/CMI/*.db" : db.toString();
    }

    @Override
    public boolean isAvailable(MigrationContext context) {
        return resolveDatabase(context) != null;
    }

    @Override
    public List<ForeignAccount> read(MigrationContext context) throws IOException {
        Path database = resolveDatabase(context);
        if (database == null) {
            throw new IOException("CMI database not found under plugins/CMI");
        }

        try (Connection conn = SqliteSupport.open(database)) {
            String table = resolveUsersTable(conn);
            if (table == null) {
                throw new IOException("CMI users table not found in " + database);
            }

            String uuidColumn = SqliteSupport.firstExistingColumn(conn, table, "player_uuid", "uuid");
            String nameColumn = SqliteSupport.firstExistingColumn(conn, table, "username", "userName", "name");
            String balanceColumn = SqliteSupport.firstExistingColumn(conn, table, "Balance", "balance");
            if (uuidColumn == null || nameColumn == null || balanceColumn == null) {
                throw new IOException("CMI table " + table + " is missing uuid/name/balance columns");
            }

            List<ForeignAccount> accounts = new ArrayList<>();
            for (SqliteSupport.ForeignAccountRow row : SqliteSupport.queryAccounts(
                    conn, table, uuidColumn, nameColumn, balanceColumn)) {
                accounts.add(new ForeignAccount(row.id(), row.name(), row.balance()));
            }
            return List.copyOf(accounts);
        } catch (Exception e) {
            throw new IOException("Failed to read CMI database: " + e.getMessage(), e);
        }
    }

    private static Path resolveDatabase(MigrationContext context) {
        Path override = context.resolveOverride(
                "cmi-database",
                context.pluginsFolder().resolve("CMI").resolve("cmi.sqlite.db"));
        if (override.toFile().isFile()) {
            return override;
        }
        return SqliteSupport.firstExistingFile(
                context.pluginsFolder().resolve("CMI").resolve("cmi.sqlite.db"),
                context.pluginsFolder().resolve("CMI").resolve("Database.db"),
                context.pluginsFolder().resolve("CMI").resolve("database.db"));
    }

    private static String resolveUsersTable(Connection conn) throws Exception {
        Set<String> tables = SqliteSupport.listTables(conn);
        if (tables.contains("users")) {
            return "users";
        }
        if (tables.contains("CMI_users")) {
            return "CMI_users";
        }
        for (String table : tables) {
            if (table.toLowerCase().endsWith("users")) {
                return table;
            }
        }
        return null;
    }
}
