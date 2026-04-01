package dev.alexisbinh.simpleeco.api;

import java.math.BigDecimal;

public record BalanceChangeResult(Status status, BigDecimal amount, BigDecimal previousBalance, BigDecimal newBalance) {

    public enum Status {
        SUCCESS,
        ACCOUNT_NOT_FOUND,
        INVALID_AMOUNT,
        INSUFFICIENT_FUNDS,
        BALANCE_LIMIT,
        CANCELLED
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }
}