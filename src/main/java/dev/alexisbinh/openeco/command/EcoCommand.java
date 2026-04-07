package dev.alexisbinh.openeco.command;

import dev.alexisbinh.openeco.Messages;
import dev.alexisbinh.openeco.OpenEcoPlugin;
import dev.alexisbinh.openeco.model.AccountRecord;
import dev.alexisbinh.openeco.service.AccountService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.milkbowl.vault2.economy.EconomyResponse;
import org.bukkit.command.*;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class EcoCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = Arrays.asList("give", "take", "set", "reset", "delete", "freeze", "unfreeze", "rename", "reload");

    private final AccountService service;
    private final OpenEcoPlugin plugin;
    private final Messages messages;

    public EcoCommand(AccountService service, OpenEcoPlugin plugin, Messages messages) {
        this.service = service;
        this.plugin = plugin;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§cUsage: /eco <give|take|set|reset|delete|freeze|unfreeze|rename|reload> <player> [amount] [currency]");
            return true;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("reload")) {
            if (!sender.hasPermission("openeco.command.eco.reload")) {
                messages.send(sender, "no-permission");
                return true;
            }
            plugin.reloadSettings();
            messages.send(sender, "eco-reload");
            return true;
        }

        // delete only needs <player>
        if (sub.equals("delete")) {
            if (!sender.hasPermission("openeco.command.eco.delete")) {
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

        // freeze / unfreeze only need <player>
        if (sub.equals("freeze")) {
            if (!sender.hasPermission("openeco.command.eco.freeze")) {
                messages.send(sender, "no-permission");
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage("§cUsage: /eco freeze <player>");
                return true;
            }
            var optTarget = service.findByName(args[1]);
            if (optTarget.isEmpty()) {
                messages.send(sender, "account-not-found", Placeholder.unparsed("player", args[1]));
                return true;
            }
            var target = optTarget.get();
            if (service.freezeAccount(target.getId())) {
                messages.send(sender, "eco-freeze",
                        Placeholder.unparsed("player", target.getLastKnownName()));
            } else {
                messages.send(sender, "eco-freeze-failed",
                        Placeholder.unparsed("player", target.getLastKnownName()));
            }
            return true;
        }

        if (sub.equals("unfreeze")) {
            if (!sender.hasPermission("openeco.command.eco.unfreeze")) {
                messages.send(sender, "no-permission");
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage("§cUsage: /eco unfreeze <player>");
                return true;
            }
            var optTarget = service.findByName(args[1]);
            if (optTarget.isEmpty()) {
                messages.send(sender, "account-not-found", Placeholder.unparsed("player", args[1]));
                return true;
            }
            var target = optTarget.get();
            if (service.unfreezeAccount(target.getId())) {
                messages.send(sender, "eco-unfreeze",
                        Placeholder.unparsed("player", target.getLastKnownName()));
            } else {
                messages.send(sender, "eco-unfreeze-failed",
                        Placeholder.unparsed("player", target.getLastKnownName()));
            }
            return true;
        }

        // rename needs <player> <newname>
        if (sub.equals("rename")) {
            if (!sender.hasPermission("openeco.command.eco.rename")) {
                messages.send(sender, "no-permission");
                return true;
            }
            if (args.length < 3) {
                sender.sendMessage("§cUsage: /eco rename <player> <newname>");
                return true;
            }
            var optTarget = service.findByName(args[1]);
            if (optTarget.isEmpty()) {
                messages.send(sender, "account-not-found", Placeholder.unparsed("player", args[1]));
                return true;
            }
            var target = optTarget.get();
            var status = service.renameAccountDetailed(target.getId(), args[2]);
            switch (status) {
                case RENAMED, UNCHANGED -> messages.send(sender, "eco-rename",
                        Placeholder.unparsed("old_name", target.getLastKnownName()),
                        Placeholder.unparsed("new_name", args[2]));
                case NAME_IN_USE -> messages.send(sender, "eco-rename-name-in-use",
                        Placeholder.unparsed("new_name", args[2]));
                case INVALID_NAME -> messages.send(sender, "eco-rename-invalid",
                        Placeholder.unparsed("new_name", args[2]));
                default -> messages.send(sender, "eco-rename-failed",
                        Placeholder.unparsed("player", target.getLastKnownName()));
            }
            return true;
        }

        // reset only needs <player>
        if (sub.equals("reset")) {
            if (!sender.hasPermission("openeco.command.eco.reset")) {
                messages.send(sender, "no-permission");
                return true;
            }
            if (args.length < 2 || args.length > 3) {
                sender.sendMessage("§cUsage: /eco reset <player> [currency]");
                return true;
            }
            var optTarget = service.findByName(args[1]);
            if (optTarget.isEmpty()) {
                messages.send(sender, "account-not-found", Placeholder.unparsed("player", args[1]));
                return true;
            }
            var target = optTarget.get();
            String currencyId = args.length == 3 ? args[2] : service.getCurrencyId();
            if (!service.hasCurrency(currencyId)) {
                messages.send(sender, "unknown-currency");
                return true;
            }
            var res = service.reset(target.getId(), currencyId);
            if (!res.transactionSuccess()) {
                sender.sendMessage("§cFailed: " + res.errorMessage);
                return true;
            }
            messages.send(sender, "eco-reset",
                    Placeholder.unparsed("player", target.getLastKnownName()),
                    Placeholder.unparsed("balance", service.format(res.balance, currencyId)));
            return true;
        }

        // give / take / set require <player> <amount>
        if (args.length < 3 || args.length > 4) {
            sender.sendMessage("§cUsage: /eco " + sub + " <player> <amount> [currency]");
            return true;
        }

        String perm = switch (sub) {
            case "give" -> "openeco.command.eco.give";
            case "take" -> "openeco.command.eco.take";
            case "set"  -> "openeco.command.eco.set";
            default     -> null;
        };
        if (perm == null) {
            sender.sendMessage("§cUnknown subcommand. Use: give, take, set, reset, delete, freeze, unfreeze, rename, reload.");
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
            if (amount.precision() > 30 || Math.abs(amount.scale()) > 18) {
                messages.send(sender, "invalid-amount");
                return true;
            }
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

        String currencyId = args.length == 4 ? args[3] : service.getCurrencyId();
        if (!service.hasCurrency(currencyId)) {
            messages.send(sender, "unknown-currency");
            return true;
        }

        switch (sub) {
            case "give" -> {
                EconomyResponse res = service.deposit(target.getId(), currencyId, amount);
                if (!res.transactionSuccess()) {
                    String limit = service.getFormattedMaxBalance(currencyId);
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
                        Placeholder.unparsed("amount", service.format(amount, currencyId)),
                        Placeholder.unparsed("player", target.getLastKnownName()),
                        Placeholder.unparsed("balance", service.format(res.balance, currencyId)));
            }
            case "take" -> {
                EconomyResponse res = service.withdraw(target.getId(), currencyId, amount);
                if (!res.transactionSuccess()) {
                    messages.send(sender, "eco-take-failed",
                            Placeholder.unparsed("player", target.getLastKnownName()));
                    return true;
                }
                messages.send(sender, "eco-take",
                        Placeholder.unparsed("amount", service.format(amount, currencyId)),
                        Placeholder.unparsed("player", target.getLastKnownName()),
                        Placeholder.unparsed("balance", service.format(res.balance, currencyId)));
            }
            case "set" -> {
                EconomyResponse res = service.set(target.getId(), currencyId, amount);
                if (!res.transactionSuccess()) {
                    String limit = service.getFormattedMaxBalance(currencyId);
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
                        Placeholder.unparsed("balance", service.format(res.balance, currencyId)));
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
            if (sub.equals("give") || sub.equals("take") || sub.equals("set") || sub.equals("reset") || sub.equals("delete") || sub.equals("freeze") || sub.equals("unfreeze") || sub.equals("rename")) {
                String prefix = args[1].toLowerCase();
                return service.getAccountNames().stream()
                        .filter(n -> n.toLowerCase().startsWith(prefix))
                        .sorted()
                        .toList();
            }
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("reset")) {
            String prefix = args[2].toLowerCase();
            return service.getCurrencyIds().stream()
                    .filter(id -> id.toLowerCase().startsWith(prefix))
                    .sorted()
                    .toList();
        }
        if (args.length == 4) {
            String sub = args[0].toLowerCase();
            if (sub.equals("give") || sub.equals("take") || sub.equals("set")) {
                String prefix = args[3].toLowerCase();
                return service.getCurrencyIds().stream()
                        .filter(id -> id.toLowerCase().startsWith(prefix))
                        .sorted()
                        .toList();
            }
        }
        return Collections.emptyList();
    }
}
