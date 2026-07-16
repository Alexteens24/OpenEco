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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EnhancementsPolicyProviderTest {

    @Test
    void loadsRemoteTargetSnapshotWhenPlayerIsOnAnotherBackend() {
        UUID target = UUID.randomUUID();
        dev.alexisbinh.openeco.api.NetworkPolicyStateStore store =
                org.mockito.Mockito.mock(dev.alexisbinh.openeco.api.NetworkPolicyStateStore.class);
        org.mockito.Mockito.when(store.load("openeco-enhancements", target)).thenReturn(Optional.of("250|0"));
        var settings = new EnhancementsPolicyProvider.PolicySettings(true, java.util.List.of(), false, null);
        EnhancementsPolicyProvider provider = new EnhancementsPolicyProvider(settings, Map.of(), store);

        var decision = provider.evaluate(new dev.alexisbinh.openeco.api.MutationPolicyContext(
                dev.alexisbinh.openeco.api.MutationPolicyContext.Kind.PAY,
                UUID.randomUUID(), target, "coins", BigDecimal.TEN));

        assertEquals(new BigDecimal("250"), decision.maximumTargetBalance());
    }

    @Test
    void missingAuthoritativeTargetSnapshotFailsClosed() {
        UUID target = UUID.randomUUID();
        dev.alexisbinh.openeco.api.NetworkPolicyStateStore store =
                org.mockito.Mockito.mock(dev.alexisbinh.openeco.api.NetworkPolicyStateStore.class);
        org.mockito.Mockito.when(store.load("openeco-enhancements", target)).thenReturn(Optional.empty());
        var settings = new EnhancementsPolicyProvider.PolicySettings(true, java.util.List.of(), false, null);
        EnhancementsPolicyProvider provider = new EnhancementsPolicyProvider(settings, Map.of(), store);

        var decision = provider.evaluate(new dev.alexisbinh.openeco.api.MutationPolicyContext(
                dev.alexisbinh.openeco.api.MutationPolicyContext.Kind.PAY,
                UUID.randomUUID(), target, "coins", BigDecimal.TEN));

        org.junit.jupiter.api.Assertions.assertFalse(decision.allowed());
    }

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
