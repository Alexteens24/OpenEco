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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Reads The New Economy (TNE) v0.1.2+ data from YAML account files or SQLite storage.
 * Multi-currency balances are merged into OpenEco's target currency (VIRTUAL holdings preferred).
 */
public final class TneEconomyReader implements EconomySourceReader {

    @Override
    public MigrationSource source() {
        return MigrationSource.TNE;
    }

    @Override
    public String describeLocation(MigrationContext context) {
        Path database = resolveDatabase(context);
        if (database != null) {
            return database.toString();
        }
        Path accounts = resolveAccountsFolder(context);
        return accounts == null ? "plugins/TheNewEconomy/accounts/*.yml" : accounts + "/*.yml";
    }

    @Override
    public boolean isAvailable(MigrationContext context) {
        return resolveDatabase(context) != null || resolveAccountsFolder(context) != null;
    }

    @Override
    public List<ForeignAccount> read(MigrationContext context) throws IOException {
        Path database = resolveDatabase(context);
        if (database != null) {
            return readSqlite(database);
        }

        Path accountsFolder = resolveAccountsFolder(context);
        if (accountsFolder == null) {
            throw new IOException("TNE data not found under plugins/TheNewEconomy or plugins/TNE");
        }
        return readYamlAccounts(accountsFolder);
    }

    private static List<ForeignAccount> readSqlite(Path database) throws IOException {
        try (Connection conn = SqliteSupport.open(database)) {
            if (!SqliteSupport.tableExists(conn, "tne_accounts")) {
                throw new IOException("TNE table tne_accounts not found in " + database);
            }

            Map<UUID, AccountRow> rows = new HashMap<>();
            String accountSql = """
                    SELECT uid, username, account_type
                    FROM tne_accounts
                    """;
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(accountSql)) {
                while (rs.next()) {
                    UUID id = parseUid(rs.getObject("uid"));
                    if (id == null) {
                        continue;
                    }
                    String type = rs.getString("account_type");
                    if (type != null && !isPlayerType(type)) {
                        continue;
                    }
                    String name = rs.getString("username");
                    if (name == null || name.isBlank()) {
                        name = id.toString().substring(0, 8);
                    }
                    rows.put(id, new AccountRow(id, sanitizeName(name), BigDecimal.ZERO));
                }
            }

            if (SqliteSupport.tableExists(conn, "tne_holdings")) {
                String holdingsSql = """
                        SELECT uid, holdings_type, holdings
                        FROM tne_holdings
                        """;
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(holdingsSql)) {
                    while (rs.next()) {
                        UUID id = parseUid(rs.getObject("uid"));
                        AccountRow row = id == null ? null : rows.get(id);
                        if (row == null) {
                            continue;
                        }
                        String handler = rs.getString("holdings_type");
                        BigDecimal amount = rs.getBigDecimal("holdings");
                        if (amount == null) {
                            continue;
                        }
                        row.addHolding(handler, amount);
                    }
                }
            }

