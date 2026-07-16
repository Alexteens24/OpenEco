/*
 * Copyright 2026 alexisbinh
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package dev.alexisbinh.openeco.command;

import dev.alexisbinh.openeco.Messages;
import dev.alexisbinh.openeco.service.AccountService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

final class CommandAccountResolver {

    private CommandAccountResolver() {
    }

    static boolean deferColdLookup(JavaPlugin plugin, AccountService service, Messages messages,
                                   CommandSender sender, String accountName, Runnable retry) {
        return deferColdLookup(plugin, service, messages, sender, accountName,
                () -> messages.send(sender, "account-not-found", Placeholder.unparsed("player", accountName)), retry);
    }

    static boolean deferColdLookup(JavaPlugin plugin, AccountService service, Messages messages,
                                   CommandSender sender, String accountName, Runnable missing, Runnable retry) {
        if (plugin == null || !service.isLazyAccountModeEnabled() || service.isAccountNameCached(accountName)) {
            return false;
        }
        service.findByNameAsync(accountName).whenComplete((account, error) -> dispatch(plugin, sender, () -> {
            if (error != null) {
                messages.send(sender, "storage-error");
            } else if (account.isEmpty()) {
                missing.run();
            } else {
                AccountService.AccountPin pin = service.pinAccount(account.get().getId()).orElse(null);
                if (pin == null) {
                    messages.send(sender, "storage-error");
                    return;
                }
                try (pin) {
                    retry.run();
                }
            }
        }));
        return true;
    }

    private static void dispatch(JavaPlugin plugin, CommandSender sender, Runnable action) {
        if (sender instanceof Player player) {
            player.getScheduler().run(plugin, ignored -> action.run(), () -> { });
        } else {
            plugin.getServer().getGlobalRegionScheduler().run(plugin, ignored -> action.run());
        }
    }
}
