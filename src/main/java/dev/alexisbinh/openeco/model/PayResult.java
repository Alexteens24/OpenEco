package dev.alexisbinh.openeco.model;

import java.math.BigDecimal;

public final class PayResult {

    public enum Status { SUCCESS, UNKNOWN_CURRENCY, COOLDOWN, INSUFFICIENT_FUNDS, ACCOUNT_NOT_FOUND, BALANCE_LIMIT, CANCELLED, TOO_LOW, INVALID_AMOUNT, SELF_TRANSFER, FROZEN }

    private final Status status;
    private final BigDecimal sent;
    private final BigDecimal received;
    private final BigDecimal tax;
    private final BigDecimal minimumAmount;
    private final long cooldownRemainingMs;

    private PayResult(Status status, BigDecimal sent, BigDecimal received,
                      BigDecimal tax, BigDecimal minimumAmount, long cooldownRemainingMs) {
        this.status = status;
        this.sent = sent;
        this.received = received;
        this.tax = tax;
        this.minimumAmount = minimumAmount;
        this.cooldownRemainingMs = cooldownRemainingMs;
    }

    public static PayResult success(BigDecimal sent, BigDecimal received, BigDecimal tax) {
        return new PayResult(Status.SUCCESS, sent, received, tax, null, 0);
    }

    public static PayResult unknownCurrency() {
        return new PayResult(Status.UNKNOWN_CURRENCY, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, 0);
    }

    public static PayResult onCooldown(long remainingMs) {
        return new PayResult(Status.COOLDOWN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, remainingMs);
    }

    public static PayResult insufficientFunds() {
        return new PayResult(Status.INSUFFICIENT_FUNDS, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, 0);
    }

    public static PayResult accountNotFound() {
        return new PayResult(Status.ACCOUNT_NOT_FOUND, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, 0);
    }

    public static PayResult balanceLimit() {
        return new PayResult(Status.BALANCE_LIMIT, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, 0);
    }

    public static PayResult cancelled() {
        return new PayResult(Status.CANCELLED, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, 0);
    }

    public static PayResult tooLow(BigDecimal minimum) {
        return new PayResult(Status.TOO_LOW, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, minimum, 0);
    }

    public static PayResult invalidAmount() {
        return new PayResult(Status.INVALID_AMOUNT, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, 0);
    }

    public static PayResult selfTransfer() {
        return new PayResult(Status.SELF_TRANSFER, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, 0);
    }

    public static PayResult frozen() {
        return new PayResult(Status.FROZEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, 0);
    }

    public boolean isSuccess()              { return status == Status.SUCCESS; }
    public Status getStatus()               { return status; }
    public BigDecimal getSent()             { return sent; }
    public BigDecimal getReceived()         { return received; }
    public BigDecimal getTax()              { return tax; }
    public BigDecimal getMinimumAmount()    { return minimumAmount; }
    public long getCooldownRemainingMs()    { return cooldownRemainingMs; }
}
