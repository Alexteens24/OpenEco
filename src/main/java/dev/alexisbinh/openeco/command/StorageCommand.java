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

import dev.alexisbinh.openeco.OpenEcoPlugin;
import dev.alexisbinh.openeco.service.AccountService;
import dev.alexisbinh.openeco.storage.DatabaseDialect;
import dev.alexisbinh.openeco.storage.JdbcAccountRepository;
import dev.alexisbinh.openeco.storage.StorageMigrationReport;
import dev.alexisbinh.openeco.storage.StorageMigrationStats;
import dev.alexisbinh.openeco.storage.StorageMigrator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class StorageCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("list", "scan", "migrate");

    private final OpenEcoPlugin plugin;
    private final AccountService service;

    public StorageCommand(OpenEcoPlugin plugin, AccountService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                               @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("openeco.command.storage")) {
            sender.sendMessage("§cYou do not have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            sendUsage(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "list" -> handleList(sender);
            case "scan" -> handleScan(sender, args);
            case "migrate" -> handleMigrate(sender, args);
            default -> {
                sendUsage(sender, label);
                yield true;
            }
        };
    }

    private boolean handleList(CommandSender sender) {
        sender.sendMessage("§6OpenEco storage migration");
        sender.sendMessage("§7Source: §fsqlite §7or §fh2 §7(local file)");
        sender.sendMessage("§7Targets: §f" + String.join("§7, §f", StorageMigrator.formatTargetChoices()));
        sender.sendMessage("§7Configure target connection under §fstorage.mysql§7, §fstorage.mariadb§7, or §fstorage.postgresql§7.");
        return true;
    }

    private boolean handleScan(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /openecostorage scan <mysql|mariadb|postgresql>");
            return true;
        }

        try {
            DatabaseDialect targetDialect = StorageMigrator.parseTargetDialect(args[1]);
            JdbcAccountRepository source = openSourceRepository();
            try {
                StorageMigrationStats sourceStats = StorageMigrator.scan(source);
                sender.sendMessage("§aSource §7(" + source.dialect().name().toLowerCase(Locale.ROOT) + "§7): §f"
                        + sourceStats.accounts() + " §7account(s), §f"
                        + sourceStats.transactions() + " §7transaction(s)");

                JdbcAccountRepository target = openTargetRepository(targetDialect);
                try {
                    StorageMigrationStats targetStats = StorageMigrator.scan(target);
                    sender.sendMessage("§aTarget §7(" + targetDialect.name().toLowerCase(Locale.ROOT) + "§7): §f"
                            + targetStats.accounts() + " §7account(s), §f"
                            + targetStats.transactions() + " §7transaction(s)");
                } finally {
                    target.close();
                }
            } finally {
                closeIfNotLive(source);
            }
        } catch (Exception e) {
            sender.sendMessage("§cScan failed: " + e.getMessage());
        }
        return true;
    }

    private boolean handleMigrate(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /openecostorage migrate <mysql|mariadb|postgresql> [--dry-run] [--overwrite]");
            return true;
        }

        boolean dryRun = false;
        boolean overwrite = false;
        for (int i = 2; i < args.length; i++) {
            String flag = args[i].toLowerCase(Locale.ROOT);
            if (flag.equals("--dry-run")) {
                dryRun = true;
            } else if (flag.equals("--overwrite")) {
                overwrite = true;
            } else {
                sender.sendMessage("§cUnknown flag: " + args[i]);
                return true;
            }
        }

        try {
            DatabaseDialect targetDialect = StorageMigrator.parseTargetDialect(args[1]);
            DatabaseDialect activeDialect = DatabaseDialect.fromConfig(plugin.getConfig().getString("storage.type", "sqlite"));
            if (!activeDialect.isLocal() && !usesBackupSource()) {
                sender.sendMessage("§cActive storage is already remote. Configure storage.migration.source-* to import a local backup file.");
                return true;
            }
            if (activeDialect == targetDialect && !usesBackupSource()) {
                sender.sendMessage("§cActive storage type already matches the migration target.");
                return true;
            }

            service.flushDirty();
            JdbcAccountRepository source = openSourceRepository();
            JdbcAccountRepository target = openTargetRepository(targetDialect);
            try {
                StorageMigrationReport report = StorageMigrator.migrate(source, target, targetDialect, dryRun, overwrite);
                String mode = dryRun ? "§e(dry-run)" : "§a(completed)";
                sender.sendMessage("§6Storage migration " + mode);
                sender.sendMessage("§7Accounts: §f" + report.accountsCopied());
                sender.sendMessage("§7Transactions: §f" + report.transactionsCopied());
                if (!report.errors().isEmpty()) {
                    sender.sendMessage("§cErrors:");
                    for (String error : report.errors()) {
                        sender.sendMessage("§c- " + error);
                    }
                    return true;
                }
                if (!dryRun) {
                    sender.sendMessage("§aMigration finished. Set §fstorage.type: "
                            + targetDialect.name().toLowerCase(Locale.ROOT)
                            + " §ain config.yml, then restart the server.");
                }
            } finally {
                closeIfNotLive(source);
                target.close();
            }
        } catch (Exception e) {
            sender.sendMessage("§cMigration failed: " + e.getMessage());
            plugin.getLogger().severe("Storage migration failed: " + e.getMessage());
        }
        return true;
    }

    private JdbcAccountRepository openSourceRepository() throws Exception {
        if (usesBackupSource()) {
            return StorageMigrator.openLocalSource(
                    plugin.getConfig(),
                    plugin.getDataFolder(),
                    plugin.resolveDefaultCurrencyId());
        }
        if (!(plugin.getRepository() instanceof JdbcAccountRepository jdbc)) {
            throw new IllegalStateException("Active repository is not JDBC-backed");
        }
        return jdbc;
    }

    private JdbcAccountRepository openTargetRepository(DatabaseDialect targetDialect) throws Exception {
        return StorageMigrator.openRemoteTarget(
                targetDialect,
                plugin.getConfig(),
                plugin.resolveDefaultCurrencyId());
    }

    private boolean usesBackupSource() {
        String folder = plugin.getConfig().getString("storage.migration.source-folder", "").trim();
        String file = plugin.getConfig().getString("storage.migration.source-file", "").trim();
        return !folder.isEmpty() || !file.isEmpty();
    }

    private void closeIfNotLive(JdbcAccountRepository source) throws Exception {
        if (source != plugin.getRepository()) {
            source.close();
        }
    }

    private static void sendUsage(CommandSender sender, String label) {
        sender.sendMessage("§cUsage: /" + label + " <list|scan|migrate> [target] [--dry-run] [--overwrite]");
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("openeco.command.storage")) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return filterPrefix(SUBCOMMANDS, args[0]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("scan") || args[0].equalsIgnoreCase("migrate"))) {
            return filterPrefix(StorageMigrator.formatTargetChoices(), args[1]);
        }
        if (args.length >= 3 && args[0].equalsIgnoreCase("migrate")) {
            List<String> flags = Arrays.asList("--dry-run", "--overwrite");
            return filterPrefix(flags, args[args.length - 1]);
        }
        return Collections.emptyList();
    }

    private static List<String> filterPrefix(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(option -> option.startsWith(lower))
                .collect(Collectors.toCollection(ArrayList::new));
    }
}
