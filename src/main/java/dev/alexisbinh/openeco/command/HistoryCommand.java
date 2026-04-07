package dev.alexisbinh.openeco.command;

import dev.alexisbinh.openeco.Messages;
import dev.alexisbinh.openeco.model.TransactionEntry;
import dev.alexisbinh.openeco.service.AccountService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class HistoryCommand implements CommandExecutor, TabCompleter {

    private static final int PAGE_SIZE = 10;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final AccountService service;
    private final JavaPlugin plugin;
    private final Messages messages;

    public HistoryCommand(AccountService service, JavaPlugin plugin, Messages messages) {
        this.service = service;
        this.plugin = plugin;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length > 3) {
            sender.sendMessage("§cUsage: /history [player] [page] [currency]");
            return true;
        }

        UUID targetId;
        String targetName;
        int page = 1;
        String currencyId = service.getCurrencyId();

        boolean selfRequest = args.length == 0
                || (sender instanceof Player && (isPageNumber(args[0]) || service.hasCurrency(args[0])));

        if (selfRequest) {
            if (!(sender instanceof Player player)) {
                messages.send(sender, "console-player-only");
                return true;
            }
            if (!sender.hasPermission("openeco.command.history")) {
                messages.send(sender, "no-permission");
                return true;
            }
            targetId = player.getUniqueId();
            targetName = player.getName();
            if (args.length >= 1) {
                if (isPageNumber(args[0])) {
                    page = parsePageSafe(args[0]);
                    if (args.length == 2) {
                        currencyId = args[1];
                    }
                } else {
                    currencyId = args[0];
                    if (args.length == 2) {
                        page = parsePageSafe(args[1]);
                    }
                }
            }
        } else {
            if (!sender.hasPermission("openeco.command.history.others")) {
                messages.send(sender, "no-permission");
                return true;
            }
            var opt = service.findByName(args[0]);
            if (opt.isEmpty()) {
                messages.send(sender, "account-not-found", Placeholder.unparsed("player", args[0]));
                return true;
            }
            targetId = opt.get().getId();
            targetName = opt.get().getLastKnownName();
            if (args.length >= 2) {
                if (isPageNumber(args[1])) {
                    page = parsePageSafe(args[1]);
                    if (args.length == 3) {
                        currencyId = args[2];
                    }
                } else {
                    currencyId = args[1];
                    if (args.length == 3) {
                        page = parsePageSafe(args[2]);
                    }
                }
            }
        }

        if (!service.hasCurrency(currencyId)) {
            messages.send(sender, "unknown-currency");
            return true;
        }

        final UUID fTargetId = targetId;
        final String fTargetName = targetName;
        final int fPage = page;
        final int fPageSize = PAGE_SIZE;
        final String fCurrencyId = currencyId;

        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            try {
                int total = service.countTransactions(fTargetId, fCurrencyId);
                int totalPages = Math.max(1, (int) Math.ceil((double) total / fPageSize));
                int clampedPage = Math.min(fPage, totalPages);
                List<TransactionEntry> entries = service.getTransactions(fTargetId, fCurrencyId, clampedPage, fPageSize);
                Map<UUID, String> nameMap = service.getUUIDNameMap();

                dispatchReply(sender, () -> {
                    messages.send(sender, "history-header",
                            Placeholder.unparsed("player", fTargetName),
                            Placeholder.unparsed("page", String.valueOf(clampedPage)),
                            Placeholder.unparsed("total", String.valueOf(totalPages)));
                    if (entries.isEmpty()) {
                        messages.send(sender, "history-empty");
                        return;
                    }
                    for (TransactionEntry e : entries) {
                        sender.sendMessage(formatEntry(e, nameMap, fCurrencyId));
                    }
                });
            } catch (SQLException ex) {
                plugin.getLogger().warning("Failed to load transaction history for " + fTargetId + ": " + ex.getMessage());
                dispatchReply(sender, () -> messages.send(sender, "history-error"));
            }
        });
        return true;
    }

    private void dispatchReply(CommandSender sender, Runnable reply) {
        if (sender instanceof Player player) {
            player.getScheduler().run(plugin, task -> reply.run(), () -> { });
            return;
        }
        plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> reply.run());
    }

    private Component formatEntry(TransactionEntry e, Map<UUID, String> nameMap, String fallbackCurrencyId) {
        String date = DATE_FORMAT.format(Instant.ofEpochMilli(e.getTimestamp()));
        String currencyId = e.hasCurrencyId() ? e.getCurrencyId() : fallbackCurrencyId;
        String amount = service.format(e.getAmount(), currencyId);
        String balance = service.format(e.getBalanceAfter(), currencyId);

        if (e.hasMetadata()) {
            return messages.getOrDefault("history-custom",
                "<dark_gray>[<date>] <aqua><kind> <yellow><amount> <gray>(<details>)",
                    Placeholder.unparsed("date", date),
                    Placeholder.unparsed("kind", humanizeType(e)),
                    Placeholder.unparsed("amount", signedAmount(e, amount)),
                    Placeholder.unparsed("balance", balance),
                    Placeholder.unparsed("source", e.getSource() != null ? e.getSource() : ""),
                    Placeholder.unparsed("note", e.getNote() != null ? e.getNote() : ""),
                    Placeholder.unparsed("details", customDetails(e)));
        }

        String counterpart = e.getCounterpartId() != null
                ? nameMap.getOrDefault(e.getCounterpartId(), e.getCounterpartId().toString())
                : "Admin";

        String key = switch (e.getType()) {
            case GIVE         -> "history-give";
            case TAKE         -> "history-take";
            case SET          -> "history-set";
            case RESET        -> "history-reset";
            case PAY_SENT     -> "history-pay-sent";
            case PAY_RECEIVED -> "history-pay-received";
        };
        return messages.get(key,
                Placeholder.unparsed("date", date),
                Placeholder.unparsed("amount", amount),
                Placeholder.unparsed("balance", balance),
                Placeholder.unparsed("counterpart", counterpart));
    }

    private static String signedAmount(TransactionEntry entry, String formattedAmount) {
        return switch (entry.getType()) {
            case GIVE, PAY_RECEIVED -> "+" + formattedAmount;
            case TAKE, PAY_SENT -> "-" + formattedAmount;
            case SET, RESET -> formattedAmount;
        };
    }

    private static String customDetails(TransactionEntry entry) {
        if (entry.getSource() != null && entry.getNote() != null) {
            return entry.getSource() + ": " + entry.getNote();
        }
        if (entry.getSource() != null) {
            return entry.getSource();
        }
        if (entry.getNote() != null) {
            return entry.getNote();
        }
        return humanizeType(entry);
    }

    private static String humanizeType(TransactionEntry entry) {
        String raw = entry.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1 && sender.hasPermission("openeco.command.history.others")) {
            String prefix = args[0].toLowerCase();
            List<String> suggestions = new java.util.ArrayList<>(service.getAccountNames().stream()
                .filter(n -> n.toLowerCase().startsWith(prefix))
                .sorted()
                .toList());
            suggestions.addAll(service.getCurrencyIds().stream()
                .filter(id -> id.toLowerCase().startsWith(prefix))
                .sorted()
                .filter(id -> !suggestions.contains(id))
                .toList());
            return suggestions;
        }
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            return service.getCurrencyIds().stream()
                .filter(id -> id.toLowerCase().startsWith(prefix))
                .sorted()
                .toList();
        }
        if (args.length == 2 && (!sender.hasPermission("openeco.command.history.others")
            || service.findByName(args[0]).isPresent() || isPageNumber(args[0]))) {
            String prefix = args[1].toLowerCase();
            return service.getCurrencyIds().stream()
                .filter(id -> id.toLowerCase().startsWith(prefix))
                .sorted()
                .toList();
        }
        if (args.length == 3 && isPageNumber(args[1])) {
            String prefix = args[2].toLowerCase();
            return service.getCurrencyIds().stream()
                .filter(id -> id.toLowerCase().startsWith(prefix))
                .sorted()
                .toList();
        }
        return Collections.emptyList();
    }

    private static boolean isPageNumber(String s) {
        try { Integer.parseInt(s); return true; } catch (NumberFormatException e) { return false; }
    }

    private static int parsePageSafe(String s) {
        try { int p = Integer.parseInt(s); return p < 1 ? 1 : p; } catch (NumberFormatException e) { return 1; }
    }
}
