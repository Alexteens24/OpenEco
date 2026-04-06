package dev.alexisbinh.simpleeco.model;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class AccountRecord {

    public static final int MAX_NAME_LENGTH = 16;

    private final UUID id;
    private volatile String lastKnownName;
    private volatile String primaryCurrencyId;
    private final Map<String, BigDecimal> balances;
    private final long createdAt;
    private volatile long updatedAt;
    private volatile boolean dirty;
    private volatile boolean frozen;

    public AccountRecord(UUID id, String lastKnownName, BigDecimal balance, long createdAt, long updatedAt) {
        this(id, lastKnownName, "simpleeco", Map.of("simpleeco", Objects.requireNonNull(balance, "balance")), createdAt, updatedAt);
    }

    public AccountRecord(UUID id, String lastKnownName, String primaryCurrencyId,
                         Map<String, BigDecimal> balances, long createdAt, long updatedAt) {
        this.id = id;
        this.lastKnownName = lastKnownName;
        this.primaryCurrencyId = requireCurrencyId(primaryCurrencyId);
        this.balances = new HashMap<>();
        if (balances != null) {
            for (Map.Entry<String, BigDecimal> entry : balances.entrySet()) {
                this.balances.put(requireCurrencyId(entry.getKey()), Objects.requireNonNull(entry.getValue(), "balance"));
            }
        }
        this.balances.putIfAbsent(this.primaryCurrencyId, BigDecimal.ZERO);
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.dirty = false;
    }

    public UUID getId() { return id; }
    public String getLastKnownName() { return lastKnownName; }
    public BigDecimal getBalance() { return getBalance(primaryCurrencyId); }
    public BigDecimal getBalance(String currencyId) {
        return balances.getOrDefault(requireCurrencyId(currencyId), BigDecimal.ZERO);
    }
    public Map<String, BigDecimal> getBalancesSnapshot() { return Map.copyOf(balances); }
    public String getPrimaryCurrencyId() { return primaryCurrencyId; }
    public long getCreatedAt() { return createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public boolean isDirty() { return dirty; }
    public boolean isFrozen() { return frozen; }

    public void setLastKnownName(String name) {
        this.lastKnownName = name;
        this.updatedAt = System.currentTimeMillis();
        this.dirty = true;
    }

    public void setBalance(BigDecimal balance) {
        setBalance(primaryCurrencyId, balance);
    }

    public void setBalance(String currencyId, BigDecimal balance) {
        balances.put(requireCurrencyId(currencyId), Objects.requireNonNull(balance, "balance"));
        this.updatedAt = System.currentTimeMillis();
        this.dirty = true;
    }

    public void setPrimaryCurrencyId(String currencyId) {
        this.primaryCurrencyId = requireCurrencyId(currencyId);
        this.balances.putIfAbsent(this.primaryCurrencyId, BigDecimal.ZERO);
    }

    public void setFrozen(boolean frozen) {
        this.frozen = frozen;
        this.dirty = true;
    }

    public void markDirty() {
        this.dirty = true;
    }

    public void clearDirty() {
        this.dirty = false;
    }

    /** Returns an immutable snapshot safe to flush to DB while modifications continue. */
    public AccountRecord snapshot() {
        AccountRecord snap = new AccountRecord(id, lastKnownName, primaryCurrencyId, balances, createdAt, updatedAt);
        snap.frozen = this.frozen;
        return snap;
    }

    private static String requireCurrencyId(String currencyId) {
        Objects.requireNonNull(currencyId, "currencyId");
        String trimmed = currencyId.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("currencyId must not be blank");
        }
        return trimmed;
    }
}
