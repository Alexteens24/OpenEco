package dev.alexisbinh.openeco.model;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

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
        this(id, lastKnownName, "openeco", Map.of("openeco", Objects.requireNonNull(balance, "balance")), createdAt, updatedAt);
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
        String requestedCurrencyId = requireCurrencyId(currencyId);
        String storedCurrencyId = findStoredCurrencyId(requestedCurrencyId);
        return storedCurrencyId != null ? balances.get(storedCurrencyId) : BigDecimal.ZERO;
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
        putBalance(requireCurrencyId(currencyId), Objects.requireNonNull(balance, "balance"), true);
    }

    public void setPrimaryCurrencyId(String currencyId) {
        String canonicalCurrencyId = requireCurrencyId(currencyId);
        String storedCurrencyId = findStoredCurrencyId(canonicalCurrencyId);
        if (storedCurrencyId != null && !storedCurrencyId.equals(canonicalCurrencyId)) {
            BigDecimal storedBalance = balances.remove(storedCurrencyId);
            balances.put(canonicalCurrencyId, storedBalance);
        }
        this.primaryCurrencyId = canonicalCurrencyId;
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

    public boolean canonicalizeCurrencyIds(Function<String, String> canonicalizer) {
        Objects.requireNonNull(canonicalizer, "canonicalizer");

        Map<String, BigDecimal> rewrittenBalances = new HashMap<>();
        for (Map.Entry<String, BigDecimal> entry : balances.entrySet()) {
            String currentCurrencyId = entry.getKey();
            String canonicalCurrencyId = canonicalizer.apply(currentCurrencyId);
            String resolvedCurrencyId = canonicalCurrencyId != null
                    ? requireCurrencyId(canonicalCurrencyId)
                    : currentCurrencyId;
            BigDecimal existingBalance = rewrittenBalances.get(resolvedCurrencyId);
            if (existingBalance == null || currentCurrencyId.equals(resolvedCurrencyId)) {
                rewrittenBalances.put(resolvedCurrencyId, entry.getValue());
            }
        }

        String canonicalPrimaryCurrencyId = canonicalizer.apply(primaryCurrencyId);
        String resolvedPrimaryCurrencyId = canonicalPrimaryCurrencyId != null
                ? requireCurrencyId(canonicalPrimaryCurrencyId)
                : primaryCurrencyId;
        rewrittenBalances.putIfAbsent(resolvedPrimaryCurrencyId, BigDecimal.ZERO);

        boolean changed = !resolvedPrimaryCurrencyId.equals(primaryCurrencyId) || !balances.equals(rewrittenBalances);
        if (changed) {
            balances.clear();
            balances.putAll(rewrittenBalances);
            primaryCurrencyId = resolvedPrimaryCurrencyId;
        }
        return changed;
    }

    /** Returns an immutable snapshot safe to flush to DB while modifications continue. */
    public AccountRecord snapshot() {
        AccountRecord snap = new AccountRecord(id, lastKnownName, primaryCurrencyId, balances, createdAt, updatedAt);
        snap.frozen = this.frozen;
        return snap;
    }

    private void putBalance(String currencyId, BigDecimal balance, boolean touchMetadata) {
        String storedCurrencyId = findStoredCurrencyId(currencyId);
        if (storedCurrencyId != null && !storedCurrencyId.equals(currencyId)) {
            balances.remove(storedCurrencyId);
        }
        balances.put(currencyId, balance);
        if (touchMetadata) {
            this.updatedAt = System.currentTimeMillis();
            this.dirty = true;
        }
    }

    private String findStoredCurrencyId(String currencyId) {
        if (balances.containsKey(currencyId)) {
            return currencyId;
        }
        for (String storedCurrencyId : balances.keySet()) {
            if (storedCurrencyId.equalsIgnoreCase(currencyId)) {
                return storedCurrencyId;
            }
        }
        return null;
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
