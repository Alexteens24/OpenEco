/*
 * Copyright 2026 alexisbinh
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
    private volatile long version;

    public AccountRecord(UUID id, String lastKnownName, BigDecimal balance, long createdAt, long updatedAt) {
        this(id, lastKnownName, "openeco", Map.of("openeco", Objects.requireNonNull(balance, "balance")), createdAt, updatedAt);
    }

    public AccountRecord(UUID id, String lastKnownName, String primaryCurrencyId,
                         Map<String, BigDecimal> balances, long createdAt, long updatedAt) {
        this(id, lastKnownName, primaryCurrencyId, balances, createdAt, updatedAt, 0L);
    }

    public AccountRecord(UUID id, String lastKnownName, String primaryCurrencyId,
                         Map<String, BigDecimal> balances, long createdAt, long updatedAt,
                         long version) {
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
        this.version = Math.max(0L, version);
        this.dirty = false;
    }

    public UUID getId() { return id; }
    public synchronized String getLastKnownName() { return lastKnownName; }
    public BigDecimal getBalance() { return getBalance(primaryCurrencyId); }
    public synchronized BigDecimal getBalance(String currencyId) {
        String requestedCurrencyId = requireCurrencyId(currencyId);
        String storedCurrencyId = findStoredCurrencyId(requestedCurrencyId);
        return storedCurrencyId != null ? balances.get(storedCurrencyId) : BigDecimal.ZERO;
    }
    public synchronized Map<String, BigDecimal> getBalancesSnapshot() { return Map.copyOf(balances); }
    public String getPrimaryCurrencyId() { return primaryCurrencyId; }
    public long getCreatedAt() { return createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public boolean isDirty() { return dirty; }
    public boolean isFrozen() { return frozen; }
    public long getVersion() { return version; }

    public synchronized void setLastKnownName(String name) {
        this.lastKnownName = name;
        this.updatedAt = System.currentTimeMillis();
        this.dirty = true;
    }

    public void setBalance(BigDecimal balance) {
        setBalance(primaryCurrencyId, balance);
    }

    public synchronized void setBalance(String currencyId, BigDecimal balance) {
        putBalance(requireCurrencyId(currencyId), Objects.requireNonNull(balance, "balance"), true);
    }

    public synchronized void setPrimaryCurrencyId(String currencyId) {
        String canonicalCurrencyId = requireCurrencyId(currencyId);
        String storedCurrencyId = findStoredCurrencyId(canonicalCurrencyId);
        if (storedCurrencyId != null && !storedCurrencyId.equals(canonicalCurrencyId)) {
            BigDecimal storedBalance = balances.remove(storedCurrencyId);
            balances.put(canonicalCurrencyId, storedBalance);
        }
        this.primaryCurrencyId = canonicalCurrencyId;
        this.balances.putIfAbsent(this.primaryCurrencyId, BigDecimal.ZERO);
    }

    public synchronized void setFrozen(boolean frozen) {
        this.frozen = frozen;
        this.dirty = true;
    }

    public synchronized void setVersion(long version) {
        this.version = Math.max(0L, version);
    }

    public synchronized void markDirty() {
        this.dirty = true;
    }

    public synchronized void clearDirty() {
        this.dirty = false;
    }

    public synchronized boolean canonicalizeCurrencyIds(Function<String, String> canonicalizer) {
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

    /**
     * Replaces mutable state from a fresh snapshot while preserving this object identity.
     * This avoids races where callers still hold a reference to the current live instance.
     */
    public synchronized void overwriteFrom(AccountRecord source) {
        Objects.requireNonNull(source, "source");
        if (!id.equals(source.id)) {
            throw new IllegalArgumentException("Cannot overwrite account " + id + " from " + source.id);
        }
        if (source == this) {
            return;
        }

        this.lastKnownName = source.getLastKnownName();
        this.primaryCurrencyId = source.getPrimaryCurrencyId();
        this.balances.clear();
        this.balances.putAll(source.getBalancesSnapshot());
        this.updatedAt = source.getUpdatedAt();
        this.frozen = source.isFrozen();
        this.version = source.getVersion();
        this.dirty = false;
    }

    /** Returns an immutable snapshot safe to flush to DB while modifications continue. */
    public synchronized AccountRecord snapshot() {
        AccountRecord snap = new AccountRecord(id, lastKnownName, primaryCurrencyId, balances,
                createdAt, updatedAt, version);
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
