package dev.alexisbinh.simpleeco.api;

import java.math.BigDecimal;

public record BalanceCheckResult(Status status, BigDecimal amount, BigDecimal currentBalance, BigDecimal resultingBalance) {

    public enum Status {
        ALLOWED,
        ACCOUNT_NOT_FOUND,
        INVALID_AMOUNT,
        INSUFFICIENT_FUNDS,
        BALANCE_LIMIT
    }

    public boolean isAllowed() {
        return status == Status.ALLOWED;
    }
}