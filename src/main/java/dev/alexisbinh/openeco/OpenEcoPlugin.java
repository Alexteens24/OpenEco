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
import dev.alexisbinh.openeco.api.OpenEcoAsyncApi;
import dev.alexisbinh.openeco.api.OpenEcoAsyncApiImpl;
import dev.alexisbinh.openeco.api.EconomyPolicyRegistry;
import dev.alexisbinh.openeco.api.ClusterJobCoordinator;
import dev.alexisbinh.openeco.command.*;
import dev.alexisbinh.openeco.crossserver.CrossServerMessenger;
import dev.alexisbinh.openeco.crossserver.RedisChangeBus;
import dev.alexisbinh.openeco.economy.OpenEcoEconomyProvider;
import dev.alexisbinh.openeco.economy.OpenEcoLegacyEconomyProvider;
import dev.alexisbinh.openeco.listener.PlayerConnectionListener;
import dev.alexisbinh.openeco.placeholder.OpenEcoPlaceholderExpansion;
import dev.alexisbinh.openeco.service.AccountService;
import dev.alexisbinh.openeco.storage.AccountRepository;
import dev.alexisbinh.openeco.storage.DatabaseDialect;
import dev.alexisbinh.openeco.storage.JdbcAccountRepository;
import dev.alexisbinh.openeco.storage.RemoteStorageDataSource;
import dev.faststats.bukkit.BukkitContext;
import dev.faststats.data.Metric;
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
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class OpenEcoPlugin extends JavaPlugin {

    private static final String FASTSTATS_TOKEN = "83b1ce7d24e774f11a19baa64a90e367";

    private AccountRepository repository;
    private AccountService service;
    private Messages messages;
    private OpenEcoApi api;
    private OpenEcoAsyncApiImpl asyncApi;
    private ScheduledTask autoSaveTask;
    private ScheduledTask historyPruneTask;
    private ScheduledTask leaderboardRefreshTask;
    private ScheduledTask multiWriterPollTask;
    private ScheduledTask accountCacheMaintenanceTask;
    private BukkitContext fastStatsContext;
    private RedisChangeBus redisChangeBus;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrateConfig();
        reloadConfig();

        // ── Storage ──────────────────────────────────────────────────────────
        String dialectStr = getConfig().getString("storage.type", "sqlite");
        DatabaseDialect dialect;
        try {
            dialect = DatabaseDialect.fromConfig(dialectStr);
        } catch (IllegalArgumentException e) {
            getLogger().severe("Invalid config: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (getConfig().getBoolean("cross-server.enabled", false) && dialect.isLocal()) {
            getLogger().severe("Invalid config: cross-server.enabled requires mysql, mariadb, or postgresql storage.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

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
                if (filename == null || filename.isBlank()) {
                    String path = dialect == DatabaseDialect.H2 ? "storage.h2.file" : "storage.sqlite.file";
                    throw new IllegalArgumentException(path + " must not be blank");
                }
                repository = new JdbcAccountRepository(
                        dialect,
                        dataDir.getAbsolutePath(),
                        filename.trim(),
                        resolveDefaultCurrencyId());
            } else {
                repository = new JdbcAccountRepository(
                        buildRemoteDataSource(dialect),
                        dialect,
                        resolveDefaultCurrencyId());
            }
        } catch (SQLException | IllegalArgumentException e) {
            getLogger().severe("Failed to open database: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // ── Service ───────────────────────────────────────────────────────────
        try {
            validatePluginRuntimeConfig();
            service = new AccountService(repository, this, getConfig());
            service.loadAll();
        } catch (SQLException | IllegalArgumentException e) {
            getLogger().severe("Invalid configuration or failed account load: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // ── Messages ──────────────────────────────────────────────────────────
        messages = new Messages(getConfig());

        // ── Public API ────────────────────────────────────────────────────────
        api = new OpenEcoApiImpl(service);
        getServer().getServicesManager().register(
            OpenEcoApi.class, api, this, ServicePriority.Normal);
        asyncApi = new OpenEcoAsyncApiImpl(api, service, getName(), 4);
        getServer().getServicesManager().register(
                OpenEcoAsyncApi.class, asyncApi, this, ServicePriority.Normal);
        getServer().getServicesManager().register(
                EconomyPolicyRegistry.class, service.getPolicyRegistry(), this, ServicePriority.Normal);
        getServer().getServicesManager().register(
                ClusterJobCoordinator.class, service.getClusterJobCoordinator(), this, ServicePriority.Normal);
        getLogger().info("Registered openeco addon API service.");

        // ── VaultUnlocked registration (optional) ─────────────────────────────
        registerVaultUnlockedEconomy(service, asyncApi);

        // ── Legacy Vault v1 registration (for ShopGUI+, SmartSpawner, etc.) ───
        registerLegacyEconomy(service);

        // ── Commands ──────────────────────────────────────────────────────────
        BalanceCommand balance = new BalanceCommand(this, service, messages);
        getCommand("balance").setExecutor(balance);
        getCommand("balance").setTabCompleter(balance);
        BalTopCommand balTop = new BalTopCommand(this, service, messages);
        getCommand("baltop").setExecutor(balTop);
        getCommand("baltop").setTabCompleter(balTop);
        PayCommand pay = new PayCommand(this, service, messages);
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

        // ── Leaderboard refresh scheduler ────────────────────────────────────
        restartLeaderboardRefreshTask();

        // ── Lazy account cache maintenance ─────────────────────────────────
        restartAccountCacheMaintenanceTask();

        // ── History prune scheduler ───────────────────────────────────────────
        restartPruneTask();

        restartMultiWriterPollTask();
        startRedisChangeBus();

        // ── bStats (optional) ────────────────────────────────────────────────
        try {
            new Metrics(this, 30556);
        } catch (IllegalStateException | LinkageError ex) {
            getLogger().warning("bStats metrics disabled: " + ex.getMessage());
        }

        // ── FastStats (optional) ─────────────────────────────────────────────
        startFastStats(dialect);

        getLogger().info("openeco enabled. Backend: " + dialect.name().toLowerCase());
    }

    public boolean reloadSettings() {
        migrateConfig();
        reloadConfig();
        try {
            validatePluginRuntimeConfig();
            if (service != null) {
                service.reloadConfig(getConfig());
            }
        } catch (IllegalArgumentException e) {
            getLogger().warning("Config reload rejected: " + e.getMessage());
            return false;
        }
        if (messages != null) {
            messages.reload(getConfig());
        }
        restartAutoSaveTask();
        restartLeaderboardRefreshTask();
        restartAccountCacheMaintenanceTask();
        restartPruneTask();
        restartMultiWriterPollTask();
        return true;
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

        long intervalSec = getConfig().getLong("persistence.autosave-interval-seconds", 30);
        autoSaveTask = getServer().getAsyncScheduler().runAtFixedRate(
                this,
                task -> service.flushDirty(),
                intervalSec,
                intervalSec,
                TimeUnit.SECONDS);
    }

    private void validatePluginRuntimeConfig() {
        long autosaveInterval = getConfig().getLong("persistence.autosave-interval-seconds", 30);
        if (autosaveInterval <= 0) {
            throw new IllegalArgumentException(
                    "persistence.autosave-interval-seconds must be greater than 0");
        }
        dev.alexisbinh.openeco.service.CrossServerMode.fromConfig(
                getConfig().getString("cross-server.mode", "multi-writer"));
        long refreshMs = getConfig().getLong("cross-server.cache-refresh-interval-ms", 250L);
        if (refreshMs < 50L || refreshMs > 5_000L) {
            throw new IllegalArgumentException(
                    "cross-server.cache-refresh-interval-ms must be between 50 and 5000");
        }
    }

    private void restartPruneTask() {
        if (historyPruneTask != null) {
            historyPruneTask.cancel();
            historyPruneTask = null;
        }
        int retentionDays = getConfig().getInt("history.retention-days", -1);
        int changeRetentionDays = getConfig().getInt("cross-server.change-retention-days", 7);
        if (retentionDays > 0 || (service != null && service.isMultiWriterEnabled() && changeRetentionDays > 0)) {
            // Run once shortly after enable/reload, then every 24 h.
            historyPruneTask = getServer().getAsyncScheduler().runAtFixedRate(
                    this,
                    task -> {
                        service.pruneHistory();
                        service.pruneMultiWriterChanges(changeRetentionDays);
                    },
                    1,
                    86_400,
                    TimeUnit.SECONDS);
        }
    }

    private void restartLeaderboardRefreshTask() {
        if (leaderboardRefreshTask != null) {
            leaderboardRefreshTask.cancel();
        }
        long intervalSeconds = Math.max(1L, service.getBalTopCacheTtlMs() / 1000L);
        leaderboardRefreshTask = getServer().getAsyncScheduler().runAtFixedRate(
                this,
                task -> service.refreshLeaderboards(),
                intervalSeconds,
                intervalSeconds,
                TimeUnit.SECONDS);
    }

    private void restartMultiWriterPollTask() {
        if (multiWriterPollTask != null) {
            multiWriterPollTask.cancel();
            multiWriterPollTask = null;
        }
        if (service == null || !service.isMultiWriterEnabled()) return;
        long intervalMs = Math.max(50L, Math.min(5_000L,
                getConfig().getLong("cross-server.cache-refresh-interval-ms", 250L)));
        int batchSize = Math.max(1, getConfig().getInt("cross-server.poll-batch-size", 1_000));
        multiWriterPollTask = getServer().getAsyncScheduler().runAtFixedRate(
                this, task -> service.pollMultiWriterChanges(batchSize),
                intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    private void startRedisChangeBus() {
        if (redisChangeBus != null) {
            redisChangeBus.close();
            redisChangeBus = null;
        }
        service.setChangeNotifier(null);
        if (!service.isMultiWriterEnabled()
                || !getConfig().getBoolean("cross-server.redis.enabled", false)) return;
        String uri = getConfig().getString("cross-server.redis.uri", "redis://localhost:6379");
        String channel = getConfig().getString("cross-server.redis.channel", "openeco:changes");
        try {
            int batchSize = Math.max(1, getConfig().getInt("cross-server.poll-batch-size", 1_000));
            redisChangeBus = new RedisChangeBus(uri, channel, getLogger(), () ->
                    getServer().getAsyncScheduler().runNow(this,
                            task -> service.pollMultiWriterChanges(batchSize)));
            service.setChangeNotifier(redisChangeBus);
            getLogger().info("Redis change notifications enabled; JDBC polling remains active.");
        } catch (RuntimeException | LinkageError e) {
            getLogger().warning("Redis notifications unavailable; using JDBC polling: " + e.getMessage());
        }
    }

    private void restartAccountCacheMaintenanceTask() {
        if (accountCacheMaintenanceTask != null) {
            accountCacheMaintenanceTask.cancel();
            accountCacheMaintenanceTask = null;
        }
        if (!service.isLazyAccountModeEnabled()) return;
        long intervalSeconds = service.getAccountCacheMaintenanceIntervalSeconds();
        accountCacheMaintenanceTask = getServer().getAsyncScheduler().runAtFixedRate(
                this,
                task -> service.maintainAccountCache(),
                intervalSeconds,
                intervalSeconds,
                TimeUnit.SECONDS);
    }

    private void startFastStats(DatabaseDialect dialect) {
        String storageBackend = dialect.name().toLowerCase(Locale.ROOT);
        String[] integrations = getServer().getPluginManager().getPlugin("PlaceholderAPI") == null
                ? new String[]{"vault"}
                : new String[]{"vault", "placeholderapi"};
        try {
            fastStatsContext = new BukkitContext.Factory(this, FASTSTATS_TOKEN)
                    .metrics(factory -> factory
                            .addMetric(Metric.string("storage_backend", () -> storageBackend))
                            .addMetric(Metric.number("account_count", service::getAccountCount))
                            .addMetric(Metric.number("currency_count", service::getCurrencyCount))
                            .addMetric(Metric.bool("cross_server_enabled", service::isCrossServerEnabled))
                            .addMetric(Metric.stringArray("integrations", integrations::clone))
                            .create())
                    .create();
            fastStatsContext.ready();
        } catch (RuntimeException | LinkageError ex) {
            shutdownFastStats();
            getLogger().warning("FastStats metrics disabled: " + ex.getMessage());
        }
    }

    private void shutdownFastStats() {
        BukkitContext context = fastStatsContext;
        fastStatsContext = null;
        if (context == null) {
            return;
        }
        try {
            context.shutdown();
        } catch (RuntimeException | LinkageError ex) {
            getLogger().warning("Failed to shut down FastStats metrics: " + ex.getMessage());
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

    private void registerVaultUnlockedEconomy(AccountService accountService, OpenEcoAsyncApi asyncApi) {
        try {
            OpenEcoEconomyProvider provider = new OpenEcoEconomyProvider(accountService, asyncApi);
            getServer().getServicesManager().register(
                    Economy.class, provider, this, ServicePriority.Normal);
            getLogger().info("Registered as VaultUnlocked v2 Economy provider.");
        } catch (LinkageError ex) {
            getLogger().info("VaultUnlocked v2 API not detected; skipping v2 provider registration.");
        }
    }

    @Override
    public void onDisable() {
        shutdownFastStats();
        if (redisChangeBus != null) {
            redisChangeBus.close();
            redisChangeBus = null;
        }
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
        }
        if (historyPruneTask != null) {
            historyPruneTask.cancel();
        }
        if (leaderboardRefreshTask != null) {
            leaderboardRefreshTask.cancel();
        }
        if (multiWriterPollTask != null) {
            multiWriterPollTask.cancel();
        }
        if (accountCacheMaintenanceTask != null) {
            accountCacheMaintenanceTask.cancel();
        }
        getServer().getServicesManager().unregisterAll(this);
        if (asyncApi != null) {
            asyncApi.close();
        }
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
