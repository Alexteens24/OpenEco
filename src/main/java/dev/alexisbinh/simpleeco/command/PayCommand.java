package dev.alexisbinh.simpleeco.command;

import dev.alexisbinh.simpleeco.Messages;
import dev.alexisbinh.simpleeco.model.PayResult;
import dev.alexisbinh.simpleeco.service.AccountService;
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
        if (!payer.hasPermission("simpleeco.command.pay")) {
            messages.send(payer, "no-permission");
            return true;
        }
        if (args.length < 2) {
            payer.sendMessage("§cUsage: /pay <player> <amount>");
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
        } catch (NumberFormatException e) {
            messages.send(payer, "invalid-amount");
            return true;
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            messages.send(payer, "negative-amount");
            return true;
        }

        PayResult result = service.pay(payer.getUniqueId(), target.getId(), amount);
        switch (result.getStatus()) {
            case INVALID_AMOUNT      -> messages.send(payer, "invalid-amount");
            case SELF_TRANSFER      -> messages.send(payer, "self-pay");
            case INSUFFICIENT_FUNDS -> messages.send(payer, "insufficient-funds");
            case TOO_LOW            -> messages.send(payer, "pay-too-low",
                    Placeholder.unparsed("min", service.format(result.getSent())));
            case CANCELLED          -> messages.send(payer, "pay-cancelled");
            case BALANCE_LIMIT      -> messages.send(payer, "pay-balance-limit",
                    Placeholder.unparsed("player", target.getLastKnownName()));
            case ACCOUNT_NOT_FOUND  -> messages.send(payer, "account-not-found",
                    Placeholder.unparsed("player", target.getLastKnownName()));
            case COOLDOWN -> {
                long secs = (result.getCooldownRemainingMs() + 999) / 1000;
                messages.send(payer, "pay-cooldown",
                        Placeholder.unparsed("seconds", String.valueOf(secs)));
            }
            case SUCCESS -> {
                messages.send(payer, "pay-sent",
                        Placeholder.unparsed("player", target.getLastKnownName()),
                        Placeholder.unparsed("amount", service.format(result.getSent())));
                if (result.getTax().compareTo(BigDecimal.ZERO) > 0) {
                    messages.send(payer, "pay-tax",
                            Placeholder.unparsed("tax", service.format(result.getTax())));
                }
                Player onlineTarget = payer.getServer().getPlayer(target.getId());
                if (onlineTarget != null) {
                    messages.send(onlineTarget, "pay-received",
                            Placeholder.unparsed("amount", service.format(result.getReceived())),
                            Placeholder.unparsed("player", payer.getName()));
                }
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1 && sender.hasPermission("simpleeco.command.pay")) {
            String prefix = args[0].toLowerCase();
            return service.getUUIDNameMap().values().stream()
                    .filter(n -> n.toLowerCase().startsWith(prefix))
                    .sorted()
                    .toList();
        }
        return Collections.emptyList();
    }
}
