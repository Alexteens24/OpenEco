/*
 * Copyright 2026 alexisbinh
 * Licensed under the Apache License, Version 2.0.
 */
package dev.alexisbinh.openeco.api;

import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;

public record MutationPolicyDecision(boolean allowed, @Nullable String reason,
                                     @Nullable BigDecimal maximumTargetBalance,
                                     @Nullable RollingLimit rollingLimit) {
    public MutationPolicyDecision {
        if (maximumTargetBalance != null && maximumTargetBalance.signum() < 0) {
            throw new IllegalArgumentException("maximumTargetBalance must be non-negative");
        }
    }

    public record RollingLimit(BigDecimal maximumAmount, long windowMs) {
        public RollingLimit {
            if (maximumAmount == null || maximumAmount.signum() <= 0) {
                throw new IllegalArgumentException("maximumAmount must be positive");
            }
            if (windowMs <= 0) throw new IllegalArgumentException("windowMs must be positive");
        }
    }

    public static MutationPolicyDecision allow() {
        return new MutationPolicyDecision(true, null, null, null);
    }
}
