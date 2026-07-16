/*
 * Copyright 2026 alexisbinh
 * Licensed under the Apache License, Version 2.0.
 */
package dev.alexisbinh.openeco.service;

import dev.alexisbinh.openeco.api.EconomyMutationPolicyProvider;
import dev.alexisbinh.openeco.api.EconomyPolicyRegistry;
import dev.alexisbinh.openeco.api.MutationPolicyContext;
import dev.alexisbinh.openeco.api.MutationPolicyDecision;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class EconomyPolicyRegistryImpl implements EconomyPolicyRegistry {
    public record ResolvedPolicy(boolean allowed, String providerId, String reason,
                                 BigDecimal maximumTargetBalance,
                                 MutationPolicyDecision.RollingLimit rollingLimit) {
        static ResolvedPolicy allow() { return new ResolvedPolicy(true, null, null, null, null); }
    }

    private final Map<String, EconomyMutationPolicyProvider> providers = new ConcurrentHashMap<>();

    @Override
    public void register(String providerId, EconomyMutationPolicyProvider provider) {
        if (providerId == null || !providerId.matches("[a-z0-9_.-]{1,64}")) {
            throw new IllegalArgumentException("providerId must match [a-z0-9_.-]{1,64}");
        }
        if (provider == null) throw new IllegalArgumentException("provider must not be null");
        providers.put(providerId, provider);
    }

    @Override
    public void unregister(String providerId) {
        if (providerId != null) providers.remove(providerId);
    }

    public ResolvedPolicy evaluate(MutationPolicyContext context) {
        BigDecimal cap = null;
        MutationPolicyDecision.RollingLimit rolling = null;
        String rollingProvider = null;
        for (Map.Entry<String, EconomyMutationPolicyProvider> entry : providers.entrySet()) {
            MutationPolicyDecision decision;
            try {
                decision = entry.getValue().evaluate(context);
            } catch (RuntimeException e) {
                return new ResolvedPolicy(false, entry.getKey(),
                        "Policy provider failed: " + e.getClass().getSimpleName(), null, null);
            }
            if (decision == null) continue;
            if (!decision.allowed()) {
                return new ResolvedPolicy(false, entry.getKey(), decision.reason(), null, null);
            }
            if (decision.maximumTargetBalance() != null
                    && (cap == null || decision.maximumTargetBalance().compareTo(cap) < 0)) {
                cap = decision.maximumTargetBalance();
            }
            if (decision.rollingLimit() != null) {
                rolling = decision.rollingLimit();
                rollingProvider = entry.getKey();
            }
        }
        return new ResolvedPolicy(true, rollingProvider, null, cap, rolling);
    }
}
