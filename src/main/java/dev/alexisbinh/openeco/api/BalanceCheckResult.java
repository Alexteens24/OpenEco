package dev.alexisbinh.openeco.api;

import java.math.BigDecimal;

public record BalanceCheckResult(Status status, BigDecimal amount, BigDecimal currentBalance, BigDecimal resultingBalance) {

    public enum Status {
        ALLOWED,
        UNKNOWN_CURRENCY,
        ACCOUNT_NOT_FOUND,
        INVALID_AMOUNT,
        INSUFFICIENT_FUNDS,
        BALANCE_LIMIT,
        FROZEN
    }

    public boolean isAllowed() {
        return status == Status.ALLOWED;
    }
}