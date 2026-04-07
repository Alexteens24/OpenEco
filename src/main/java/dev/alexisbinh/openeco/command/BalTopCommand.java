package dev.alexisbinh.openeco.command;

import dev.alexisbinh.openeco.Messages;
import dev.alexisbinh.openeco.model.AccountRecord;
import dev.alexisbinh.openeco.service.AccountService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;

public class BalTopCommand implements CommandExecutor, TabCompleter {

    private final AccountService service;
    private final JavaPlugin plugin;
    private final Messages messages;

    public BalTopCommand(AccountService service, JavaPlugin plugin, Messages messages) {
        this.service = service;
        this.plugin = plugin;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("openeco.command.baltop")) {
            messages.send(sender, "no-permission");
            return true;
        }

        if (args.length > 2) {
            sender.sendMessage("§cUsage: /baltop [page] [currency]");
            return true;
        }

        int pageSize = plugin.getConfig().getInt("baltop.page-size", 10);
        if (pageSize < 1) pageSize = 10;

        int page = 1;
        String currencyId = service.getCurrencyId();

        if (args.length > 0) {
            if (isPageNumber(args[0])) {
                page = parsePage(args[0]);
                if (args.length == 2) {
                    currencyId = args[1];
                }
            } else {
                currencyId = args[0];
            }
        }

        if (args.length == 2 && !isPageNumber(args[0])) {
            sender.sendMessage("§cUsage: /baltop [page] [currency]");
            return true;
        }

        if (!service.hasCurrency(currencyId)) {
            messages.send(sender, "unknown-currency");
            return true;
        }

        List<AccountRecord> snapshot = service.getBalTopSnapshot(currencyId);
        int totalPages = (int) Math.ceil((double) snapshot.size() / pageSize);
        if (totalPages == 0) totalPages = 1;
        if (page > totalPages) page = totalPages;

        int start = (page - 1) * pageSize;
        int end = Math.min(start + pageSize, snapshot.size());

        messages.send(sender, "baltop-header",
                Placeholder.unparsed("page", String.valueOf(page)),
                Placeholder.unparsed("total", String.valueOf(totalPages)));
        for (int i = start; i < end; i++) {
            AccountRecord r = snapshot.get(i);
            messages.send(sender, "baltop-entry",
                    Placeholder.unparsed("rank", String.valueOf(i + 1)),
                    Placeholder.unparsed("player", r.getLastKnownName()),
                    Placeholder.unparsed("balance", service.format(r.getBalance(currencyId), currencyId)));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            return service.getCurrencyIds().stream()
                    .filter(id -> id.toLowerCase().startsWith(prefix))
                    .sorted()
                    .toList();
        }
        if (args.length == 2 && isPageNumber(args[0])) {
            String prefix = args[1].toLowerCase();
            return service.getCurrencyIds().stream()
                    .filter(id -> id.toLowerCase().startsWith(prefix))
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

    private static int parsePage(String value) {
        try {
            int page = Integer.parseInt(value);
            return Math.max(page, 1);
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}

