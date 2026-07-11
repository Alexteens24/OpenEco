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

package dev.alexisbinh.openeco;

import dev.alexisbinh.openeco.api.OpenEcoApi;
import dev.alexisbinh.openeco.api.OpenEcoApiImpl;
import dev.alexisbinh.openeco.command.*;
import dev.alexisbinh.openeco.crossserver.CrossServerMessenger;
import dev.alexisbinh.openeco.economy.OpenEcoEconomyProvider;
import dev.alexisbinh.openeco.economy.OpenEcoLegacyEconomyProvider;
import dev.alexisbinh.openeco.listener.PlayerConnectionListener;
import dev.alexisbinh.openeco.placeholder.OpenEcoPlaceholderExpansion;
import dev.alexisbinh.openeco.service.AccountService;
import dev.alexisbinh.openeco.storage.AccountRepository;
import dev.alexisbinh.openeco.storage.DatabaseDialect;
import dev.alexisbinh.openeco.storage.JdbcAccountRepository;
import dev.alexisbinh.openeco.storage.RemoteStorageDataSource;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.milkbowl.vault2.economy.Economy;
import org.bstats.bukkit.Metrics;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public class OpenEcoPlugin extends JavaPlugin {

    private AccountRepository repository;
    private AccountService service;
    private Messages messages;
    private OpenEcoApi api;
    private ScheduledTask autoSaveTask;
    private ScheduledTask historyPruneTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrateConfig();
        reloadConfig();

        // ── Storage ──────────────────────────────────────────────────────────
        String dialectStr = getConfig().getString("storage.type", "sqlite");
        DatabaseDialect dialect = DatabaseDialect.fromConfig(dialectStr);

        File dataDir = getDataFolder();
        if (!dataDir.exists() && !dataDir.mkdirs()) {
            getLogger().severe("Could not create data folder! Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            if (dialect.isLocal()) {
                String filename = switch (dialect) {
                    case H2 -> getConfig().getString("storage.h2.file", "economy");
                    default -> getConfig().getString("storage.sqlite.file", "economy.db");
                };
                repository = new JdbcAccountRepository(
                        dialect,
                        dataDir.getAbsolutePath(),
                        filename,
                        resolveDefaultCurrencyId());
            } else {
                repository = new JdbcAccountRepository(
                        buildRemoteDataSource(dialect),
                        dialect,
                        resolveDefaultCurrencyId());
            }
        } catch (SQLException e) {
            getLogger().severe("Failed to open database: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // ── Service ───────────────────────────────────────────────────────────
        service = new AccountService(repository, this, getConfig());
        try {
            service.loadAll();
        } catch (SQLException e) {
            getLogger().severe("Failed to load accounts: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // ── Messages ──────────────────────────────────────────────────────────
        messages = new Messages(getConfig());

        // ── Public API ────────────────────────────────────────────────────────
        api = new OpenEcoApiImpl(service);
        getServer().getServicesManager().register(
            OpenEcoApi.class, api, this, ServicePriority.Normal);
        getLogger().info("Registered openeco addon API service.");

        // ── VaultUnlocked registration (optional) ─────────────────────────────
        registerVaultUnlockedEconomy(service);

        // ── Legacy Vault v1 registration (for ShopGUI+, SmartSpawner, etc.) ───
        registerLegacyEconomy(service);

        // ── Commands ──────────────────────────────────────────────────────────
        BalanceCommand balance = new BalanceCommand(service, messages);
        getCommand("balance").setExecutor(balance);
        getCommand("balance").setTabCompleter(balance);
        BalTopCommand balTop = new BalTopCommand(service, this, messages);
        getCommand("baltop").setExecutor(balTop);
        getCommand("baltop").setTabCompleter(balTop);
        PayCommand pay = new PayCommand(service, messages);
        getCommand("pay").setExecutor(pay);
        getCommand("pay").setTabCompleter(pay);
        EcoCommand eco = new EcoCommand(service, this, messages);
        getCommand("eco").setExecutor(eco);
        getCommand("eco").setTabCompleter(eco);
        HistoryCommand history = new HistoryCommand(service, this, messages);
        getCommand("history").setExecutor(history);
        getCommand("history").setTabCompleter(history);
        MigrateCommand migrate = new MigrateCommand(this, service);
        getCommand("openecomigrate").setExecutor(migrate);
        getCommand("openecomigrate").setTabCompleter(migrate);

        // ── Listener ──────────────────────────────────────────────────────────
        getServer().getPluginManager().registerEvents(
            new PlayerConnectionListener(service, messages, getLogger(), this), this);

        // ── Cross-server plugin messaging channel ─────────────────────────────
        if (service.isCrossServerEnabled()) {
            new CrossServerMessenger(this, service, getLogger()).register();
        }

        // ── PlaceholderAPI ────────────────────────────────────────────────────
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new OpenEcoPlaceholderExpansion(service, getPluginMeta().getVersion()).register();
            getLogger().info("PlaceholderAPI expansion registered.");
        }

        // ── Auto-save scheduler ───────────────────────────────────────────────
        restartAutoSaveTask();

        // ── History prune scheduler ───────────────────────────────────────────
        restartPruneTask();

        // ── bStats (optional) ────────────────────────────────────────────────
        try {
            new Metrics(this, 30556);
        } catch (IllegalStateException | LinkageError ex) {
            getLogger().warning("bStats metrics disabled: " + ex.getMessage());
        }

        String loadStrategy = service.isLiveAccountLoadingEnabled()
                ? "live"
                : service.isLazyAccountLoadingEnabled() ? "lazy" : "eager";
        getLogger().info("openeco enabled. Backend: " + dialect.name().toLowerCase()
            + " | Account load strategy: " + loadStrategy);
    }

    public void reloadSettings() {
        migrateConfig();
        reloadConfig();
        if (service != null) {
            service.reloadConfig(getConfig());
        }
        if (messages != null) {
            messages.reload(getConfig());
        }
        restartAutoSaveTask();
        restartPruneTask();
    }

    private com.zaxxer.hikari.HikariDataSource buildRemoteDataSource(DatabaseDialect dialect) {
        return RemoteStorageDataSource.create(dialect, getConfig());
    }

    public AccountRepository getRepository() {
        return repository;
    }

    public String resolveDefaultCurrencyId() {
        String configured = getConfig().getString("currencies.default");
        if (configured == null || configured.isBlank()) {
            configured = getConfig().getString("currency.id", "openeco");
        }
        return configured == null || configured.isBlank() ? "openeco" : configured.trim();
    }

    private void migrateConfig() {
        File configFile = new File(getDataFolder(), "config.yml");
        YamlConfiguration currentConfig = YamlConfiguration.loadConfiguration(configFile);
        String originalYaml = currentConfig.saveToString();
        try (InputStream defaultConfigStream = getResource("config.yml")) {
            if (defaultConfigStream == null) {
                getLogger().warning("Could not load bundled config.yml for migration; skipping config migration.");
                return;
            }

            YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaultConfigStream, StandardCharsets.UTF_8));
            YamlConfiguration migratedConfig = ConfigMigrator.rewrite(currentConfig, defaultConfig);
            if (!migratedConfig.saveToString().equals(originalYaml)) {
                migratedConfig.save(configFile);
                getLogger().info("Migrated OpenEco config to the latest schema.");
            }
        } catch (IOException e) {
            getLogger().warning("Failed to migrate config.yml: " + e.getMessage());
        }
    }

    private void restartAutoSaveTask() {
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
        }

        long configuredIntervalSec = getConfig().getLong("autosave-interval", 300);
        long intervalSec = Math.max(1L, configuredIntervalSec);
        if (configuredIntervalSec != intervalSec) {
            getLogger().warning("autosave-interval must be greater than 0. Clamping to 1 second.");
        }
        autoSaveTask = getServer().getAsyncScheduler().runAtFixedRate(
                this,
                task -> service.flushDirty(),
                intervalSec,
                intervalSec,
                TimeUnit.SECONDS);
    }

    private void restartPruneTask() {
        if (historyPruneTask != null) {
            historyPruneTask.cancel();
            historyPruneTask = null;
        }
        int retentionDays = getConfig().getInt("history.retention-days", -1);
        if (retentionDays > 0) {
            // Run once shortly after enable/reload, then every 24 h.
            historyPruneTask = getServer().getAsyncScheduler().runAtFixedRate(
                    this,
                    task -> service.pruneHistory(),
                    1,
                    86_400,
                    TimeUnit.SECONDS);
        }
    }

    /** Returns the public addon API. Prefer ServicesManager when integrating from other plugins. */
    public OpenEcoApi getApi() { return api; }

    @SuppressWarnings("deprecation")
    private void registerLegacyEconomy(AccountService accountService) {
        OpenEcoLegacyEconomyProvider legacyProvider = new OpenEcoLegacyEconomyProvider(accountService);
        getServer().getServicesManager().register(
                net.milkbowl.vault.economy.Economy.class, legacyProvider, this, ServicePriority.Normal);
        getLogger().info("Registered as legacy Vault v1 Economy provider.");
    }

    private void registerVaultUnlockedEconomy(AccountService accountService) {
        try {
            OpenEcoEconomyProvider provider = new OpenEcoEconomyProvider(accountService);
            getServer().getServicesManager().register(
                    Economy.class, provider, this, ServicePriority.Normal);
            getLogger().info("Registered as VaultUnlocked v2 Economy provider.");
        } catch (LinkageError ex) {
            getLogger().info("VaultUnlocked v2 API not detected; skipping v2 provider registration.");
        }
    }

    @Override
    public void onDisable() {
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
        }
        if (historyPruneTask != null) {
            historyPruneTask.cancel();
        }
        getServer().getServicesManager().unregisterAll(this);
        if (service != null) {
            service.shutdown();
        }
        if (repository != null) {
            try {
                repository.close();
            } catch (SQLException e) {
                getLogger().severe("Failed to close database: " + e.getMessage());
            }
        }
        getLogger().info("openeco disabled.");
    }
}
