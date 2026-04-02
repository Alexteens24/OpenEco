package dev.alexisbinh.simpleeco.command;

import dev.alexisbinh.simpleeco.Messages;
import dev.alexisbinh.simpleeco.service.AccountService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;

public class BalanceCommand implements CommandExecutor, TabCompleter {

    private final AccountService service;
    private final Messages messages;

    public BalanceCommand(AccountService service, Messages messages) {
        this.service = service;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                messages.send(sender, "console-player-only");
                return true;
            }
            if (!player.hasPermission("simpleeco.command.balance")) {
                messages.send(sender, "no-permission");
                return true;
            }
            String bal = service.format(service.getBalance(player.getUniqueId()));
            messages.send(player, "balance-self", Placeholder.unparsed("balance", bal));
            return true;
        }

        if (!sender.hasPermission("simpleeco.command.balance.others")) {
            messages.send(sender, "no-permission");
            return true;
        }

        String targetName = args[0];
        var optAccount = service.findByName(targetName);
        if (optAccount.isEmpty()) {
            messages.send(sender, "account-not-found", Placeholder.unparsed("player", targetName));
            return true;
        }
        var account = optAccount.get();
        String bal = service.format(account.getBalance());
        messages.send(sender, "balance-other",
                Placeholder.unparsed("player", account.getLastKnownName()),
                Placeholder.unparsed("balance", bal));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1 && sender.hasPermission("simpleeco.command.balance.others")) {
            String prefix = args[0].toLowerCase();
            return service.getAccountNames().stream()
                    .filter(n -> n.toLowerCase().startsWith(prefix))
                    .sorted()
                    .toList();
        }
        return Collections.emptyList();
    }
}

