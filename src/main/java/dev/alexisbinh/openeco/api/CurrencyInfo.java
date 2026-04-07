package dev.alexisbinh.openeco.api;

import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;

public record CurrencyInfo(
        String id,
        String singularName,
        String pluralName,
        int fractionalDigits,
        BigDecimal startingBalance,
        @Nullable BigDecimal maxBalance
) {

    public boolean hasMaxBalance() {
        return maxBalance != null;
    }
}