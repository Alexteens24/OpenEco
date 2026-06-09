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

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

final class SqliteSupport {

    private SqliteSupport() {
    }

    static Connection open(Path databaseFile) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
    }

    static boolean tableExists(Connection conn, String tableName) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' AND lower(name)='"
                             + tableName.toLowerCase(Locale.ROOT) + "'")) {
            return rs.next();
        }
    }

    static Set<String> listTables(Connection conn) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        return Set.copyOf(tables);
    }

    static String firstExistingColumn(Connection conn, String table, String... candidates) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + quoteIdentifier(table) + ")")) {
            while (rs.next()) {
                columns.add(rs.getString("name").toLowerCase(Locale.ROOT));
            }
        }
        for (String candidate : candidates) {
            if (columns.contains(candidate.toLowerCase(Locale.ROOT))) {
                return candidate;
            }
        }
        return null;
    }

    static List<ForeignAccountRow> queryAccounts(
            Connection conn,
            String table,
            String uuidColumn,
            String nameColumn,
            String balanceColumn) throws SQLException {
        String sql = "SELECT "
                + quoteIdentifier(uuidColumn) + ", "
                + quoteIdentifier(nameColumn) + ", "
                + quoteIdentifier(balanceColumn)
                + " FROM " + quoteIdentifier(table);
        List<ForeignAccountRow> rows = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String uuidRaw = rs.getString(1);
                String name = rs.getString(2);
                double balance = rs.getDouble(3);
                if (uuidRaw == null || uuidRaw.isBlank()) {
                    continue;
                }
                UUID id = parseUuid(uuidRaw);
                if (id == null) {
                    continue;
                }
                if (name == null || name.isBlank()) {
                    name = id.toString().substring(0, 8);
                }
                rows.add(new ForeignAccountRow(id, name, BigDecimal.valueOf(balance)));
            }
        }
        return rows;
    }

    static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    static Path firstExistingFile(Path... candidates) {
        for (Path candidate : candidates) {
            if (candidate != null && Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    static Path firstExistingInDirectory(Path directory, String... fileNames) {
        if (directory == null || !Files.isDirectory(directory)) {
            return null;
        }
        for (String fileName : fileNames) {
            Path candidate = directory.resolve(fileName);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".db"))
                    .findFirst()
                    .orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    record ForeignAccountRow(java.util.UUID id, String name, BigDecimal balance) {
    }
}
