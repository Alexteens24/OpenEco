package dev.alexisbinh.simpleeco.enhancements.permcap;

import dev.alexisbinh.simpleeco.api.EconomyRulesSnapshot;
import dev.alexisbinh.simpleeco.api.SimpleEcoApi;
import dev.alexisbinh.simpleeco.event.BalanceChangeEvent;
import dev.alexisbinh.simpleeco.event.PayEvent;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Intercepts {@link BalanceChangeEvent} and cancels mutations that would push an account's
 * balance past their permission-granted cap.  Only online players are checked — offline
 * writes (e.g. admin /eco give on an offline player) are allowed through.
 */
public class PermCapListener implements Listener {

    private final SimpleEcoApi api;
    private final JavaPlugin plugin;

    public PermCapListener(SimpleEcoApi api, JavaPlugin plugin) {
        this.api = api;
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBalanceChange(BalanceChangeEvent event) {
        FileConfiguration config = plugin.getConfig();
        if (!config.getBoolean("perm-cap.enabled", false)) return;

        UUID playerId = event.getPlayerId();
        Player player = plugin.getServer().getPlayer(playerId);
        if (player == null) return; // offline — skip
        if (player.hasPermission("simpleeco.enhancements.bypass.permcap")) return;

        if (wouldExceedCap(player, event.getNewBalance(), config)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPay(PayEvent event) {
        FileConfiguration config = plugin.getConfig();
        if (!config.getBoolean("perm-cap.enabled", false)) return;

        UUID recipientId = event.getToId();
        Player recipient = plugin.getServer().getPlayer(recipientId);
        if (recipient == null) return; // offline — skip
        if (recipient.hasPermission("simpleeco.enhancements.bypass.permcap")) return;

        BigDecimal projectedBalance = api.getBalance(recipientId).add(event.getReceived());
        if (wouldExceedCap(recipient, projectedBalance, config)) {
            event.setCancelled(true);
        }
    }

    private boolean wouldExceedCap(Player player, BigDecimal newBalance, FileConfiguration config) {
        BigDecimal cap = resolveCapForPlayer(player, config);
        if (cap == null) return false;
        return newBalance.compareTo(cap) > 0;
    }

    /**
     * Returns the effective cap for this player:
     * the highest cap from all matching permission tiers, floored at the SimpleEco global cap.
     * Returns null if neither tiers nor global cap are set.
     */
    private BigDecimal resolveCapForPlayer(Player player, FileConfiguration config) {
        List<Map<?, ?>> tiers = config.getMapList("perm-cap.tiers");
        BigDecimal bestTierCap = null;

        for (Map<?, ?> entry : tiers) {
            String perm = (String) entry.get("permission");
            Object capObj = entry.get("cap");
            if (perm == null || capObj == null) continue;
            if (!(capObj instanceof Number n)) continue;
            double capValue = n.doubleValue();
            if (capValue < 0) continue;
            if (!player.hasPermission(perm)) continue;
            BigDecimal tierCap = BigDecimal.valueOf(capValue);
            if (bestTierCap == null || tierCap.compareTo(bestTierCap) > 0) {
                bestTierCap = tierCap;
            }
        }

        // Global cap from SimpleEco (may be null = unlimited)
        EconomyRulesSnapshot rules = api.getRules();
        BigDecimal globalCap = rules.currency().maxBalance();

        if (bestTierCap != null) {
            // Use whichever is higher: tier cap or global cap
            if (globalCap == null) return bestTierCap;
            return bestTierCap.compareTo(globalCap) > 0 ? bestTierCap : globalCap;
        }
        return globalCap; // may be null
    }
}
