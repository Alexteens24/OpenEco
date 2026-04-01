package dev.alexisbinh.simpleeco.api;

import java.math.BigDecimal;

public record TransferResult(Status status, BigDecimal sent, BigDecimal received, BigDecimal tax, long cooldownRemainingMs) {

    public enum Status {
        SUCCESS,
        COOLDOWN,
        INSUFFICIENT_FUNDS,
        ACCOUNT_NOT_FOUND,
        BALANCE_LIMIT,
        CANCELLED,
        TOO_LOW,
        INVALID_AMOUNT,
        SELF_TRANSFER
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }
}