package dev.alexisbinh.simpleeco.service;

import org.bukkit.configuration.file.FileConfiguration;

import java.math.BigDecimal;
import java.math.RoundingMode;

record EconomyConfigSnapshot(
        String currencyId,
        String currencySingular,
        String currencyPlural,
        int fractionalDigits,
        BigDecimal startingBalance,
        long payCooldownMs,
        BigDecimal payTaxRate,
        BigDecimal payMinAmount,
        BigDecimal maxBalance,
        long balTopCacheTtlMs,
        int historyRetentionDays
) {

    static EconomyConfigSnapshot from(FileConfiguration config) {
        int fractionalDigits = Math.max(0, Math.min(8, config.getInt("currency.decimal-digits", 2)));
        BigDecimal startingBalance = BigDecimal.valueOf(Math.max(0.0, config.getDouble("currency.starting-balance", 0.0)))
                .setScale(fractionalDigits, RoundingMode.HALF_UP);
        BigDecimal payTaxRate = BigDecimal.valueOf(
                Math.max(0.0, Math.min(100.0, config.getDouble("pay.tax-percent", 0.0))));

        double minPay = config.getDouble("pay.min-amount", 0.01);
        BigDecimal payMinAmount = minPay > 0
                ? BigDecimal.valueOf(minPay).setScale(fractionalDigits, RoundingMode.HALF_UP)
                : null;

        double maxBalanceCfg = config.getDouble("currency.max-balance", -1);
        BigDecimal maxBalance = maxBalanceCfg > 0
                ? BigDecimal.valueOf(maxBalanceCfg).setScale(fractionalDigits, RoundingMode.HALF_UP)
                : null;

        long balTopCacheTtlMs = Math.max(1, config.getLong("baltop.cache-ttl-seconds", 30)) * 1000L;
        int configuredHistoryRetentionDays = config.getInt("history.retention-days", -1);
        int historyRetentionDays = configuredHistoryRetentionDays > 0 ? configuredHistoryRetentionDays : -1;

        return new EconomyConfigSnapshot(
                config.getString("currency.id", "simpleeco"),
                config.getString("currency.name-singular", "Dollar"),
                config.getString("currency.name-plural", "Dollars"),
                fractionalDigits,
                startingBalance,
                Math.max(0, config.getLong("pay.cooldown-seconds", 0)) * 1000L,
                payTaxRate,
                payMinAmount,
                maxBalance,
                balTopCacheTtlMs,
                historyRetentionDays);
    }
}