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

public final class XConomyDatabaseReader implements EconomySourceReader {

    @Override
    public MigrationSource source() {
        return MigrationSource.XCONOMY;
    }

    @Override
    public String describeLocation(MigrationContext context) {
        Path db = resolveDatabase(context);
        return db == null ? "plugins/XConomy/**/data.db" : db.toString();
    }

    @Override
    public boolean isAvailable(MigrationContext context) {
        return resolveDatabase(context) != null;
    }

    @Override
    public List<ForeignAccount> read(MigrationContext context) throws IOException {
        Path database = resolveDatabase(context);
        if (database == null) {
            throw new IOException("XConomy SQLite database not found under plugins/XConomy");
        }

        try (Connection conn = SqliteSupport.open(database)) {
            String table = resolveBalanceTable(conn);
            if (table == null) {
                throw new IOException("XConomy balance table not found in " + database);
            }

            String uuidColumn = SqliteSupport.firstExistingColumn(conn, table, "UID", "uuid", "player_uuid");
            String nameColumn = SqliteSupport.firstExistingColumn(conn, table, "playername", "username", "name");
            String balanceColumn = SqliteSupport.firstExistingColumn(conn, table, "balance", "money", "Balance");
            if (uuidColumn == null || balanceColumn == null) {
                throw new IOException("XConomy table " + table + " is missing uuid/balance columns");
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
            throw new IOException("Failed to read XConomy database: " + e.getMessage(), e);
        }
    }

    private static Path resolveDatabase(MigrationContext context) {
        Path override = context.resolveOverride(
                "xconomy-database",
                context.pluginsFolder().resolve("XConomy").resolve("playerdata").resolve("Default").resolve("data.db"));
        if (override.toFile().isFile()) {
            return override;
        }

        Path xconomyRoot = context.pluginsFolder().resolve("XConomy");
        Path direct = SqliteSupport.firstExistingFile(
                xconomyRoot.resolve("playerdata").resolve("Default").resolve("data.db"),
                xconomyRoot.resolve("data.db"));
        if (direct != null) {
            return direct;
        }
        return SqliteSupport.firstExistingInDirectory(xconomyRoot.resolve("playerdata"), "data.db");
    }

    private static String resolveBalanceTable(Connection conn) throws Exception {
        Set<String> tables = SqliteSupport.listTables(conn);
        for (String candidate : List.of("xconomy", "XConomy", "xconomy_user")) {
            if (tables.contains(candidate)) {
                return candidate;
            }
        }
        for (String table : tables) {
            if (table.toLowerCase(Locale.ROOT).contains("xconomy")) {
                return table;
            }
        }
        return null;
    }
}
