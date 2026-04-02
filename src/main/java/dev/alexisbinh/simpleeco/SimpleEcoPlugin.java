package dev.alexisbinh.simpleeco;

import dev.alexisbinh.simpleeco.api.SimpleEcoApi;
import dev.alexisbinh.simpleeco.api.SimpleEcoApiImpl;
import dev.alexisbinh.simpleeco.command.*;
import dev.alexisbinh.simpleeco.economy.SimpleEcoEconomyProvider;
import dev.alexisbinh.simpleeco.economy.SimpleEcoLegacyEconomyProvider;
import dev.alexisbinh.simpleeco.listener.PlayerConnectionListener;
import dev.alexisbinh.simpleeco.placeholder.SimpleEcoPlaceholderExpansion;
import dev.alexisbinh.simpleeco.service.AccountService;
import dev.alexisbinh.simpleeco.storage.AccountRepository;
import dev.alexisbinh.simpleeco.storage.DatabaseDialect;
import dev.alexisbinh.simpleeco.storage.JdbcAccountRepository;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.milkbowl.vault2.economy.Economy;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;

public class SimpleEcoPlugin extends JavaPlugin {

    private AccountRepository repository;
    private AccountService service;
    private Messages messages;
    private SimpleEcoApi api;
    private ScheduledTask autoSaveTask;
    private ScheduledTask historyPruneTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // ── Storage ──────────────────────────────────────────────────────────
        String dialectStr = getConfig().getString("storage.type", "sqlite");
        DatabaseDialect dialect = DatabaseDialect.fromConfig(dialectStr);
        String filename = switch (dialect) {
            case H2 -> getConfig().getString("storage.h2.file", "economy");
            default -> getConfig().getString("storage.sqlite.file", "economy.db");
        };

        File dataDir = getDataFolder();
        if (!dataDir.exists() && !dataDir.mkdirs()) {
            getLogger().severe("Could not create data folder! Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            repository = new JdbcAccountRepository(dialect, dataDir.getAbsolutePath(), filename);
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
        api = new SimpleEcoApiImpl(service);
        getServer().getServicesManager().register(
            SimpleEcoApi.class, api, this, ServicePriority.Normal);
        getLogger().info("Registered SimpleEco addon API service.");

        // ── VaultUnlocked registration ────────────────────────────────────────
        SimpleEcoEconomyProvider provider = new SimpleEcoEconomyProvider(service);
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
            new PlayerConnectionListener(service, messages, getLogger()), this);

        // ── PlaceholderAPI ────────────────────────────────────────────────────
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new SimpleEcoPlaceholderExpansion(service, getPluginMeta().getVersion()).register();
            getLogger().info("PlaceholderAPI expansion registered.");
        }

        // ── Auto-save scheduler ───────────────────────────────────────────────
        restartAutoSaveTask();

        // ── History prune scheduler ───────────────────────────────────────────
        restartPruneTask();

        getLogger().info("SimpleEco enabled. Backend: " + dialect.name().toLowerCase()
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
    public SimpleEcoApi getApi() { return api; }

    @SuppressWarnings("deprecation")
    private void registerLegacyEconomy(AccountService accountService) {
        SimpleEcoLegacyEconomyProvider legacyProvider = new SimpleEcoLegacyEconomyProvider(accountService);
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
        getLogger().info("SimpleEco disabled.");
    }
}
