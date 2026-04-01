package dev.alexisbinh.simpleeco.command;

import dev.alexisbinh.simpleeco.Messages;
import dev.alexisbinh.simpleeco.model.AccountRecord;
import dev.alexisbinh.simpleeco.service.AccountService;
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
        if (!sender.hasPermission("simpleeco.command.baltop")) {
            messages.send(sender, "no-permission");
            return true;
        }

        int pageSize = plugin.getConfig().getInt("baltop.page-size", 10);
        if (pageSize < 1) pageSize = 10;

        int page = 1;
        if (args.length > 0) {
            try {
                page = Integer.parseInt(args[0]);
                if (page < 1) page = 1;
            } catch (NumberFormatException e) {
                sender.sendMessage("§cInvalid page number.");
                return true;
            }
        }

        List<AccountRecord> snapshot = service.getBalTopSnapshot();
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
                    Placeholder.unparsed("balance", service.format(r.getBalance())));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        return Collections.emptyList();
    }
}

