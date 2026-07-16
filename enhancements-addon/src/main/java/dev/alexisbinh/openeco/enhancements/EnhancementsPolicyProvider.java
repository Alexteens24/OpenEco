/*
 * Copyright 2026 alexisbinh
 * Licensed under the Apache License, Version 2.0.
 */
package dev.alexisbinh.openeco.enhancements;

import dev.alexisbinh.openeco.api.EconomyMutationPolicyProvider;
import dev.alexisbinh.openeco.api.MutationPolicyContext;
import dev.alexisbinh.openeco.api.MutationPolicyDecision;
import org.bukkit.configuration.file.FileConfiguration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class EnhancementsPolicyProvider implements EconomyMutationPolicyProvider {
    record PermissionTier(String permission, BigDecimal cap) { }

    record PolicySettings(boolean permCapEnabled, List<PermissionTier> permissionTiers,
                          boolean payLimitEnabled,
                          MutationPolicyDecision.RollingLimit payLimit) {
        PolicySettings {
            permissionTiers = List.copyOf(permissionTiers);
        }
    }

    record PlayerPolicySnapshot(BigDecimal maximumBalance, boolean payLimitBypass) { }

    private final PolicySettings settings;
    private final Map<UUID, PlayerPolicySnapshot> playerSnapshots;

    EnhancementsPolicyProvider(PolicySettings settings, Map<UUID, PlayerPolicySnapshot> playerSnapshots) {
        this.settings = settings;
        this.playerSnapshots = playerSnapshots;
    }

    @Override
    public MutationPolicyDecision evaluate(MutationPolicyContext context) {
        BigDecimal cap = permissionCap(context);
        MutationPolicyDecision.RollingLimit rolling = payLimit(context);
        return new MutationPolicyDecision(true, null, cap, rolling);
    }

    private BigDecimal permissionCap(MutationPolicyContext context) {
        if (!settings.permCapEnabled()) return null;
        if (context.kind() == MutationPolicyContext.Kind.WITHDRAW) return null;
        PlayerPolicySnapshot snapshot = playerSnapshots.get(context.targetId());
        return snapshot == null ? null : snapshot.maximumBalance();
    }

    private MutationPolicyDecision.RollingLimit payLimit(MutationPolicyContext context) {
        if (context.kind() != MutationPolicyContext.Kind.PAY
                || !settings.payLimitEnabled() || settings.payLimit() == null
                || context.sourceId() == null) return null;
        PlayerPolicySnapshot snapshot = playerSnapshots.get(context.sourceId());
        return snapshot != null && snapshot.payLimitBypass() ? null : settings.payLimit();
    }

    static PolicySettings settingsFrom(FileConfiguration config) {
        List<PermissionTier> tiers = new ArrayList<>();
        for (Map<?, ?> tier : config.getMapList("perm-cap.tiers")) {
            Object permission = tier.get("permission");
            Object rawCap = tier.get("cap");
            if (!(permission instanceof String node) || node.isBlank() || rawCap == null) continue;
            try {
                BigDecimal cap = new BigDecimal(rawCap.toString());
                if (cap.signum() >= 0) tiers.add(new PermissionTier(node, cap));
            } catch (NumberFormatException ignored) { }
        }
        MutationPolicyDecision.RollingLimit payLimit = null;
        long windowMs = config.getLong("pay-limit.window-seconds", 86_400L) * 1_000L;
        Object configured = config.get("pay-limit.max-amount");
        try {
            BigDecimal maximum = new BigDecimal(configured == null ? "10000" : configured.toString());
            if (maximum.signum() > 0 && windowMs > 0) {
                payLimit = new MutationPolicyDecision.RollingLimit(maximum, windowMs);
            }
        } catch (NumberFormatException ignored) { }
        return new PolicySettings(
                config.getBoolean("perm-cap.enabled", false), tiers,
                config.getBoolean("pay-limit.enabled", false), payLimit);
    }
}
