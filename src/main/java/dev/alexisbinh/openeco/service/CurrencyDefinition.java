package dev.alexisbinh.openeco.service;

import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.Objects;

record CurrencyDefinition(
        String id,
        String singularName,
        String pluralName,
        int fractionalDigits,
        BigDecimal startingBalance,
        @Nullable BigDecimal maxBalance
) {

    CurrencyDefinition {
        id = requireText(id, "id");
        singularName = requireText(singularName, "singularName");
        pluralName = requireText(pluralName, "pluralName");
        startingBalance = Objects.requireNonNull(startingBalance, "startingBalance");
    }

    boolean hasMaxBalance() {
        return maxBalance != null;
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return trimmed;
    }
}