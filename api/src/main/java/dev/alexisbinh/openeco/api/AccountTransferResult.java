/*
 * Copyright 2026 alexisbinh
 * Licensed under the Apache License, Version 2.0.
 */
package dev.alexisbinh.openeco.api;

import java.math.BigDecimal;

/** Result of an atomic account-to-account transfer without pay tax or cooldown. */
public record AccountTransferResult(
        Status status,
        BigDecimal amount,
        BigDecimal fromBalance,
        BigDecimal toBalance,
        String message) {

    public enum Status {
        SUCCESS, UNKNOWN_CURRENCY, INVALID_AMOUNT, SELF_TRANSFER, ACCOUNT_NOT_FOUND,
        FROZEN, INSUFFICIENT_FUNDS, BALANCE_LIMIT, STORAGE_ERROR, POLICY_REJECTED
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }
}
