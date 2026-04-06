package dev.alexisbinh.simpleeco.enhancements;

import dev.alexisbinh.simpleeco.api.SimpleEcoApi;
import dev.alexisbinh.simpleeco.enhancements.interest.InterestTask;
import dev.alexisbinh.simpleeco.enhancements.paylimit.PayLimitListener;
import dev.alexisbinh.simpleeco.enhancements.permcap.PermCapListener;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class SimpleEcoEnhancementsPlugin extends JavaPlugin {

    private SimpleEcoApi api;
    private ScheduledTask interestTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        RegisteredServiceProvider<SimpleEcoApi> rsp =
                getServer().getServicesManager().getRegistration(SimpleEcoApi.class);
        if (rsp == null) {
            getLogger().severe("SimpleEcoApi not found — is SimpleEco loaded and enabled?");
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

        getLogger().info("SimpleEcoEnhancements enabled.");
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
        long intervalMs = intervalSeconds * 1000L;
        InterestTask task = new InterestTask(api, this);
        interestTask = getServer().getAsyncScheduler().runAtFixedRate(
                this, st -> task.run(), intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        getLogger().info("Interest task scheduled every " + intervalSeconds + "s.");
    }

    static void warnIfPermCapExceedsGlobalLimit(List<Map<?, ?>> tiers, SimpleEcoApi api, Logger logger) {
        BigDecimal globalCap = api.getRules().currency().maxBalance();
        if (globalCap == null) {
            return;
        }

        int fractionalDigits = api.getRules().currency().fractionalDigits();
        for (Map<?, ?> entry : tiers) {
            String permission = entry.get("permission") instanceof String value ? value : null;
            if (!(entry.get("cap") instanceof Number capNumber)) {
                continue;
            }

            BigDecimal tierCap = BigDecimal.valueOf(capNumber.doubleValue())
                    .setScale(fractionalDigits, RoundingMode.HALF_UP);
            if (tierCap.compareTo(globalCap) <= 0) {
                continue;
            }

            String label = permission != null && !permission.isBlank() ? permission : "<unknown permission>";
            logger.warning("perm-cap tier '" + label + "' configures " + tierCap.toPlainString()
                    + " above SimpleEco global max-balance " + globalCap.toPlainString()
                    + "; core will still enforce the global limit.");
        }
    }
}
