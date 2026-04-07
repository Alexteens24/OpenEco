package dev.alexisbinh.openeco.enhancements.permcap;

import dev.alexisbinh.openeco.api.CurrencyInfo;
import dev.alexisbinh.openeco.api.OpenEcoApi;
import dev.alexisbinh.openeco.event.BalanceChangeEvent;
import dev.alexisbinh.openeco.event.PayEvent;
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

    private final OpenEcoApi api;
    private final JavaPlugin plugin;

    public PermCapListener(OpenEcoApi api, JavaPlugin plugin) {
        this.api = api;
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBalanceChange(BalanceChangeEvent event) {
        FileConfiguration config = plugin.getConfig();
        if (!config.getBoolean("perm-cap.enabled", false)) return;

        UUID playerId = event.getPlayerId();
        String currencyId = resolveCurrencyId(event.hasCurrencyId() ? event.getCurrencyId() : null);
        Player player = plugin.getServer().getPlayer(playerId);
        if (player == null) return; // offline — skip
        if (player.hasPermission("openeco.enhancements.bypass.permcap")) return;

        if (wouldExceedCap(player, event.getNewBalance(), config, currencyId)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPay(PayEvent event) {
        FileConfiguration config = plugin.getConfig();
        if (!config.getBoolean("perm-cap.enabled", false)) return;

        UUID recipientId = event.getToId();
        boolean explicitCurrency = event.hasCurrencyId() && api.hasCurrency(event.getCurrencyId());
        String currencyId = resolveCurrencyId(explicitCurrency ? event.getCurrencyId() : null);
        Player recipient = plugin.getServer().getPlayer(recipientId);
        if (recipient == null) return; // offline — skip
        if (recipient.hasPermission("openeco.enhancements.bypass.permcap")) return;

        BigDecimal currentBalance = explicitCurrency
                ? api.getBalance(recipientId, currencyId)
                : api.getBalance(recipientId);
        BigDecimal projectedBalance = currentBalance.add(event.getReceived());
        if (wouldExceedCap(recipient, projectedBalance, config, currencyId)) {
            event.setCancelled(true);
        }
    }

    private boolean wouldExceedCap(Player player, BigDecimal newBalance, FileConfiguration config, String currencyId) {
        BigDecimal cap = resolveCapForPlayer(player, config, currencyId);
        if (cap == null) return false;
        return newBalance.compareTo(cap) > 0;
    }

    /**
     * Returns the effective cap for this player:
     * the highest cap from all matching permission tiers, floored at the OpenEco global cap.
     * Returns null if neither tiers nor global cap are set.
     */
    private BigDecimal resolveCapForPlayer(Player player, FileConfiguration config, String currencyId) {
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

        // Global cap from OpenEco (may be null = unlimited)
        CurrencyInfo currencyInfo = api.getCurrencyInfo(currencyId);
        BigDecimal globalCap = currencyInfo != null
            ? currencyInfo.maxBalance()
            : api.getRules().currency().maxBalance();

        if (bestTierCap != null) {
            // Use whichever is higher: tier cap or global cap
            if (globalCap == null) return bestTierCap;
            return bestTierCap.compareTo(globalCap) > 0 ? bestTierCap : globalCap;
        }
        return globalCap; // may be null
    }

    private String resolveCurrencyId(String currencyId) {
        if (currencyId != null && api.hasCurrency(currencyId)) {
            return currencyId;
        }
        return api.getRules().currency().id();
    }
}
