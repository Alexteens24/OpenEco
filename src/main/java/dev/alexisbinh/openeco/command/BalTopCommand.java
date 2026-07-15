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
import dev.alexisbinh.openeco.service.AccountService;
import dev.alexisbinh.openeco.service.LeaderboardEntry;
import dev.alexisbinh.openeco.service.LeaderboardView;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;

public class BalTopCommand implements CommandExecutor, TabCompleter {

    private final AccountService service;
    private final Messages messages;
    private final JavaPlugin plugin;

    public BalTopCommand(AccountService service, Messages messages) {
        this(null, service, messages);
    }

    public BalTopCommand(JavaPlugin plugin, AccountService service, Messages messages) {
        this.plugin = plugin;
        this.service = service;
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

        int pageSize = service.getBalTopPageSize();
        if (pageSize <= 0) pageSize = 10;

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

        final int requestedPage = page;
        final int resolvedPageSize = pageSize;
        final String resolvedCurrencyId = currencyId;
        if (plugin != null && service.isLazyAccountCacheEnabled()) {
            plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
                try {
                    LeaderboardResult result = loadLeaderboard(resolvedCurrencyId, resolvedPageSize, requestedPage);
                    dispatchReply(sender, () -> sendLeaderboard(sender, resolvedCurrencyId, result));
                } catch (RuntimeException e) {
                    plugin.getLogger().warning("Failed to load leaderboard: " + e.getMessage());
                    dispatchReply(sender, () -> messages.send(sender, "storage-error"));
                }
            });
            return true;
        }

        sendLeaderboard(sender, resolvedCurrencyId,
                loadLeaderboard(resolvedCurrencyId, resolvedPageSize, requestedPage));
        return true;
    }

    private LeaderboardResult loadLeaderboard(String currencyId, int pageSize, int requestedPage) {
        LeaderboardView summary = service.getLeaderboardPage(currencyId, 0, 0);
        int totalPages = (int) Math.ceil((double) summary.totalEntries() / pageSize);
        if (totalPages == 0) totalPages = 1;
        int page = Math.min(requestedPage, totalPages);

        int start = (page - 1) * pageSize;
        LeaderboardView view = service.getLeaderboardPage(currencyId, start, pageSize);
        return new LeaderboardResult(page, totalPages, start, view);
    }

    private void sendLeaderboard(CommandSender sender, String currencyId, LeaderboardResult result) {
        messages.send(sender, "baltop-header",
                Placeholder.unparsed("page", String.valueOf(result.page())),
                Placeholder.unparsed("total", String.valueOf(result.totalPages())));
        for (int i = 0; i < result.view().entries().size(); i++) {
            LeaderboardEntry entry = result.view().entries().get(i);
            messages.send(sender, "baltop-entry",
                    Placeholder.unparsed("rank", String.valueOf(result.start() + i + 1)),
                    Placeholder.unparsed("player", entry.name()),
                    Placeholder.unparsed("balance", service.format(entry.balance(), currencyId)));
        }
    }

    private void dispatchReply(CommandSender sender, Runnable reply) {
        if (sender instanceof Player player) {
            player.getScheduler().run(plugin, task -> reply.run(), () -> { });
            return;
        }
        plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> reply.run());
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

    private record LeaderboardResult(int page, int totalPages, int start, LeaderboardView view) {
    }
}
