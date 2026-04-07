package dev.alexisbinh.openeco.command;

import dev.alexisbinh.openeco.Messages;
import dev.alexisbinh.openeco.model.PayResult;
import dev.alexisbinh.openeco.service.AccountService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

public class PayCommand implements CommandExecutor, TabCompleter {

    private final AccountService service;
    private final Messages messages;

    public PayCommand(AccountService service, Messages messages) {
        this.service = service;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player payer)) {
            messages.send(sender, "console-player-only");
            return true;
        }
        if (!payer.hasPermission("openeco.command.pay")) {
            messages.send(payer, "no-permission");
            return true;
        }
        if (args.length < 2 || args.length > 3) {
            payer.sendMessage("§cUsage: /pay <player> <amount> [currency]");
            return true;
        }

        var optTarget = service.findByName(args[0]);
        if (optTarget.isEmpty()) {
            messages.send(payer, "account-not-found", Placeholder.unparsed("player", args[0]));
            return true;
        }
        var target = optTarget.get();

        if (target.getId().equals(payer.getUniqueId())) {
            messages.send(payer, "self-pay");
            return true;
        }

        BigDecimal amount;
        try {
            amount = new BigDecimal(args[1]);
            if (amount.precision() > 30 || Math.abs(amount.scale()) > 18) {
                messages.send(payer, "invalid-amount");
                return true;
            }
        } catch (NumberFormatException e) {
            messages.send(payer, "invalid-amount");
            return true;
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            messages.send(payer, "negative-amount");
            return true;
        }

        String currencyId = service.getCurrencyId();
        if (args.length == 3) {
            currencyId = args[2];
            if (!service.hasCurrency(currencyId)) {
                messages.send(payer, "unknown-currency");
                return true;
            }
        }

        PayResult result = service.pay(payer.getUniqueId(), target.getId(), currencyId, amount);
        switch (result.getStatus()) {
            case UNKNOWN_CURRENCY    -> messages.send(payer, "unknown-currency");
            case INVALID_AMOUNT      -> messages.send(payer, "invalid-amount");
            case SELF_TRANSFER      -> messages.send(payer, "self-pay");
            case INSUFFICIENT_FUNDS -> messages.send(payer, "insufficient-funds");
            case TOO_LOW            -> messages.send(payer, "pay-too-low",
                Placeholder.unparsed("min", service.format(result.getMinimumAmount(), currencyId)));
            case CANCELLED          -> messages.send(payer, "pay-cancelled");
            case BALANCE_LIMIT      -> messages.send(payer, "pay-balance-limit",
                    Placeholder.unparsed("player", target.getLastKnownName()));
            case ACCOUNT_NOT_FOUND  -> messages.send(payer, "account-not-found",
                    Placeholder.unparsed("player", target.getLastKnownName()));
            case FROZEN             -> messages.send(payer, "account-frozen");
            case COOLDOWN -> {
                long secs = (result.getCooldownRemainingMs() + 999) / 1000;
                messages.send(payer, "pay-cooldown",
                        Placeholder.unparsed("seconds", String.valueOf(secs)));
            }
            case SUCCESS -> {
                messages.send(payer, "pay-sent",
                        Placeholder.unparsed("player", target.getLastKnownName()),
                        Placeholder.unparsed("amount", service.format(result.getSent(), currencyId)));
                if (result.getTax().compareTo(BigDecimal.ZERO) > 0) {
                    messages.send(payer, "pay-tax",
                            Placeholder.unparsed("tax", service.format(result.getTax(), currencyId)));
                }
                Player onlineTarget = payer.getServer().getPlayer(target.getId());
                if (onlineTarget != null) {
                    messages.send(onlineTarget, "pay-received",
                            Placeholder.unparsed("amount", service.format(result.getReceived(), currencyId)),
                            Placeholder.unparsed("player", payer.getName()));
                }
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1 && sender.hasPermission("openeco.command.pay")) {
            String prefix = args[0].toLowerCase();
            return service.getAccountNames().stream()
                    .filter(n -> n.toLowerCase().startsWith(prefix))
                    .sorted()
                    .toList();
        }
        if (args.length == 3 && sender.hasPermission("openeco.command.pay")) {
            String prefix = args[2].toLowerCase();
            return service.getCurrencyIds().stream()
                    .filter(id -> id.toLowerCase().startsWith(prefix))
                    .sorted()
                    .toList();
        }
        return Collections.emptyList();
    }
}
