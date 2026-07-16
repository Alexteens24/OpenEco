/*
 * Copyright 2026 alexisbinh
 * Licensed under the Apache License, Version 2.0.
 */
package dev.alexisbinh.openeco.api;

import java.math.BigDecimal;

public record ExchangeResult(Status status, BigDecimal fromAmount, BigDecimal toAmount,
                             BigDecimal fromBalance, BigDecimal toBalance) {
    public enum Status {
        SUCCESS, UNKNOWN_CURRENCY, SAME_CURRENCY, INVALID_AMOUNT, ACCOUNT_NOT_FOUND,
        INSUFFICIENT_FUNDS, BALANCE_LIMIT, FROZEN, CANCELLED, STORAGE_ERROR, POLICY_REJECTED
    }

    public boolean isSuccess() { return status == Status.SUCCESS; }
}
