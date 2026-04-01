package dev.alexisbinh.simpleeco.command;

import dev.alexisbinh.simpleeco.Messages;
import dev.alexisbinh.simpleeco.model.TransactionEntry;
import dev.alexisbinh.simpleeco.service.AccountService;
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
        UUID targetId;
        String targetName;
        int page = 1;

        if (args.length == 0 || (args.length == 1 && isPageNumber(args[0]))) {
            if (!(sender instanceof Player player)) {
                messages.send(sender, "console-player-only");
                return true;
            }
            if (!sender.hasPermission("simpleeco.command.history")) {
                messages.send(sender, "no-permission");
                return true;
            }
            targetId = player.getUniqueId();
            targetName = player.getName();
            if (args.length == 1) page = parsePageSafe(args[0]);
        } else {
            if (!sender.hasPermission("simpleeco.command.history.others")) {
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
            if (args.length >= 2) page = parsePageSafe(args[1]);
        }

        final UUID fTargetId = targetId;
        final String fTargetName = targetName;
        final int fPage = page;
        final int fPageSize = PAGE_SIZE;

        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            try {
                int total = service.countTransactions(fTargetId);
                int totalPages = Math.max(1, (int) Math.ceil((double) total / fPageSize));
                int clampedPage = Math.min(fPage, totalPages);
                List<TransactionEntry> entries = service.getTransactions(fTargetId, clampedPage, fPageSize);
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
                        sender.sendMessage(formatEntry(e, nameMap));
                    }
                });
            } catch (SQLException ex) {
                dispatchReply(sender, () ->
                    messages.send(sender, "history-error",
                            Placeholder.unparsed("message", ex.getMessage())));
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

    private Component formatEntry(TransactionEntry e, Map<UUID, String> nameMap) {
        String date = DATE_FORMAT.format(Instant.ofEpochMilli(e.getTimestamp()));
        String amount = service.format(e.getAmount());
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
                Placeholder.unparsed("balance", service.format(e.getBalanceAfter())),
                Placeholder.unparsed("counterpart", counterpart));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1 && sender.hasPermission("simpleeco.command.history.others")) {
            String prefix = args[0].toLowerCase();
            return service.getUUIDNameMap().values().stream()
                    .filter(n -> n.toLowerCase().startsWith(prefix))
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
