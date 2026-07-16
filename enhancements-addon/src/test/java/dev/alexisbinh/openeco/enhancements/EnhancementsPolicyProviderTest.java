/*
 * Copyright 2026 alexisbinh
 * Licensed under the Apache License, Version 2.0.
 */
package dev.alexisbinh.openeco.enhancements;

import dev.alexisbinh.openeco.api.MutationPolicyContext;
import dev.alexisbinh.openeco.api.MutationPolicyDecision;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EnhancementsPolicyProviderTest {

    @Test
    void evaluateReadsImmutablePermissionSnapshotWithoutBukkitObjects() {
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        MutationPolicyDecision.RollingLimit payLimit =
                new MutationPolicyDecision.RollingLimit(new BigDecimal("100"), 60_000L);
        var settings = new EnhancementsPolicyProvider.PolicySettings(
                true, List.of(), true, payLimit);
        var snapshots = Map.of(
                source, new EnhancementsPolicyProvider.PlayerPolicySnapshot(null, false),
                target, new EnhancementsPolicyProvider.PlayerPolicySnapshot(new BigDecimal("250"), false));
        EnhancementsPolicyProvider provider = new EnhancementsPolicyProvider(settings, snapshots);

        var decision = provider.evaluate(new MutationPolicyContext(
                MutationPolicyContext.Kind.PAY, source, target, "coins", BigDecimal.TEN));

        assertEquals(new BigDecimal("250"), decision.maximumTargetBalance());
        assertEquals(payLimit, decision.rollingLimit());
    }

    @Test
    void snapshotBypassDisablesPayLimitAndOfflineTargetHasNoPermissionCap() {
        UUID source = UUID.randomUUID();
        UUID offlineTarget = UUID.randomUUID();
        var settings = new EnhancementsPolicyProvider.PolicySettings(
                true, List.of(), true,
                new MutationPolicyDecision.RollingLimit(new BigDecimal("100"), 60_000L));
        EnhancementsPolicyProvider provider = new EnhancementsPolicyProvider(settings, Map.of(
                source, new EnhancementsPolicyProvider.PlayerPolicySnapshot(null, true)));

        var decision = provider.evaluate(new MutationPolicyContext(
                MutationPolicyContext.Kind.PAY, source, offlineTarget, "coins", BigDecimal.TEN));

        assertNull(decision.maximumTargetBalance());
        assertNull(decision.rollingLimit());
    }
}
