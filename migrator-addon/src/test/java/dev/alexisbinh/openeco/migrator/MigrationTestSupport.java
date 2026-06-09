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

import dev.alexisbinh.openeco.migrator.source.MigrationContext;
import dev.alexisbinh.openeco.service.AccountService;
import dev.alexisbinh.openeco.storage.AccountRepository;
import dev.alexisbinh.openeco.storage.JdbcAccountRepository;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public final class MigrationTestSupport {

    private MigrationTestSupport() {
    }

    public static MigrationContext context(Path pluginsDir) {
        JavaPlugin plugin = mock(JavaPlugin.class);
        File addonData = pluginsDir.resolve("OpenEcoMigrator").toFile();
        addonData.mkdirs();
        when(plugin.getDataFolder()).thenReturn(addonData);
        return new MigrationContext(plugin, pluginsDir, Map.of());
    }

    public static void createEssentialsUser(Path pluginsDir, UUID id, String name, String balance) throws Exception {
        Path userdata = pluginsDir.resolve("Essentials").resolve("userdata");
        userdata.toFile().mkdirs();
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("money", balance);
        yaml.set("last-account-name", name);
        yaml.save(userdata.resolve(id + ".yml").toFile());
    }

    public static Path createLiteEcoDatabase(Path pluginsDir, UUID id, String name, double balance) throws Exception {
        Path db = pluginsDir.resolve("LiteEco").resolve("database.db");
        db.getParent().toFile().mkdirs();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE lite_eco (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        uuid TEXT NOT NULL,
                        username TEXT NOT NULL,
                        money REAL NOT NULL
                    )
                    """);
            stmt.execute("INSERT INTO lite_eco (uuid, username, money) VALUES ('"
                    + id + "', '" + name + "', " + balance + ")");
        }
        return db;
    }

    public static Path createXConomyDatabase(Path pluginsDir, UUID id, String name, double balance) throws Exception {
        Path db = pluginsDir.resolve("XConomy").resolve("playerdata").resolve("Default").resolve("data.db");
        db.getParent().toFile().mkdirs();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE xconomy (
                        UID TEXT PRIMARY KEY,
                        playername TEXT NOT NULL,
                        balance REAL NOT NULL
                    )
                    """);
            stmt.execute("INSERT INTO xconomy (UID, playername, balance) VALUES ('"
                    + id + "', '" + name + "', " + balance + ")");
        }
        return db;
    }

    public static Path createCmiDatabase(Path pluginsDir, UUID id, String name, double balance) throws Exception {
        Path db = pluginsDir.resolve("CMI").resolve("cmi.sqlite.db");
        db.getParent().toFile().mkdirs();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE users (
                        player_uuid TEXT NOT NULL,
                        username TEXT NOT NULL,
                        Balance REAL NOT NULL
                    )
                    """);
            stmt.execute("INSERT INTO users (player_uuid, username, Balance) VALUES ('"
                    + id + "', '" + name + "', " + balance + ")");
        }
        return db;
    }

    static YamlConfiguration openEcoConfig() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("accounts.load-strategy", "eager");
        config.set("currencies.default", "openeco");
        config.set("currencies.definitions.openeco.name-singular", "Dollar");
        config.set("currencies.definitions.openeco.name-plural", "Dollars");
        config.set("currencies.definitions.openeco.decimal-digits", 2);
        config.set("currencies.definitions.openeco.starting-balance", 0.00);
        config.set("currencies.definitions.openeco.max-balance", -1);
        config.set("pay.cooldown-seconds", 0);
        config.set("pay.tax-percent", 0.0);
        config.set("pay.min-amount", 0.01);
        config.set("baltop.cache-ttl-seconds", 30);
        config.set("history.retention-days", -1);
        return config;
    }

    public static AccountService newOpenEcoService(JdbcAccountRepository repository) {
        try {
            Class<?> dispatcherClass = Class.forName("dev.alexisbinh.openeco.service.EventDispatcher");
            Object noOpDispatcher = java.lang.reflect.Proxy.newProxyInstance(
                    AccountService.class.getClassLoader(),
                    new Class<?>[] {dispatcherClass},
                    (proxy, method, args) -> null);
            Constructor<AccountService> ctor = AccountService.class.getDeclaredConstructor(
                    AccountRepository.class,
                    Logger.class,
                    String.class,
                    FileConfiguration.class,
                    dispatcherClass);
            ctor.setAccessible(true);
            return ctor.newInstance(
                    repository,
                    Logger.getLogger("openeco-migrator-test"),
                    "openeco-migrator-test",
                    openEcoConfig(),
                    noOpDispatcher);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to create AccountService for migrator tests", e);
        }
    }
}
