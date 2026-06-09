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

/** Reads BOSEconomy v0.7+ SQLite storage when present. */
public final class BoseEconomyDatabaseReader implements EconomySourceReader {

    @Override
    public MigrationSource source() {
        return MigrationSource.BOSECONOMY;
    }

    @Override
    public String describeLocation(MigrationContext context) {
        Path db = resolveDatabase(context);
        return db == null ? "plugins/BOSEconomy/*.db" : db.toString();
    }

    @Override
    public boolean isAvailable(MigrationContext context) {
        return resolveDatabase(context) != null;
    }

    @Override
    public List<ForeignAccount> read(MigrationContext context) throws IOException {
        Path database = resolveDatabase(context);
        if (database == null) {
            throw new IOException("BOSEconomy database not found under plugins/BOSEconomy");
        }

        try (Connection conn = SqliteSupport.open(database)) {
            String table = resolveAccountsTable(conn);
            if (table == null) {
                throw new IOException("BOSEconomy accounts table not found in " + database);
            }

            String uuidColumn = SqliteSupport.firstExistingColumn(conn, table, "uuid", "player_uuid", "id");
            String nameColumn = SqliteSupport.firstExistingColumn(conn, table, "name", "username", "playername");
            String balanceColumn = SqliteSupport.firstExistingColumn(conn, table, "money", "balance");
            if (uuidColumn == null || balanceColumn == null) {
                throw new IOException("BOSEconomy table " + table + " is missing uuid/balance columns");
            }
            if (nameColumn == null) {
                nameColumn = uuidColumn;
            }

            List<ForeignAccount> accounts = new ArrayList<>();
            for (SqliteSupport.ForeignAccountRow row : SqliteSupport.queryAccounts(
                    conn, table, uuidColumn, nameColumn, balanceColumn)) {
                accounts.add(new ForeignAccount(row.id(), row.name(), row.balance()));
            }
            return List.copyOf(accounts);
        } catch (Exception e) {
            throw new IOException("Failed to read BOSEconomy database: " + e.getMessage(), e);
        }
    }

    private static Path resolveDatabase(MigrationContext context) {
        Path folder = context.pluginsFolder().resolve("BOSEconomy");
        return SqliteSupport.firstExistingFile(
                folder.resolve("accounts.db"),
                folder.resolve("economy.db"),
                folder.resolve("data.db"));
    }

    private static String resolveAccountsTable(Connection conn) throws Exception {
        Set<String> tables = SqliteSupport.listTables(conn);
        if (tables.contains("accounts")) {
            return "accounts";
        }
        if (tables.contains("boseconomy_accounts")) {
            return "boseconomy_accounts";
        }
        return tables.stream().findFirst().orElse(null);
    }
}
