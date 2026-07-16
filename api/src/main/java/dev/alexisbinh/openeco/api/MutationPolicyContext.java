/*
 * Copyright 2026 alexisbinh
 * Licensed under the Apache License, Version 2.0.
 */
package dev.alexisbinh.openeco.api;

import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.UUID;

public record MutationPolicyContext(Kind kind, @Nullable UUID sourceId, UUID targetId,
                                    String currencyId, BigDecimal amount) {
    public enum Kind { DEPOSIT, WITHDRAW, SET, RESET, PAY, TRANSFER, EXCHANGE }
}
