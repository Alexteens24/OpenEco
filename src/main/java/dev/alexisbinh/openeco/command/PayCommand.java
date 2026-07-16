/*
 * Copyright 2026 alexisbinh
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.alexisbinh.openeco.command;

import dev.alexisbinh.openeco.Messages;
import dev.alexisbinh.openeco.api.OpenEcoAsyncApi;
import dev.alexisbinh.openeco.api.TransferResult;
import dev.alexisbinh.openeco.service.AccountService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

public class PayCommand implements CommandExecutor, TabCompleter {

    private final AccountService service;
    private final Messages messages;
    private final JavaPlugin plugin;
    private final OpenEcoAsyncApi asyncApi;

    public PayCommand(JavaPlugin plugin, AccountService service, OpenEcoAsyncApi asyncApi, Messages messages) {
        this.plugin = plugin;
        this.service = service;
        this.asyncApi = java.util.Objects.requireNonNull(asyncApi, "asyncApi");
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

        if (CommandAccountResolver.deferColdLookup(plugin, service, messages, payer, args[0],
                () -> onCommand(sender, command, label, args.clone()))) return true;

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

        String resolvedCurrencyId = currencyId;
        asyncApi.transfer(payer.getUniqueId(), target.getId(), resolvedCurrencyId, amount)
                .whenComplete((result, error) -> payer.getScheduler().run(plugin, task -> {
                    if (error != null) {
                        messages.send(payer, "storage-error");
                        return;
                    }
                    handleAsyncResult(payer, target.getId(), target.getLastKnownName(),
                            resolvedCurrencyId, result);
                }, null));
        return true;
    }

    private void handleAsyncResult(Player payer, java.util.UUID targetId, String targetName,
                                   String currencyId, TransferResult result) {
        switch (result.status()) {
            case UNKNOWN_CURRENCY -> messages.send(payer, "unknown-currency");
            case INVALID_AMOUNT -> messages.send(payer, "invalid-amount");
            case SELF_TRANSFER -> messages.send(payer, "self-pay");
            case INSUFFICIENT_FUNDS -> messages.send(payer, "insufficient-funds");
            case TOO_LOW -> messages.send(payer, "pay-too-low",
                    Placeholder.unparsed("min", service.format(
                            service.getMinimumPayAmount(currencyId), currencyId)));
            case CANCELLED -> messages.send(payer, "pay-cancelled");
            case BALANCE_LIMIT -> messages.send(payer, "pay-balance-limit",
                    Placeholder.unparsed("player", targetName));
            case ACCOUNT_NOT_FOUND -> messages.send(payer, "account-not-found",
                    Placeholder.unparsed("player", targetName));
            case FROZEN -> messages.send(payer, "account-frozen");
            case STORAGE_ERROR -> messages.send(payer, "storage-error");
            case POLICY_REJECTED -> messages.send(payer, "pay-policy-rejected");
            case COOLDOWN -> messages.send(payer, "pay-cooldown",
                    Placeholder.unparsed("seconds", String.valueOf(
                            (result.cooldownRemainingMs() + 999) / 1000)));
            case SUCCESS -> {
                String payerName = payer.getName();
                messages.send(payer, "pay-sent",
                        Placeholder.unparsed("player", targetName),
                        Placeholder.unparsed("amount", service.format(result.sent(), currencyId)));
                if (result.tax().compareTo(BigDecimal.ZERO) > 0) {
                    messages.send(payer, "pay-tax",
                            Placeholder.unparsed("tax", service.format(result.tax(), currencyId)));
                }
                Player onlineTarget = payer.getServer().getPlayer(targetId);
                if (onlineTarget != null) {
                    onlineTarget.getScheduler().run(plugin, task -> messages.send(onlineTarget, "pay-received",
                            Placeholder.unparsed("amount", service.format(result.received(), currencyId)),
                            Placeholder.unparsed("player", payerName)), null);
                }
            }
        }
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
