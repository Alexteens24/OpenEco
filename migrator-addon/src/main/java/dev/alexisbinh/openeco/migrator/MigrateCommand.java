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

package dev.alexisbinh.openeco.migrator;

import dev.alexisbinh.openeco.api.OpenEcoApi;
import dev.alexisbinh.openeco.migrator.model.MigrationReport;
import dev.alexisbinh.openeco.migrator.model.MigrationSource;
import dev.alexisbinh.openeco.migrator.source.EconomySourceReader;
import dev.alexisbinh.openeco.migrator.source.MigrationContext;
import dev.alexisbinh.openeco.migrator.source.MigrationReaders;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class MigrateCommand implements CommandExecutor, TabCompleter {

    private final JavaPlugin plugin;
    private final OpenEcoApi api;
    private final MigrationEngine engine;

    public MigrateCommand(JavaPlugin plugin, OpenEcoApi api, MigrationEngine engine) {
        this.plugin = plugin;
        this.api = api;
        this.engine = engine;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("openeco.migrator.admin")) {
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
            case "run" -> handleRun(sender, args);
            default -> {
                sendUsage(sender, label);
                yield true;
            }
        };
    }

    private boolean handleList(CommandSender sender) {
        sender.sendMessage("§6OpenEcoMigrator §7— supported sources:");
        for (EconomySourceReader reader : MigrationReaders.all()) {
            MigrationSource source = reader.source();
            boolean available = reader.isAvailable(engineContext());
            sender.sendMessage((available ? "§a" : "§7") + "• " + source.name().toLowerCase(Locale.ROOT)
                    + " §8(" + source.displayName() + "§8) §7— " + source.description());
            if (available) {
                sender.sendMessage("  §8↳ " + reader.describeLocation(engineContext()));
            }
        }
        sender.sendMessage("§7Tip: file/database sources work while OpenEco is active.");
        sender.sendMessage("§7Vault import needs the old economy plugin to still be the hooked provider.");
        return true;
    }

    private boolean handleScan(CommandSender sender, String[] args) {
        Optional<MigrationSource> sourceOpt = parseSource(args, 1);
        if (sourceOpt.isEmpty()) {
            sender.sendMessage("§cUsage: /openemomigrate scan <source>");
            return true;
        }
        try {
            Optional<MigrationEngine.ScanResult> result = engine.scan(sourceOpt.get());
            if (result.isEmpty()) {
                sender.sendMessage("§cSource not available: " + sourceOpt.get().displayName());
                return true;
            }
            MigrationEngine.ScanResult scan = result.get();
            sender.sendMessage("§6Scan §7" + scan.source().displayName());
            sender.sendMessage("§7Location: §f" + scan.location());
            sender.sendMessage("§7Accounts: §f" + scan.accounts());
            sender.sendMessage("§7Total balance: §f" + api.format(scan.totalBalance()));
            return true;
        } catch (Exception e) {
            sender.sendMessage("§cScan failed: " + e.getMessage());
            plugin.getLogger().warning("Migration scan failed: " + e.getMessage());
            return true;
        }
    }

    private boolean handleRun(CommandSender sender, String[] args) {
        Optional<MigrationSource> sourceOpt = parseSource(args, 1);
        if (sourceOpt.isEmpty()) {
            sender.sendMessage("§cUsage: /openemomigrate run <source> [--dry-run] [--overwrite]");
            return true;
        }

        boolean dryRun = Arrays.stream(args).anyMatch(arg -> arg.equalsIgnoreCase("--dry-run"));
        boolean overwrite = Arrays.stream(args).anyMatch(arg -> arg.equalsIgnoreCase("--overwrite"));

        sender.sendMessage("§6Starting migration from §f" + sourceOpt.get().displayName()
                + (dryRun ? " §7(dry-run)" : "")
                + (overwrite ? " §7(overwrite)" : "") + "§6...");

        try {
            MigrationReport report = engine.migrate(sourceOpt.get(), dryRun, overwrite);
            sender.sendMessage("§aMigration " + (dryRun ? "preview" : "finished") + ":");
            sender.sendMessage("§7Scanned: §f" + report.scanned()
                    + " §7| Created: §f" + report.created()
                    + " §7| Updated: §f" + report.updated()
                    + " §7| Skipped: §f" + report.skipped()
                    + " §7| Failed: §f" + report.failed());
            sender.sendMessage("§7Source total: §f" + api.format(report.sourceTotal()));
            for (String error : report.errors()) {
                sender.sendMessage("§c- " + error);
            }
            if (!dryRun && report.migratedOrWouldMigrate() > 0) {
                sender.sendMessage("§7Run §f/openemomigrate scan vault §7or check balances to verify.");
            }
            return true;
        } catch (Exception e) {
            sender.sendMessage("§cMigration failed: " + e.getMessage());
            plugin.getLogger().warning("Migration run failed: " + e.getMessage());
            return true;
        }
    }

    private MigrationContext engineContext() {
        return new MigrationContext(
                plugin,
                plugin.getDataFolder().getParentFile().toPath(),
                MigrationSupport.pathOverrides(plugin));
    }

    private static Optional<MigrationSource> parseSource(String[] args, int index) {
        if (args.length <= index) {
            return Optional.empty();
        }
        return MigrationSource.parse(args[index]);
    }

    private static void sendUsage(CommandSender sender, String label) {
        sender.sendMessage("§6/" + label + " list §7— list supported economy sources");
        sender.sendMessage("§6/" + label + " scan <source> §7— preview account count and total");
        sender.sendMessage("§6/" + label + " run <source> [--dry-run] [--overwrite] §7— import balances");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("openeco.migrator.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            return filterPrefix(List.of("list", "scan", "run"), args[0]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("scan") || args[0].equalsIgnoreCase("run"))) {
            List<String> sources = new ArrayList<>();
            for (MigrationSource source : MigrationSource.values()) {
                sources.add(source.name().toLowerCase(Locale.ROOT));
            }
            return filterPrefix(sources, args[1]);
        }
        if (args.length >= 3 && args[0].equalsIgnoreCase("run")) {
            return filterPrefix(List.of("--dry-run", "--overwrite"), args[args.length - 1]);
        }
        return List.of();
    }

    private static List<String> filterPrefix(List<String> options, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower))
                .toList();
    }
}
