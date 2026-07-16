/*
 * Copyright 2026 alexisbinh
 * Licensed under the Apache License, Version 2.0.
 */
package dev.alexisbinh.openeco.enhancements;

import dev.alexisbinh.openeco.api.NetworkPolicyStateStore;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/** Captures Bukkit permission state on the player's region thread for async policy reads. */
final class EnhancementsPermissionSnapshots implements Listener {
    private final EnhancementsPolicyProvider.PolicySettings settings;
    private final Map<UUID, EnhancementsPolicyProvider.PlayerPolicySnapshot> snapshots =
            new ConcurrentHashMap<>();
    private final NetworkPolicyStateStore networkState;
    private final Logger log;

    EnhancementsPermissionSnapshots(EnhancementsPolicyProvider.PolicySettings settings) {
        this(settings, null, Logger.getLogger("openeco-enhancements-test"));
    }

    EnhancementsPermissionSnapshots(EnhancementsPolicyProvider.PolicySettings settings,
                                    NetworkPolicyStateStore networkState, Logger log) {
        this.settings = settings;
        this.networkState = networkState;
        this.log = log;
    }

    Map<UUID, EnhancementsPolicyProvider.PlayerPolicySnapshot> view() {
        return snapshots;
    }

    void refresh(Player player) {
        BigDecimal maximumBalance = null;
        if (settings.permCapEnabled()
                && !player.hasPermission("openeco.enhancements.bypass.permcap")) {
            for (EnhancementsPolicyProvider.PermissionTier tier : settings.permissionTiers()) {
                if (player.hasPermission(tier.permission())
                        && (maximumBalance == null || tier.cap().compareTo(maximumBalance) > 0)) {
                    maximumBalance = tier.cap();
                }
            }
        }
        boolean payLimitBypass = player.hasPermission("openeco.enhancements.bypass.paylimit");
        UUID playerId = player.getUniqueId();
        var snapshot = new EnhancementsPolicyProvider.PlayerPolicySnapshot(maximumBalance, payLimitBypass);
        var previous = snapshots.put(playerId, snapshot);
        if (networkState != null && !snapshot.equals(previous)) {
            networkState.save("openeco-enhancements", playerId,
                    EnhancementsPolicyProvider.encodeSnapshot(snapshot)).exceptionally(error -> {
                        log.warning("Could not persist network permission policy for "
                                + playerId + ": " + error.getMessage());
                        return null;
                    });
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        refresh(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        snapshots.remove(event.getPlayer().getUniqueId());
    }
}
