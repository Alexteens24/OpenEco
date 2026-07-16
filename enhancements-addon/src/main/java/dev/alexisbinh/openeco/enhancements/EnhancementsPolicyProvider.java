/*
 * Copyright 2026 alexisbinh
 * Licensed under the Apache License, Version 2.0.
 */
package dev.alexisbinh.openeco.enhancements;

import dev.alexisbinh.openeco.api.EconomyMutationPolicyProvider;
import dev.alexisbinh.openeco.api.MutationPolicyContext;
import dev.alexisbinh.openeco.api.MutationPolicyDecision;
import dev.alexisbinh.openeco.api.NetworkPolicyStateStore;
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
    private final NetworkPolicyStateStore networkState;

    EnhancementsPolicyProvider(PolicySettings settings, Map<UUID, PlayerPolicySnapshot> playerSnapshots) {
        this(settings, playerSnapshots, null);
    }

    EnhancementsPolicyProvider(PolicySettings settings, Map<UUID, PlayerPolicySnapshot> playerSnapshots,
                               NetworkPolicyStateStore networkState) {
        this.settings = settings;
        this.playerSnapshots = playerSnapshots;
        this.networkState = networkState;
    }

    @Override
    public MutationPolicyDecision evaluate(MutationPolicyContext context) {
        PlayerPolicySnapshot targetSnapshot = permissionSnapshotRequired(context)
                ? snapshot(context.targetId()) : null;
        if (permissionSnapshotRequired(context) && targetSnapshot == null) {
            return new MutationPolicyDecision(false,
                    "Authoritative permission snapshot unavailable", null, null);
        }
        BigDecimal cap = targetSnapshot == null ? null : targetSnapshot.maximumBalance();
        MutationPolicyDecision.RollingLimit rolling = payLimit(context);
        return new MutationPolicyDecision(true, null, cap, rolling);
    }

    private boolean permissionSnapshotRequired(MutationPolicyContext context) {
        return settings.permCapEnabled()
                && context.kind() != MutationPolicyContext.Kind.WITHDRAW;
    }

    private MutationPolicyDecision.RollingLimit payLimit(MutationPolicyContext context) {
        if (context.kind() != MutationPolicyContext.Kind.PAY
                || !settings.payLimitEnabled() || settings.payLimit() == null
                || context.sourceId() == null) return null;
        PlayerPolicySnapshot snapshot = snapshot(context.sourceId());
        return snapshot != null && snapshot.payLimitBypass() ? null : settings.payLimit();
    }

    private PlayerPolicySnapshot snapshot(UUID subjectId) {
        PlayerPolicySnapshot local = playerSnapshots.get(subjectId);
        if (local != null || networkState == null) return local;
        return networkState.load("openeco-enhancements", subjectId)
                .map(EnhancementsPolicyProvider::decodeSnapshot)
                .orElse(null);
    }

    static String encodeSnapshot(PlayerPolicySnapshot snapshot) {
        return (snapshot.maximumBalance() == null ? "" : snapshot.maximumBalance().toPlainString())
                + '|' + (snapshot.payLimitBypass() ? '1' : '0');
    }

    static PlayerPolicySnapshot decodeSnapshot(String encoded) {
        String[] parts = encoded.split("\\|", -1);
        if (parts.length != 2) throw new IllegalArgumentException("Invalid policy snapshot");
        BigDecimal cap = parts[0].isBlank() ? null : new BigDecimal(parts[0]);
        return new PlayerPolicySnapshot(cap, "1".equals(parts[1]));
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
