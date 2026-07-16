/*
 * Copyright 2026 alexisbinh
 * Licensed under the Apache License, Version 2.0.
 */
package dev.alexisbinh.openeco.enhancements;

import dev.alexisbinh.openeco.api.EconomyMutationPolicyProvider;
import dev.alexisbinh.openeco.api.MutationPolicyContext;
import dev.alexisbinh.openeco.api.MutationPolicyDecision;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.util.Map;

final class EnhancementsPolicyProvider implements EconomyMutationPolicyProvider {
    private final JavaPlugin plugin;

    EnhancementsPolicyProvider(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public MutationPolicyDecision evaluate(MutationPolicyContext context) {
        FileConfiguration config = plugin.getConfig();
        BigDecimal cap = permissionCap(context, config);
        MutationPolicyDecision.RollingLimit rolling = payLimit(context, config);
        return new MutationPolicyDecision(true, null, cap, rolling);
    }

    private BigDecimal permissionCap(MutationPolicyContext context, FileConfiguration config) {
        if (!config.getBoolean("perm-cap.enabled", false)) return null;
        if (context.kind() == MutationPolicyContext.Kind.WITHDRAW) return null;
        Player target = plugin.getServer().getPlayer(context.targetId());
        if (target == null || target.hasPermission("openeco.enhancements.bypass.permcap")) return null;
        BigDecimal highest = null;
        for (Map<?, ?> tier : config.getMapList("perm-cap.tiers")) {
            Object permission = tier.get("permission");
            Object rawCap = tier.get("cap");
            if (!(permission instanceof String node) || rawCap == null || !target.hasPermission(node)) continue;
            try {
                BigDecimal candidate = new BigDecimal(rawCap.toString());
                if (candidate.signum() >= 0 && (highest == null || candidate.compareTo(highest) > 0)) highest = candidate;
            } catch (NumberFormatException ignored) { }
        }
        return highest;
    }

    private MutationPolicyDecision.RollingLimit payLimit(MutationPolicyContext context, FileConfiguration config) {
        if (context.kind() != MutationPolicyContext.Kind.PAY
                || !config.getBoolean("pay-limit.enabled", false) || context.sourceId() == null) return null;
        Player source = plugin.getServer().getPlayer(context.sourceId());
        if (source != null && source.hasPermission("openeco.enhancements.bypass.paylimit")) return null;
        long windowMs = config.getLong("pay-limit.window-seconds", 86_400L) * 1_000L;
        if (windowMs <= 0) return null;
        Object configured = config.get("pay-limit.max-amount");
        try {
            BigDecimal maximum = new BigDecimal(configured == null ? "10000" : configured.toString());
            return maximum.signum() > 0 ? new MutationPolicyDecision.RollingLimit(maximum, windowMs) : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
