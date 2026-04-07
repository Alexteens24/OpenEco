package dev.alexisbinh.openeco.api;

import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;

/**
 * Preflight result for a transfer without mutating state.
 */
public record TransferPreviewResult(
        Status status,
        BigDecimal sent,
        BigDecimal received,
        BigDecimal tax,
        long cooldownRemainingMs,
        @Nullable BigDecimal minimumAmount
) {

    public enum Status {
        ALLOWED,
        UNKNOWN_CURRENCY,
        COOLDOWN,
        INSUFFICIENT_FUNDS,
        ACCOUNT_NOT_FOUND,
        BALANCE_LIMIT,
        TOO_LOW,
        INVALID_AMOUNT,
        SELF_TRANSFER
    }

    public boolean isAllowed() {
        return status == Status.ALLOWED;
    }

    public boolean hasMinimumAmount() {
        return status == Status.TOO_LOW && minimumAmount != null;
    }
}