            List<ForeignAccount> accounts = new ArrayList<>();
            for (AccountRow row : rows.values()) {
                accounts.add(new ForeignAccount(row.id, row.name, row.balance()));
            }
            return List.copyOf(accounts);
        } catch (SQLException e) {
            throw new IOException("Failed to read TNE database: " + e.getMessage(), e);
        }
    }

    private static List<ForeignAccount> readYamlAccounts(Path accountsFolder) throws IOException {
        List<ForeignAccount> accounts = new ArrayList<>();
        try (Stream<Path> files = Files.list(accountsFolder)) {
            files.filter(path -> path.getFileName().toString().endsWith(".yml"))
                    .forEach(path -> readYamlFile(path, accounts));
        }
        return List.copyOf(accounts);
    }

    private static void readYamlFile(Path file, List<ForeignAccount> accounts) {
        String fileName = file.getFileName().toString();
        String uuidPart = fileName.substring(0, fileName.length() - 4);
        UUID id = SqliteSupport.parseUuid(uuidPart);

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        if (yaml.contains("Info.ID")) {
            UUID fromFile = SqliteSupport.parseUuid(yaml.getString("Info.ID"));
            if (fromFile != null) {
                id = fromFile;
            }
        }
        if (id == null) {
            return;
        }

        String type = yaml.getString("Info.Type");
        if (type != null && !isPlayerType(type)) {
            return;
        }

        BigDecimal balance = sumHoldings(yaml.getConfigurationSection("Holdings"));
        if (balance.signum() == 0 && !yaml.contains("Holdings")) {
            return;
        }

        String name = yaml.getString("Info.Name");
        if (name == null || name.isBlank()) {
            name = id.toString().substring(0, 8);
        }
        accounts.add(new ForeignAccount(id, sanitizeName(name), balance));
    }

    private static BigDecimal sumHoldings(ConfigurationSection holdings) {
        if (holdings == null) {
            return BigDecimal.ZERO;
        }

        BigDecimal virtual = BigDecimal.ZERO;
        BigDecimal all = BigDecimal.ZERO;
        for (String server : holdings.getKeys(false)) {
            ConfigurationSection serverSection = holdings.getConfigurationSection(server);
            if (serverSection == null) {
                continue;
            }
            for (String region : serverSection.getKeys(false)) {
                ConfigurationSection regionSection = serverSection.getConfigurationSection(region);
                if (regionSection == null) {
                    continue;
                }
                for (String currency : regionSection.getKeys(false)) {
                    ConfigurationSection currencySection = regionSection.getConfigurationSection(currency);
                    if (currencySection == null) {
                        continue;
                    }
                    for (String handler : currencySection.getKeys(false)) {
                        BigDecimal amount = parseDecimal(currencySection.getString(handler));
                        all = all.add(amount);
                        if ("VIRTUAL".equalsIgnoreCase(handler)) {
                            virtual = virtual.add(amount);
                        }
                    }
                }
            }
        }
        return virtual.signum() > 0 ? virtual : all;
    }

    private static BigDecimal parseDecimal(String raw) {
        if (raw == null || raw.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException ignored) {
            return BigDecimal.ZERO;
        }
    }

    private static boolean isPlayerType(String type) {
        String normalized = type.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("player") || normalized.equals("bedrock");
    }

    private static UUID parseUid(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof UUID uuid) {
            return uuid;
        }
        if (raw instanceof byte[] bytes) {
            if (bytes.length != 16) {
                return null;
            }
            long msb = 0;
            long lsb = 0;
            for (int i = 0; i < 8; i++) {
                msb = (msb << 8) | (bytes[i] & 0xff);
            }
            for (int i = 8; i < 16; i++) {
                lsb = (lsb << 8) | (bytes[i] & 0xff);
            }
            return new UUID(msb, lsb);
        }
        return SqliteSupport.parseUuid(raw.toString());
    }

    private static String sanitizeName(String name) {
        String trimmed = name.trim();
        if (trimmed.length() > 16) {
            return trimmed.substring(0, 16);
        }
        return trimmed;
    }

    private static Path resolvePluginFolder(MigrationContext context) {
        Path override = context.resolveOverride("tne-data", context.pluginsFolder().resolve("TheNewEconomy"));
        if (Files.isDirectory(override)) {
            return override;
        }
        Path tne = context.pluginsFolder().resolve("TNE");
        if (Files.isDirectory(tne)) {
            return tne;
        }
        Path theNewEconomy = context.pluginsFolder().resolve("TheNewEconomy");
        if (Files.isDirectory(theNewEconomy)) {
            return theNewEconomy;
        }
        return null;
    }

    private static Path resolveAccountsFolder(MigrationContext context) {
        Path pluginFolder = resolvePluginFolder(context);
        if (pluginFolder == null) {
            return null;
        }
        Path accounts = pluginFolder.resolve("accounts");
        return Files.isDirectory(accounts) ? accounts : null;
    }

    private static Path resolveDatabase(MigrationContext context) {
        Path pluginFolder = resolvePluginFolder(context);
        if (pluginFolder == null) {
            return null;
        }
        Path override = context.resolveOverride("tne-database", pluginFolder.resolve("database.db"));
        if (override.toFile().isFile()) {
            return override;
        }
        Path found = SqliteSupport.firstExistingFile(
                pluginFolder.resolve("database.db"),
                pluginFolder.resolve("data.db"),
                pluginFolder.resolve("storage.db"),
                pluginFolder.resolve("economy.db"));
        if (found == null) {
            return null;
        }
        try (Connection conn = SqliteSupport.open(found)) {
            return SqliteSupport.tableExists(conn, "tne_accounts") ? found : null;
        } catch (SQLException ignored) {
            return null;
        }
    }

    private static final class AccountRow {
        private final UUID id;
        private final String name;
        private BigDecimal virtual = BigDecimal.ZERO;
        private BigDecimal all = BigDecimal.ZERO;

        private AccountRow(UUID id, String name, BigDecimal ignored) {
            this.id = id;
            this.name = name;
        }

        private void addHolding(String handler, BigDecimal amount) {
            all = all.add(amount);
            if (handler != null && handler.equalsIgnoreCase("VIRTUAL")) {
                virtual = virtual.add(amount);
            }
        }

        private BigDecimal balance() {
            return virtual.signum() > 0 ? virtual : all;
        }
    }
}
