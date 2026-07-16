/*
 * Copyright 2026 alexisbinh
 * Licensed under the Apache License, Version 2.0.
 */
package dev.alexisbinh.openeco.enhancements;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EnhancementsPermissionSnapshotsTest {

    @Test
    void refreshCapturesHighestTierAndBypassesOnCallingThread() {
        var settings = new EnhancementsPolicyProvider.PolicySettings(
                true,
                List.of(
                        new EnhancementsPolicyProvider.PermissionTier("tier.low", new BigDecimal("100")),
                        new EnhancementsPolicyProvider.PermissionTier("tier.high", new BigDecimal("500"))),
                true,
                null);
        EnhancementsPermissionSnapshots snapshots = new EnhancementsPermissionSnapshots(settings);
        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.hasPermission("tier.low")).thenReturn(true);
        when(player.hasPermission("tier.high")).thenReturn(true);

        snapshots.refresh(player);

        var captured = snapshots.view().get(playerId);
        assertEquals(new BigDecimal("500"), captured.maximumBalance());
        assertFalse(captured.payLimitBypass());
    }
}
