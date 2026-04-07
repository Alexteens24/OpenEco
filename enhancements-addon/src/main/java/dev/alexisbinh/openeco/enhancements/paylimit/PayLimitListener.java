package dev.alexisbinh.openeco.enhancements.paylimit;

import dev.alexisbinh.openeco.api.CurrencyInfo;
import dev.alexisbinh.openeco.api.OpenEcoApi;
import dev.alexisbinh.openeco.event.PayEvent;
import dev.alexisbinh.openeco.event.PayCompletedEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enforces a rolling-window cap on how much a player can send via /pay.
 * Tracked purely in memory — resets on server restart.
 */
public class PayLimitListener implements Listener {

    private record UsageWindow(long startedAtMs, BigDecimal totalSent) {}

    private record UsageKey(UUID senderId, String currencyId) {}

    private final Map<UsageKey, UsageWindow> tracker = new ConcurrentHashMap<>();

    private final OpenEcoApi api;
    private final JavaPlugin plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public PayLimitListener(OpenEcoApi api, JavaPlugin plugin) {
        this.api = api;
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPay(PayEvent event) {
        FileConfiguration config = plugin.getConfig();
        if (!config.getBoolean("pay-limit.enabled", false)) return;

        UUID sender = event.getFromId();
        CurrencyInfo currency = resolveCurrency(event.hasCurrencyId() ? event.getCurrencyId() : null);
        String currencyId = currency.id();
        Player senderPlayer = plugin.getServer().getPlayer(sender);
        if (senderPlayer != null && senderPlayer.hasPermission("openeco.enhancements.bypass.paylimit")) {
            return;
        }

        long windowMs = config.getLong("pay-limit.window-seconds", 86400) * 1000L;
        if (windowMs <= 0) {
            return;
        }

        pruneExpiredEntries(System.currentTimeMillis(), windowMs);

        int fractionalDigits = currency.fractionalDigits();
        BigDecimal maxAmount = scale(config.getDouble("pay-limit.max-amount", 10000), fractionalDigits);
        String message = config.getString("pay-limit.message",
                "<red>You have reached your daily pay limit of <yellow><limit><red>.");

        long now = System.currentTimeMillis();
        UsageKey usageKey = new UsageKey(sender, currencyId);
        UsageWindow window = tracker.compute(usageKey,
                (id, existing) -> activeWindow(existing, now, windowMs, fractionalDigits));
        BigDecimal newTotal = window.totalSent().add(event.getAmount());

        if (newTotal.compareTo(maxAmount) > 0) {
            event.setCancelled(true);
            long remainingMs = windowMs - (now - window.startedAtMs());
            String remainingStr = formatDuration(remainingMs);
            String limitStr = api.format(maxAmount, currencyId);
            if (limitStr == null || limitStr.isBlank()) {
                limitStr = maxAmount.toPlainString();
            }
            var player = plugin.getServer().getPlayer(sender);
            if (player != null) {
                player.sendMessage(mm.deserialize(message,
                        Placeholder.unparsed("limit", limitStr),
                        Placeholder.unparsed("remaining", remainingStr)));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPayCompleted(PayCompletedEvent event) {
        FileConfiguration config = plugin.getConfig();
        if (!config.getBoolean("pay-limit.enabled", false)) return;

        long windowMs = config.getLong("pay-limit.window-seconds", 86400) * 1000L;
        if (windowMs <= 0) {
            return;
        }

        long now = System.currentTimeMillis();
        pruneExpiredEntries(now, windowMs);

        CurrencyInfo currency = resolveCurrency(event.hasCurrencyId() ? event.getCurrencyId() : null);
        String currencyId = currency.id();
        int fractionalDigits = currency.fractionalDigits();
        tracker.compute(new UsageKey(event.getFromId(), currencyId), (id, existing) -> {
            UsageWindow window = activeWindow(existing, now, windowMs, fractionalDigits);
            return new UsageWindow(window.startedAtMs(), window.totalSent().add(event.getSent()));
        });
    }

    void pruneExpiredEntries(long now, long windowMs) {
        tracker.entrySet().removeIf(entry -> now - entry.getValue().startedAtMs() >= windowMs);
    }

    int trackedWindowCount() {
        return tracker.size();
    }

    private CurrencyInfo resolveCurrency(String currencyId) {
        if (currencyId != null) {
            CurrencyInfo currency = api.getCurrencyInfo(currencyId);
            if (currency != null) {
                return currency;
            }
        }
        return api.getRules().currency();
    }

    private static UsageWindow activeWindow(UsageWindow existing, long now, long windowMs, int fractionalDigits) {
        if (existing == null || now - existing.startedAtMs() >= windowMs) {
            return new UsageWindow(now, BigDecimal.ZERO.setScale(fractionalDigits, RoundingMode.HALF_UP));
        }
        return existing;
    }

    private static BigDecimal scale(double amount, int fractionalDigits) {
        return BigDecimal.valueOf(amount).setScale(fractionalDigits, RoundingMode.HALF_UP);
    }

    private static String formatDuration(long ms) {
        long totalSeconds = ms / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) return hours + "h " + minutes + "m";
        if (minutes > 0) return minutes + "m " + seconds + "s";
        return seconds + "s";
    }
}
