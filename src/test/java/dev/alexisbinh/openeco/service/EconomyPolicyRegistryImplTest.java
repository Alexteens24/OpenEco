/*
 * Copyright 2026 alexisbinh
 * Licensed under the Apache License, Version 2.0.
 */
package dev.alexisbinh.openeco.service;

import dev.alexisbinh.openeco.api.MutationPolicyContext;
import dev.alexisbinh.openeco.api.MutationPolicyDecision;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EconomyPolicyRegistryImplTest {

    @Test
    void composesEveryRollingConstraintInDeterministicProviderOrder() {
        EconomyPolicyRegistryImpl registry = new EconomyPolicyRegistryImpl();
        registry.register("z-provider", ignored -> new MutationPolicyDecision(
                true, null, new BigDecimal("500"),
                new MutationPolicyDecision.RollingLimit(new BigDecimal("100"), 60_000L)));
        registry.register("a-provider", ignored -> new MutationPolicyDecision(
                true, null, new BigDecimal("250"),
                new MutationPolicyDecision.RollingLimit(new BigDecimal("15"), 10_000L)));

        var resolved = registry.evaluate(new MutationPolicyContext(
                MutationPolicyContext.Kind.PAY, UUID.randomUUID(), UUID.randomUUID(),
                "coins", BigDecimal.TEN));

        assertEquals(new BigDecimal("250"), resolved.maximumTargetBalance());
        assertEquals(2, resolved.rollingConstraints().size());
        assertEquals("a-provider", resolved.rollingConstraints().get(0).providerId());
        assertEquals("z-provider", resolved.rollingConstraints().get(1).providerId());
    }
}
