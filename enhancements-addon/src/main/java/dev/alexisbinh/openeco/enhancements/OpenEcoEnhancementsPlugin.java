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

package dev.alexisbinh.openeco.enhancements;

import dev.alexisbinh.openeco.api.CurrencyInfo;
import dev.alexisbinh.openeco.api.OpenEcoApi;
import dev.alexisbinh.openeco.api.OpenEcoAsyncApi;
import dev.alexisbinh.openeco.api.EconomyPolicyRegistry;
import dev.alexisbinh.openeco.api.ClusterJobCoordinator;
import dev.alexisbinh.openeco.enhancements.exchange.ExchangeCommand;
import dev.alexisbinh.openeco.enhancements.interest.InterestTask;
import dev.alexisbinh.openeco.enhancements.paylimit.PayLimitListener;
import dev.alexisbinh.openeco.enhancements.permcap.PermCapListener;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class OpenEcoEnhancementsPlugin extends JavaPlugin {

    private OpenEcoApi api;
    private ScheduledTask interestTask;
    private ScheduledTask permissionSnapshotTask;
    private ClusterJobCoordinator clusterJobCoordinator;
    private OpenEcoAsyncApi asyncApi;
    private boolean multiWriter;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        RegisteredServiceProvider<OpenEcoApi> rsp =
                getServer().getServicesManager().getRegistration(OpenEcoApi.class);
        if (rsp == null) {
            getLogger().severe("OpenEcoApi not found — is OpenEco loaded and enabled?");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        api = rsp.getProvider();
        RegisteredServiceProvider<ClusterJobCoordinator> jobRegistration =
                getServer().getServicesManager().getRegistration(ClusterJobCoordinator.class);
        clusterJobCoordinator = jobRegistration == null ? null : jobRegistration.getProvider();
        multiWriter = false;
        org.bukkit.plugin.Plugin corePlugin = getServer().getPluginManager().getPlugin("openeco");
        if (corePlugin instanceof JavaPlugin core) {
            multiWriter = core.getConfig().getBoolean("cross-server.enabled", false)
                    && "multi-writer".equalsIgnoreCase(core.getConfig().getString("cross-server.mode", "multi-writer"));
        }
        RegisteredServiceProvider<EconomyPolicyRegistry> policyRegistration =
                getServer().getServicesManager().getRegistration(EconomyPolicyRegistry.class);
        if (multiWriter && policyRegistration != null) {
            EnhancementsPolicyProvider.PolicySettings policySettings =
                    EnhancementsPolicyProvider.settingsFrom(getConfig());
            EnhancementsPermissionSnapshots permissionSnapshots =
                    new EnhancementsPermissionSnapshots(policySettings);
            getServer().getPluginManager().registerEvents(permissionSnapshots, this);
            policyRegistration.getProvider().register("openeco-enhancements",
                    new EnhancementsPolicyProvider(policySettings, permissionSnapshots.view()));
            permissionSnapshotTask = getServer().getGlobalRegionScheduler().runAtFixedRate(
                    this,
                    ignored -> getServer().getOnlinePlayers().forEach(player ->
                            player.getScheduler().run(this,
                                    task -> permissionSnapshots.refresh(player), null)),
                    1L,
                    20L);
            getLogger().info("Network-wide mutation policies registered.");
        }
        if (multiWriter) {
            RegisteredServiceProvider<OpenEcoAsyncApi> asyncRegistration =
                    getServer().getServicesManager().getRegistration(OpenEcoAsyncApi.class);
            asyncApi = asyncRegistration == null ? null : asyncRegistration.getProvider();
            if (asyncApi == null) {
                getLogger().warning("OpenEcoAsyncApi unavailable; multi-writer interest payouts will be disabled.");
            }
        }

        // ── Pay Limit ────────────────────────────────────────────────────────
        if (!multiWriter && getConfig().getBoolean("pay-limit.enabled", false)) {
            getServer().getPluginManager().registerEvents(new PayLimitListener(api, this), this);
            getLogger().info("Pay limit enabled.");
        }

        // ── Permission Balance Cap ────────────────────────────────────────────
        if (!multiWriter && getConfig().getBoolean("perm-cap.enabled", false)) {
            warnIfPermCapExceedsGlobalLimit(getConfig().getMapList("perm-cap.tiers"), api, getLogger());
            getServer().getPluginManager().registerEvents(new PermCapListener(api, this), this);
            getLogger().info("Permission balance cap enabled.");
        }

        // ── Interest ─────────────────────────────────────────────────────────
        if (getConfig().getBoolean("interest.enabled", false)) {
            startInterestTask();
        }

        // ── Currency Exchange ─────────────────────────────────────────────────
        if (getConfig().getBoolean("exchange.enabled", false)) {
            ExchangeCommand exchangeCommand = new ExchangeCommand(api, this);
            var cmd = getCommand("exchange");
            if (cmd != null) {
                cmd.setExecutor(exchangeCommand);
                cmd.setTabCompleter(exchangeCommand);
            }
            getLogger().info("Currency exchange enabled.");
        }

        getLogger().info("OpenEcoEnhancements enabled.");
    }

    @Override
    public void onDisable() {
        RegisteredServiceProvider<EconomyPolicyRegistry> registration =
                getServer().getServicesManager().getRegistration(EconomyPolicyRegistry.class);
        if (registration != null) registration.getProvider().unregister("openeco-enhancements");
        if (interestTask != null) {
            interestTask.cancel();
        }
        if (permissionSnapshotTask != null) {
            permissionSnapshotTask.cancel();
        }
    }

    private void startInterestTask() {
        if (interestTask != null) interestTask.cancel();
        if (multiWriter && asyncApi == null) {
            getLogger().warning("Interest task disabled because idempotent OpenEcoAsyncApi is unavailable.");
            return;
        }
        long intervalSeconds = getConfig().getLong("interest.interval-seconds", 3600);
        if (intervalSeconds <= 0) {
            getLogger().warning("Interest task disabled because interest.interval-seconds must be > 0.");
            return;
        }
        String configuredCurrencyId = getConfig().getString("interest.currency");
        if (configuredCurrencyId != null && !configuredCurrencyId.isBlank() && !api.hasCurrency(configuredCurrencyId)) {
            getLogger().warning("Interest task disabled because interest.currency '" + configuredCurrencyId + "' is unknown.");
            return;
        }
        long intervalMs = intervalSeconds * 1000L;
        long initialDelayMs = multiWriter
                ? Math.max(1L, intervalMs - Math.floorMod(System.currentTimeMillis(), intervalMs))
                : intervalMs;
        long schedulePeriodMs = multiWriter
                ? InterestTask.retryIntervalMs(intervalMs)
                : intervalMs;
        InterestTask task = new InterestTask(api, this, clusterJobCoordinator, multiWriter ? asyncApi : null);
        interestTask = getServer().getAsyncScheduler().runAtFixedRate(
                this, st -> task.run(), initialDelayMs, schedulePeriodMs, TimeUnit.MILLISECONDS);
        getLogger().info("Interest task scheduled every " + intervalSeconds + "s.");
    }

    static void warnIfPermCapExceedsGlobalLimit(List<Map<?, ?>> tiers, OpenEcoApi api, Logger logger) {
        List<CurrencyInfo> currencies = api.getCurrencies();
        if (currencies == null || currencies.isEmpty()) {
            currencies = List.of(api.getRules().currency());
        }

        for (Map<?, ?> entry : tiers) {
            String permission = entry.get("permission") instanceof String value ? value : null;
            if (!(entry.get("cap") instanceof Number capNumber)) {
                continue;
            }
            String label = permission != null && !permission.isBlank() ? permission : "<unknown permission>";
            for (CurrencyInfo currency : currencies) {
                if (currency == null || currency.maxBalance() == null) {
                    continue;
                }
                BigDecimal tierCap = BigDecimal.valueOf(capNumber.doubleValue())
                        .setScale(currency.fractionalDigits(), RoundingMode.HALF_UP);
                if (tierCap.compareTo(currency.maxBalance()) <= 0) {
                    continue;
                }

                logger.warning("perm-cap tier '" + label + "' configures " + tierCap.toPlainString()
                        + " above OpenEco max-balance " + currency.maxBalance().toPlainString()
                        + " for currency '" + currency.id() + "'; core will still enforce the currency limit.");
            }
        }
    }
}
