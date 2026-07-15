/*
 * Copyright 2026 alexisbinh
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package dev.alexisbinh.openeco.service;

import dev.alexisbinh.openeco.model.AccountRecord;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

final class AccountLease implements AutoCloseable {

    private final AccountRegistry registry;
    private final UUID accountId;
    private final AccountRecord record;
    private final AtomicBoolean closed = new AtomicBoolean();

    AccountLease(AccountRegistry registry, UUID accountId, AccountRecord record) {
        this.registry = registry;
        this.accountId = accountId;
        this.record = record;
    }

    AccountRecord record() {
        return record;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) registry.releaseLease(accountId);
    }
}
