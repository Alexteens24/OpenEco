package dev.alexisbinh.openeco.enhancements.interest;

import dev.alexisbinh.openeco.api.BalanceChangeResult;
import dev.alexisbinh.openeco.api.CurrencyInfo;
import dev.alexisbinh.openeco.api.OpenEcoApi;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Iterates all accounts and credits interest based on the configured rate and interval.
 * Runs on Folia/Paper's async scheduler.
 */
public class InterestTask implements Runnable {

    private final OpenEcoApi api;
    private final JavaPlugin plugin;
    private final Logger log;

    public InterestTask(OpenEcoApi api, JavaPlugin plugin) {
        this.api = api;
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    @Override
    public void run() {
        plugin.getServer().getGlobalRegionScheduler().run(plugin, this::runInterestCycle);
    }

    private void runInterestCycle(ScheduledTask task) {
        FileConfiguration config = plugin.getConfig();
        double rate = config.getDouble("interest.rate", 5.0);
        long intervalSeconds = config.getLong("interest.interval-seconds", 3600);
        String configuredCurrencyId = config.getString("interest.currency");
        boolean explicitCurrency = configuredCurrencyId != null && !configuredCurrencyId.isBlank();
        String currencyId = explicitCurrency ? configuredCurrencyId : api.getRules().currency().id();
        CurrencyInfo currency = explicitCurrency ? api.getCurrencyInfo(currencyId) : api.getRules().currency();
        if (explicitCurrency && currency == null) {
            plugin.getLogger().warning("[Interest] Unknown configured currency '" + currencyId + "'; skipping interest cycle.");
            return;
        }
        int fractionalDigits = currency.fractionalDigits();
        BigDecimal minBalance = BigDecimal.valueOf(config.getDouble("interest.min-balance", 0))
            .setScale(fractionalDigits, RoundingMode.HALF_UP);
        BigDecimal maxPerInterval = BigDecimal.valueOf(config.getDouble("interest.max-per-interval", 0))
            .setScale(fractionalDigits, RoundingMode.HALF_UP);

        if (rate <= 0 || intervalSeconds <= 0) return;

        // Factor = rate% / 100 / (seconds per year / interval)
        double secondsPerYear = 365.25 * 24 * 3600;
        BigDecimal factor = BigDecimal.valueOf(rate / 100.0 / (secondsPerYear / intervalSeconds));

        int credited = 0;
        int skipped = 0;

        Map<UUID, String> accounts = api.getUUIDNameMap();
        for (UUID id : accounts.keySet()) {
            try {
                credited += processAccount(id, currencyId, explicitCurrency, factor, minBalance, maxPerInterval, fractionalDigits) ? 1 : 0;
            } catch (Exception e) {
                log.warning("[Interest] Error processing account " + id + ": " + e.getMessage());
                skipped++;
            }
        }
        log.info("[Interest] Cycle complete — credited: " + credited + ", skipped/error: " + skipped);
    }

    private boolean processAccount(UUID id, String currencyId, boolean explicitCurrency, BigDecimal factor, BigDecimal minBalance,
                                   BigDecimal maxPerInterval, int fractionalDigits) {
        BigDecimal balance = explicitCurrency ? api.getBalance(id, currencyId) : api.getBalance(id);
        if (balance.compareTo(minBalance) < 0) return false;

        BigDecimal interest = balance.multiply(factor)
                .setScale(fractionalDigits, RoundingMode.HALF_UP);
        if (interest.compareTo(BigDecimal.ZERO) <= 0) return false;

        if (maxPerInterval.compareTo(BigDecimal.ZERO) > 0
                && interest.compareTo(maxPerInterval) > 0) {
            interest = maxPerInterval;
        }

        BalanceChangeResult result = explicitCurrency ? api.deposit(id, currencyId, interest) : api.deposit(id, interest);
        return result.isSuccess();
    }
}
