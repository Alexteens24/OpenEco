package dev.alexisbinh.openeco.api;

import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;

/**
 * Immutable snapshot of the current operational rules exposed by OpenEco.
 */
public record EconomyRulesSnapshot(
        CurrencyInfo currency,
        long payCooldownMs,
        BigDecimal payTaxRate,
        @Nullable BigDecimal payMinAmount,
        long balTopCacheTtlMs,
        int historyRetentionDays
) {

    public boolean hasPayMinimum() {
        return payMinAmount != null;
    }

    public boolean keepsHistoryForever() {
        return historyRetentionDays < 0;
    }
}