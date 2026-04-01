package dev.alexisbinh.simpleeco.command;

import dev.alexisbinh.simpleeco.Messages;
import dev.alexisbinh.simpleeco.SimpleEcoPlugin;
import dev.alexisbinh.simpleeco.model.AccountRecord;
import dev.alexisbinh.simpleeco.service.AccountService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.milkbowl.vault2.economy.EconomyResponse;
import org.bukkit.command.*;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class EcoCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = Arrays.asList("give", "take", "set", "reset", "delete", "reload");

    private final AccountService service;
    private final SimpleEcoPlugin plugin;
    private final Messages messages;

    public EcoCommand(AccountService service, SimpleEcoPlugin plugin, Messages messages) {
        this.service = service;
        this.plugin = plugin;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§cUsage: /eco <give|take|set|reset|delete|reload> <player> [amount]");
            return true;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("reload")) {
            if (!sender.hasPermission("simpleeco.command.eco.reload")) {
                messages.send(sender, "no-permission");
                return true;
            }
            plugin.reloadSettings();
            messages.send(sender, "eco-reload");
            return true;
        }

        // delete only needs <player>
        if (sub.equals("delete")) {
            if (!sender.hasPermission("simpleeco.command.eco.delete")) {
                messages.send(sender, "no-permission");
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage("§cUsage: /eco delete <player>");
                return true;
            }
            var optTarget = service.findByName(args[1]);
            if (optTarget.isEmpty()) {
                messages.send(sender, "account-not-found", Placeholder.unparsed("player", args[1]));
                return true;
            }
            var target = optTarget.get();
            if (service.deleteAccount(target.getId())) {
                messages.send(sender, "eco-delete",
                        Placeholder.unparsed("player", target.getLastKnownName()));
            } else {
                messages.send(sender, "eco-delete-failed",
                        Placeholder.unparsed("player", target.getLastKnownName()));
            }
            return true;
        }

        // reset only needs <player>
        if (sub.equals("reset")) {
            if (!sender.hasPermission("simpleeco.command.eco.reset")) {
                messages.send(sender, "no-permission");
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage("§cUsage: /eco reset <player>");
                return true;
            }
            var optTarget = service.findByName(args[1]);
            if (optTarget.isEmpty()) {
                messages.send(sender, "account-not-found", Placeholder.unparsed("player", args[1]));
                return true;
            }
            var target = optTarget.get();
            var res = service.reset(target.getId());
            if (!res.transactionSuccess()) {
                sender.sendMessage("§cFailed: " + res.errorMessage);
                return true;
            }
            messages.send(sender, "eco-reset",
                    Placeholder.unparsed("player", target.getLastKnownName()),
                    Placeholder.unparsed("balance", service.format(res.balance)));
            return true;
        }

        // give / take / set require <player> <amount>
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /eco " + sub + " <player> <amount>");
            return true;
        }

        String perm = switch (sub) {
            case "give" -> "simpleeco.command.eco.give";
            case "take" -> "simpleeco.command.eco.take";
            case "set"  -> "simpleeco.command.eco.set";
            default     -> null;
        };
        if (perm == null) {
            sender.sendMessage("§cUnknown subcommand. Use: give, take, set, reset, delete, reload.");
            return true;
        }
        if (!sender.hasPermission(perm)) {
            messages.send(sender, "no-permission");
            return true;
        }

        String targetName = args[1];
        var optTarget = service.findByName(targetName);
        if (optTarget.isEmpty()) {
            messages.send(sender, "account-not-found", Placeholder.unparsed("player", targetName));
            return true;
        }
        AccountRecord target = optTarget.get();

        BigDecimal amount;
        try {
            amount = new BigDecimal(args[2]);
        } catch (NumberFormatException e) {
            messages.send(sender, "invalid-amount");
            return true;
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0 && !sub.equals("set")) {
            messages.send(sender, "negative-amount");
            return true;
        }
        if (amount.compareTo(BigDecimal.ZERO) < 0 && sub.equals("set")) {
            messages.send(sender, "negative-amount");
            return true;
        }

        switch (sub) {
            case "give" -> {
                EconomyResponse res = service.deposit(target.getId(), amount);
                if (!res.transactionSuccess()) {
                    String limit = service.getFormattedMaxBalance();
                    if (limit != null && res.errorMessage.contains("limit")) {
                        messages.send(sender, "eco-balance-limit",
                                Placeholder.unparsed("player", target.getLastKnownName()),
                                Placeholder.unparsed("limit", limit));
                    } else {
                        messages.send(sender, "eco-give-failed",
                                Placeholder.unparsed("player", target.getLastKnownName()));
                    }
                    return true;
                }
                messages.send(sender, "eco-give",
                        Placeholder.unparsed("amount", service.format(amount)),
                        Placeholder.unparsed("player", target.getLastKnownName()),
                        Placeholder.unparsed("balance", service.format(res.balance)));
            }
            case "take" -> {
                EconomyResponse res = service.withdraw(target.getId(), amount);
                if (!res.transactionSuccess()) {
                    messages.send(sender, "eco-take-failed",
                            Placeholder.unparsed("player", target.getLastKnownName()));
                    return true;
                }
                messages.send(sender, "eco-take",
                        Placeholder.unparsed("amount", service.format(amount)),
                        Placeholder.unparsed("player", target.getLastKnownName()),
                        Placeholder.unparsed("balance", service.format(res.balance)));
            }
            case "set" -> {
                EconomyResponse res = service.set(target.getId(), amount);
                if (!res.transactionSuccess()) {
                    String limit = service.getFormattedMaxBalance();
                    if (limit != null && res.errorMessage.contains("limit")) {
                        messages.send(sender, "eco-balance-limit",
                                Placeholder.unparsed("player", target.getLastKnownName()),
                                Placeholder.unparsed("limit", limit));
                    } else {
                        messages.send(sender, "eco-set-failed",
                                Placeholder.unparsed("player", target.getLastKnownName()));
                    }
                    return true;
                }
                messages.send(sender, "eco-set",
                        Placeholder.unparsed("player", target.getLastKnownName()),
                        Placeholder.unparsed("balance", service.format(res.balance)));
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            return SUBCOMMANDS.stream()
                    .filter(s -> s.startsWith(prefix))
                    .toList();
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("give") || sub.equals("take") || sub.equals("set") || sub.equals("reset") || sub.equals("delete")) {
                String prefix = args[1].toLowerCase();
                return service.getUUIDNameMap().values().stream()
                        .filter(n -> n.toLowerCase().startsWith(prefix))
                        .sorted()
                        .toList();
            }
        }
        return Collections.emptyList();
    }
}
