package dev.alexisbinh.openeco.command;

import dev.alexisbinh.openeco.Messages;
import dev.alexisbinh.openeco.model.AccountRecord;
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
            sender.sendMessage("§cUsage: /history [self|player] [page] [currency]");
            return true;
        }

        HistoryRequest request;
        if (sender instanceof Player player) {
            request = resolvePlayerRequest(player, args);
        } else {
            request = resolveConsoleRequest(sender, args);
        }
        if (request == null) {
            return true;
        }

        if (!service.hasCurrency(request.currencyId())) {
            messages.send(sender, "unknown-currency");
            return true;
        }

        final UUID targetId = request.targetId();
        final String targetName = request.targetName();
        final int requestedPage = request.page();
        final String currencyId = request.currencyId();

        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            try {
                int totalEntries = service.countTransactions(targetId, currencyId);
                int totalPages = Math.max(1, (int) Math.ceil((double) totalEntries / PAGE_SIZE));
                int page = Math.min(requestedPage, totalPages);
                List<TransactionEntry> entries = service.getTransactions(targetId, currencyId, page, PAGE_SIZE);
                Map<UUID, String> nameMap = service.getUUIDNameMap();

                dispatchReply(sender, () -> {
                    messages.send(sender, "history-header",
                            Placeholder.unparsed("player", targetName),
                            Placeholder.unparsed("page", String.valueOf(page)),
                            Placeholder.unparsed("total", String.valueOf(totalPages)));
                    if (entries.isEmpty()) {
                        messages.send(sender, "history-empty");
                        return;
                    }
                    for (TransactionEntry entry : entries) {
                        sender.sendMessage(formatEntry(entry, nameMap, currencyId));
                    }
                });
            } catch (SQLException ex) {
                plugin.getLogger().warning("Failed to load transaction history for " + targetId + ": " + ex.getMessage());
                dispatchReply(sender, () -> messages.send(sender, "history-error"));
            }
        });
        return true;
    }

    private HistoryRequest resolvePlayerRequest(Player player, String[] args) {
        if (args.length == 0) {
            if (!player.hasPermission("openeco.command.history")) {
                messages.send(player, "no-permission");
                return null;
            }
            return createSelfRequest(player, args, 0);
        }

        if (args[0].equalsIgnoreCase("self")) {
            if (!player.hasPermission("openeco.command.history")) {
                messages.send(player, "no-permission");
                return null;
            }
            return createSelfRequest(player, args, 1);
        }

        if (player.hasPermission("openeco.command.history.others")) {
            var target = service.findByName(args[0]);
            if (target.isPresent()) {
                return createOtherRequest(target.get(), args, 1);
            }
        }

        if (isPageNumber(args[0]) || service.hasCurrency(args[0])) {
            if (!player.hasPermission("openeco.command.history")) {
                messages.send(player, "no-permission");
                return null;
            }
            return createSelfRequest(player, args, 0);
        }

        if (!player.hasPermission("openeco.command.history.others")) {
            messages.send(player, "no-permission");
            return null;
        }

        messages.send(player, "account-not-found", Placeholder.unparsed("player", args[0]));
        return null;
    }

    private HistoryRequest resolveConsoleRequest(CommandSender sender, String[] args) {
        if (args.length == 0) {
            messages.send(sender, "console-player-only");
            return null;
        }
        if (!sender.hasPermission("openeco.command.history.others")) {
            messages.send(sender, "no-permission");
            return null;
        }

        var target = service.findByName(args[0]);
        if (target.isEmpty()) {
            messages.send(sender, "account-not-found", Placeholder.unparsed("player", args[0]));
            return null;
        }
        return createOtherRequest(target.get(), args, 1);
    }

    private HistoryRequest createSelfRequest(Player player, String[] args, int startIndex) {
        ParsedArguments parsed = parseArguments(args, startIndex);
        return new HistoryRequest(player.getUniqueId(), player.getName(), parsed.page(), parsed.currencyId());
    }

    private HistoryRequest createOtherRequest(AccountRecord target, String[] args, int startIndex) {
        ParsedArguments parsed = parseArguments(args, startIndex);
        return new HistoryRequest(target.getId(), target.getLastKnownName(), parsed.page(), parsed.currencyId());
    }

    private ParsedArguments parseArguments(String[] args, int startIndex) {
        int page = 1;
        String currencyId = service.getCurrencyId();

        if (args.length > startIndex) {
            if (isPageNumber(args[startIndex])) {
                page = parsePageSafe(args[startIndex]);
                if (args.length > startIndex + 1) {
                    currencyId = args[startIndex + 1];
                }
            } else {
                currencyId = args[startIndex];
                if (args.length > startIndex + 1) {
                    page = parsePageSafe(args[startIndex + 1]);
                }
            }
        }

        return new ParsedArguments(page, currencyId);
    }

    private void dispatchReply(CommandSender sender, Runnable reply) {
        if (sender instanceof Player player) {
            player.getScheduler().run(plugin, task -> reply.run(), () -> { });
            return;
        }
        plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> reply.run());
    }

    private Component formatEntry(TransactionEntry entry, Map<UUID, String> nameMap, String fallbackCurrencyId) {
        String date = DATE_FORMAT.format(Instant.ofEpochMilli(entry.getTimestamp()));
        String currencyId = entry.hasCurrencyId() ? entry.getCurrencyId() : fallbackCurrencyId;
        String amount = service.format(entry.getAmount(), currencyId);
        String balance = service.format(entry.getBalanceAfter(), currencyId);

        if (entry.hasMetadata()) {
            return messages.getOrDefault("history-custom",
                    "<dark_gray>[<date>] <aqua><kind> <yellow><amount> <gray>(<details>)",
                    Placeholder.unparsed("date", date),
                    Placeholder.unparsed("kind", humanizeType(entry)),
                    Placeholder.unparsed("amount", signedAmount(entry, amount)),
                    Placeholder.unparsed("balance", balance),
                    Placeholder.unparsed("source", entry.getSource() != null ? entry.getSource() : ""),
                    Placeholder.unparsed("note", entry.getNote() != null ? entry.getNote() : ""),
                    Placeholder.unparsed("details", customDetails(entry)));
        }

        String counterpart = entry.getCounterpartId() != null
                ? nameMap.getOrDefault(entry.getCounterpartId(), entry.getCounterpartId().toString())
                : "Admin";

        String key = switch (entry.getType()) {
            case GIVE -> "history-give";
            case TAKE -> "history-take";
            case SET -> "history-set";
            case RESET -> "history-reset";
            case PAY_SENT -> "history-pay-sent";
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
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> suggestions = new java.util.ArrayList<>(service.getAccountNames().stream()
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted()
                    .toList());
            if (sender instanceof Player && "self".startsWith(prefix) && !suggestions.contains("self")) {
                suggestions.add("self");
            }
            suggestions.addAll(service.getCurrencyIds().stream()
                    .filter(id -> id.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted()
                    .filter(id -> !suggestions.contains(id))
                    .toList());
            return suggestions;
        }

        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> suggestions = new java.util.ArrayList<>();
            if (sender instanceof Player && "self".startsWith(prefix)) {
                suggestions.add("self");
            }
            suggestions.addAll(service.getCurrencyIds().stream()
                    .filter(id -> id.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted()
                    .filter(id -> !suggestions.contains(id))
                    .toList());
            return suggestions;
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("self")
                || !sender.hasPermission("openeco.command.history.others")
                || service.findByName(args[0]).isPresent()
                || isPageNumber(args[0]))) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return service.getCurrencyIds().stream()
                    .filter(id -> id.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted()
                    .toList();
        }

        if (args.length == 3 && isPageNumber(args[1])) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            return service.getCurrencyIds().stream()
                    .filter(id -> id.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted()
                    .toList();
        }

        return Collections.emptyList();
    }

    private static boolean isPageNumber(String value) {
        try {
            Integer.parseInt(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static int parsePageSafe(String value) {
        try {
            int page = Integer.parseInt(value);
            return Math.max(page, 1);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private record ParsedArguments(int page, String currencyId) {}

    private record HistoryRequest(UUID targetId, String targetName, int page, String currencyId) {}
}
