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
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.alexisbinh.openeco.storage.AccountRepository;
import dev.alexisbinh.openeco.storage.DatabaseDialect;
import dev.alexisbinh.openeco.storage.JdbcAccountRepository;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.milkbowl.vault2.economy.Economy;
import org.bstats.bukkit.Metrics;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.SQLException;
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

        // ── VaultUnlocked registration ────────────────────────────────────────
        OpenEcoEconomyProvider provider = new OpenEcoEconomyProvider(service);
        getServer().getServicesManager().register(
                Economy.class, provider, this, ServicePriority.Normal);
        getLogger().info("Registered as VaultUnlocked v2 Economy provider.");

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

        // ── bStats ────────────────────────────────────────────────────────────
        new Metrics(this, 30556);

        getLogger().info("openeco enabled. Backend: " + dialect.name().toLowerCase()
                + " | Accounts loaded: true");
    }

    public void reloadSettings() {
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

    private HikariDataSource buildRemoteDataSource(DatabaseDialect dialect) {
        String section = dialect.name().toLowerCase(java.util.Locale.ROOT);
        String host = getConfig().getString("storage." + section + ".host", "localhost");
        int defaultPort = (dialect == DatabaseDialect.POSTGRESQL) ? 5432 : 3306;
        int port = getConfig().getInt("storage." + section + ".port", defaultPort);
        String database = getConfig().getString("storage." + section + ".database", "openeco");
        String username = getConfig().getString("storage." + section + ".username", "root");
        String password = getConfig().getString("storage." + section + ".password", "");
        int poolSize = getConfig().getInt("storage." + section + ".pool-size", 10);

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(switch (dialect) {
            case MYSQL    -> "jdbc:mysql://" + host + ":" + port + "/" + database
                           + "?useSSL=false&serverTimezone=UTC&characterEncoding=utf8&allowReconnect=true";
            case MARIADB  -> "jdbc:mariadb://" + host + ":" + port + "/" + database
                           + "?characterEncoding=utf8";
            case POSTGRESQL -> "jdbc:postgresql://" + host + ":" + port + "/" + database;
            default -> throw new IllegalStateException("Unexpected remote dialect: " + dialect);
        });
        cfg.setUsername(username);
        cfg.setPassword(password);
        cfg.setMaximumPoolSize(poolSize);
        cfg.setMinimumIdle(Math.min(2, poolSize));
        cfg.setConnectionTimeout(10_000);
        cfg.setPoolName("OpenEco-" + dialect.name());
        return new HikariDataSource(cfg);
    }

    private String resolveDefaultCurrencyId() {
        String configured = getConfig().getString("currencies.default");
        if (configured == null || configured.isBlank()) {
            configured = getConfig().getString("currency.id", "openeco");
        }
        return configured == null || configured.isBlank() ? "openeco" : configured.trim();
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
