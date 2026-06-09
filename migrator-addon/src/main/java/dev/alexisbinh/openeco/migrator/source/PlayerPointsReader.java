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
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Reads PlayerPoints balances from SQLite ({@code *points} table) or legacy {@code storage.yml}.
 */
public final class PlayerPointsReader implements EconomySourceReader {

    @Override
    public MigrationSource source() {
        return MigrationSource.PLAYERPOINTS;
    }

    @Override
    public String describeLocation(MigrationContext context) {
        Path database = resolveDatabase(context);
        if (database != null) {
            return database.toString();
        }
        Path legacy = resolveLegacyStorage(context);
        return legacy == null ? "plugins/PlayerPoints/storage.yml" : legacy.toString();
    }

    @Override
    public boolean isAvailable(MigrationContext context) {
        return resolveDatabase(context) != null || resolveLegacyStorage(context) != null;
    }

    @Override
    public List<ForeignAccount> read(MigrationContext context) throws IOException {
        Path database = resolveDatabase(context);
        if (database != null) {
            return readDatabase(database);
        }

        Path legacy = resolveLegacyStorage(context);
        if (legacy == null) {
            throw new IOException("PlayerPoints data not found under plugins/PlayerPoints");
        }
        return readLegacyYaml(legacy);
    }

    private static List<ForeignAccount> readDatabase(Path database) throws IOException {
        try (Connection conn = SqliteSupport.open(database)) {
            String table = resolvePointsTable(conn);
            if (table == null) {
                throw new IOException("PlayerPoints points table not found in " + database);
            }

            String uuidColumn = SqliteSupport.firstExistingColumn(conn, table, "uuid", "playername");
            String pointsColumn = SqliteSupport.firstExistingColumn(conn, table, "points", "balance");
            if (uuidColumn == null || pointsColumn == null) {
                throw new IOException("PlayerPoints table " + table + " is missing uuid/points columns");
            }

            String usernameTable = resolveUsernameCacheTable(conn);
            List<ForeignAccount> accounts = new ArrayList<>();
            String sql = "SELECT "
                    + quoteIdentifier(uuidColumn) + ", "
                    + quoteIdentifier(pointsColumn)
                    + " FROM " + quoteIdentifier(table);
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    String uuidRaw = rs.getString(1);
                    UUID id = SqliteSupport.parseUuid(uuidRaw);
                    if (id == null) {
                        continue;
                    }
                    int points = rs.getInt(2);
                    String name = lookupUsername(conn, usernameTable, id);
                    if (name == null || name.isBlank()) {
                        name = id.toString().substring(0, 8);
                    }
                    accounts.add(new ForeignAccount(id, sanitizeName(name), BigDecimal.valueOf(points)));
                }
            }
            return List.copyOf(accounts);
        } catch (SQLException e) {
            throw new IOException("Failed to read PlayerPoints database: " + e.getMessage(), e);
        }
    }

    private static List<ForeignAccount> readLegacyYaml(Path storageFile) throws IOException {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(storageFile.toFile());
        ConfigurationSection section = yaml.getConfigurationSection("Points");
        if (section == null) {
            section = yaml.getConfigurationSection("Players");
        }
        if (section == null) {
            throw new IOException("Malformed PlayerPoints storage.yml (missing Points/Players section)");
        }

        List<ForeignAccount> accounts = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            UUID id = SqliteSupport.parseUuid(key);
            if (id == null) {
                continue;
            }
            int points = section.getInt(key);
            String name = id.toString().substring(0, 8);
            accounts.add(new ForeignAccount(id, name, BigDecimal.valueOf(points)));
        }
        return List.copyOf(accounts);
    }

    private static String lookupUsername(Connection conn, String table, UUID id) throws SQLException {
        if (table == null) {
            return null;
        }
        String sql = "SELECT username FROM " + quoteIdentifier(table) + " WHERE uuid = '" + id + "'";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getString(1);
            }
        }
        return null;
    }

    private static String resolvePointsTable(Connection conn) throws SQLException {
        Set<String> tables = SqliteSupport.listTables(conn);
        for (String candidate : List.of("pp_points", "playerpoints_points", "playerpoints", "points")) {
            if (tables.contains(candidate)) {
                return candidate;
            }
        }
        for (String table : tables) {
            String lower = table.toLowerCase(Locale.ROOT);
            if (lower.endsWith("points") && SqliteSupport.firstExistingColumn(conn, table, "uuid", "playername") != null) {
                return table;
            }
        }
        return null;
    }

    private static String resolveUsernameCacheTable(Connection conn) throws SQLException {
        Set<String> tables = SqliteSupport.listTables(conn);
        for (String candidate : List.of("pp_username_cache", "playerpoints_username_cache")) {
            if (tables.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static Path resolvePluginFolder(MigrationContext context) {
        Path override = context.resolveOverride("playerpoints-data", context.pluginsFolder().resolve("PlayerPoints"));
        if (Files.isDirectory(override)) {
            return override;
        }
        Path folder = context.pluginsFolder().resolve("PlayerPoints");
        return Files.isDirectory(folder) ? folder : null;
    }

    private static Path resolveDatabase(MigrationContext context) {
        Path folder = resolvePluginFolder(context);
        if (folder == null) {
            return null;
        }
        Path override = context.resolveOverride("playerpoints-database", folder.resolve("database.db"));
        if (override.toFile().isFile()) {
            return override;
        }
        Path found = SqliteSupport.firstExistingFile(
                folder.resolve("database.db"),
                folder.resolve("storage.db"),
                folder.resolve("data.db"));
        if (found == null) {
            return null;
        }
        try (Connection conn = SqliteSupport.open(found)) {
            return resolvePointsTable(conn) != null ? found : null;
        } catch (SQLException ignored) {
            return null;
        }
    }

    private static Path resolveLegacyStorage(MigrationContext context) {
        Path folder = resolvePluginFolder(context);
        if (folder == null) {
            return null;
        }
        Path storage = folder.resolve("storage.yml");
        return Files.isRegularFile(storage) ? storage : null;
    }

    private static String sanitizeName(String name) {
        String trimmed = name.trim();
        if (trimmed.length() > 16) {
            return trimmed.substring(0, 16);
        }
        return trimmed;
    }

    private static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
