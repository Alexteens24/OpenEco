package dev.alexisbinh.openeco.enhancements;

import dev.alexisbinh.openeco.api.CurrencyInfo;
import dev.alexisbinh.openeco.api.OpenEcoApi;
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

        // ── Pay Limit ────────────────────────────────────────────────────────
        if (getConfig().getBoolean("pay-limit.enabled", false)) {
            getServer().getPluginManager().registerEvents(new PayLimitListener(api, this), this);
            getLogger().info("Pay limit enabled.");
        }

        // ── Permission Balance Cap ────────────────────────────────────────────
        if (getConfig().getBoolean("perm-cap.enabled", false)) {
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
        if (interestTask != null) {
            interestTask.cancel();
        }
    }

    private void startInterestTask() {
        if (interestTask != null) interestTask.cancel();
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
        InterestTask task = new InterestTask(api, this);
        interestTask = getServer().getAsyncScheduler().runAtFixedRate(
                this, st -> task.run(), intervalMs, intervalMs, TimeUnit.MILLISECONDS);
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
