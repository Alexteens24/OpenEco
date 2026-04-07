package dev.alexisbinh.openeco.api;

import java.math.BigDecimal;

/**
 * Result of {@link OpenEcoApi#canTransfer(java.util.UUID, java.util.UUID, BigDecimal)}.
 */
public record TransferCheckResult(Status status, BigDecimal amount) {

    public boolean isAllowed() {
        return status == Status.ALLOWED;
    }

    public enum Status {
        ALLOWED,
        UNKNOWN_CURRENCY,
        ACCOUNT_NOT_FOUND,
        INVALID_AMOUNT,
        INSUFFICIENT_FUNDS,
        BALANCE_LIMIT,
        SELF_TRANSFER
    }
}
