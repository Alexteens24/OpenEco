package dev.alexisbinh.simpleeco.model;

import java.math.BigDecimal;
import java.util.UUID;

public final class AccountRecord {

    public static final int MAX_NAME_LENGTH = 16;

    private final UUID id;
    private volatile String lastKnownName;
    private volatile BigDecimal balance;
    private final long createdAt;
    private volatile long updatedAt;
    private volatile boolean dirty;

    public AccountRecord(UUID id, String lastKnownName, BigDecimal balance, long createdAt, long updatedAt) {
        this.id = id;
        this.lastKnownName = lastKnownName;
        this.balance = balance;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.dirty = false;
    }

    public UUID getId() { return id; }
    public String getLastKnownName() { return lastKnownName; }
    public BigDecimal getBalance() { return balance; }
    public long getCreatedAt() { return createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public boolean isDirty() { return dirty; }

    public void setLastKnownName(String name) {
        this.lastKnownName = name;
        this.updatedAt = System.currentTimeMillis();
        this.dirty = true;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
        this.updatedAt = System.currentTimeMillis();
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
        return new AccountRecord(id, lastKnownName, balance, createdAt, updatedAt);
    }
}
