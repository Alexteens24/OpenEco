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
import dev.alexisbinh.openeco.api.EconomyMigrationBridge;
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
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public final class MigrateCommand implements CommandExecutor, TabCompleter {

    private static final String SQLITE_TO_MYSQL = "sqlitetomysql";
    private static final String MYSQL_TO_SQLITE = "mysqltosqlite";
    private static final List<String> STORAGE_SOURCES = List.of(SQLITE_TO_MYSQL, MYSQL_TO_SQLITE);
    private static final List<String> FLAGS = List.of("--scan", "--dry-run", "--overwrite");

    private final OpenEcoPlugin plugin;
    private final AccountService service;

    public MigrateCommand(OpenEcoPlugin plugin, AccountService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                               @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("openeco.migrator.admin")
                && !sender.hasPermission("openeco.command.storage")) {
            sender.sendMessage("§cYou do not have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            sendUsage(sender, label);
            return true;
        }

        String source = args[0].toLowerCase(Locale.ROOT);
        boolean scan = hasFlag(args, "--scan");
        boolean dryRun = hasFlag(args, "--dry-run");
        boolean overwrite = hasFlag(args, "--overwrite");

        if (unknownFlag(args)) {
            sender.sendMessage("§cUnknown flag. Use: --scan, --dry-run, --overwrite");
            return true;
        }

        if (STORAGE_SOURCES.contains(source)) {
            return handleStorage(sender, source, scan, dryRun, overwrite);
        }

        EconomyMigrationBridge bridge = economyBridge();
        if (bridge == null) {
            sender.sendMessage("§cEconomy plugin import requires the §fOpenEcoMigrator §caddon.");
            sender.sendMessage("§7Storage migration: §f/" + label + " sqlitetomysql §7or §f/" + label + " mysqltosqlite");
            return true;
        }

        try {
            if (scan) {
                bridge.scan(sender, source);
            } else {
                bridge.migrate(sender, source, dryRun, overwrite);
            }
        } catch (Exception e) {
            sender.sendMessage("§cMigration failed: " + e.getMessage());
            plugin.getLogger().warning("Economy migration failed: " + e.getMessage());
        }
        return true;
    }

    private boolean handleStorage(CommandSender sender, String source,
                                  boolean scan, boolean dryRun, boolean overwrite) {
        try {
            if (SQLITE_TO_MYSQL.equals(source)) {
                return handleSqliteToMysql(sender, scan, dryRun, overwrite);
            }
            return handleMysqlToSqlite(sender, scan, dryRun, overwrite);
        } catch (Exception e) {
            sender.sendMessage("§cMigration failed: " + e.getMessage());
            plugin.getLogger().severe("Storage migration failed: " + e.getMessage());
            return true;
        }
    }

    private boolean handleSqliteToMysql(CommandSender sender, boolean scan,
                                        boolean dryRun, boolean overwrite) throws Exception {
        DatabaseDialect active = activeDialect();
        if (!active.isLocal() && !usesBackupSource()) {
            sender.sendMessage("§cActive storage is already remote. Configure storage.migration.source-* for a local backup file.");
            return true;
        }
        if (active == DatabaseDialect.MYSQL && !usesBackupSource()) {
            sender.sendMessage("§cAlready using MySQL. Use §f/openecomigrate mysqltosqlite §cto export locally.");
            return true;
        }

        JdbcAccountRepository source = openSqliteSource();
        JdbcAccountRepository target = StorageMigrator.openRemoteTarget(
                DatabaseDialect.MYSQL, plugin.getConfig(), plugin.resolveDefaultCurrencyId());
        try {
            return runStorageMigration(sender, source, target, DatabaseDialect.MYSQL, scan, dryRun, overwrite);
        } finally {
            closeIfNotLive(source);
            target.close();
        }
    }

    private boolean handleMysqlToSqlite(CommandSender sender, boolean scan,
                                          boolean dryRun, boolean overwrite) throws Exception {
        DatabaseDialect active = activeDialect();
        if (active.isLocal() && !usesBackupSource()) {
            sender.sendMessage("§cActive storage is already local. Use §f/openecomigrate sqlitetomysql §cto upload to MySQL.");
            return true;
        }

        JdbcAccountRepository source = openMysqlSource();
        JdbcAccountRepository target = StorageMigrator.openLocalTarget(
                plugin.getConfig(), plugin.getDataFolder(), plugin.resolveDefaultCurrencyId());
        try {
            return runStorageMigration(sender, source, target, DatabaseDialect.SQLITE, scan, dryRun, overwrite);
        } finally {
            closeIfNotLive(source);
            if (target != plugin.getRepository()) {
                target.close();
            }
        }
    }

    private boolean runStorageMigration(CommandSender sender,
                                          JdbcAccountRepository source,
                                          JdbcAccountRepository target,
                                          DatabaseDialect targetDialect,
                                          boolean scan,
                                          boolean dryRun,
                                          boolean overwrite) throws Exception {
        StorageMigrationStats sourceStats = StorageMigrator.scan(source);
        if (scan) {
            sender.sendMessage("§6Source §7(" + source.dialect().name().toLowerCase(Locale.ROOT) + "§7): §f"
                    + sourceStats.accounts() + " §7account(s), §f"
                    + sourceStats.transactions() + " §7transaction(s)");
            StorageMigrationStats targetStats = StorageMigrator.scan(target);
            sender.sendMessage("§6Target §7(" + targetDialect.name().toLowerCase(Locale.ROOT) + "§7): §f"
                    + targetStats.accounts() + " §7account(s), §f"
                    + targetStats.transactions() + " §7transaction(s)");
            return true;
        }

        service.flushDirty();
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
            String hint = targetDialect == DatabaseDialect.MYSQL
                    ? "storage.type: mysql"
                    : "storage.type: sqlite";
            sender.sendMessage("§aMigration finished. Set §f" + hint + " §ain config.yml, then restart.");
        }
        return true;
    }

    private JdbcAccountRepository openSqliteSource() throws Exception {
        if (usesBackupSource()) {
            return StorageMigrator.openLocalSource(
                    plugin.getConfig(), plugin.getDataFolder(), plugin.resolveDefaultCurrencyId());
        }
        if (!(plugin.getRepository() instanceof JdbcAccountRepository jdbc)) {
            throw new IllegalStateException("Active repository is not JDBC-backed");
        }
        return jdbc;
    }

    private JdbcAccountRepository openMysqlSource() throws Exception {
        if (activeDialect() == DatabaseDialect.MYSQL
                && plugin.getRepository() instanceof JdbcAccountRepository jdbc) {
            return jdbc;
        }
        return StorageMigrator.openRemoteTarget(
                DatabaseDialect.MYSQL, plugin.getConfig(), plugin.resolveDefaultCurrencyId());
    }

    private DatabaseDialect activeDialect() {
        return DatabaseDialect.fromConfig(plugin.getConfig().getString("storage.type", "sqlite"));
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

    private static EconomyMigrationBridge economyBridge() {
        RegisteredServiceProvider<EconomyMigrationBridge> rsp =
                org.bukkit.Bukkit.getServicesManager().getRegistration(EconomyMigrationBridge.class);
        return rsp == null ? null : rsp.getProvider();
    }

    private static boolean hasFlag(String[] args, String flag) {
        return Arrays.stream(args).anyMatch(arg -> arg.equalsIgnoreCase(flag));
    }

    private static boolean unknownFlag(String[] args) {
        for (int i = 1; i < args.length; i++) {
            String arg = args[i].toLowerCase(Locale.ROOT);
            if (!FLAGS.contains(arg)) {
                return true;
            }
        }
        return false;
    }

    private void sendUsage(CommandSender sender, String label) {
        sender.sendMessage("§6/" + label + " <source> [flags]");
        sender.sendMessage("§7Flags: §f--scan §7preview, §f--dry-run §7preview import, §f--overwrite §7replace target data");
        sender.sendMessage("§7Storage: §fsqlitetomysql§7, §fmysqltosqlite");
        EconomyMigrationBridge bridge = economyBridge();
        if (bridge != null) {
            bridge.sendSourceList(sender);
        } else {
            sender.sendMessage("§7Economy sources: §8(install OpenEcoMigrator addon)");
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                  @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("openeco.migrator.admin")
                && !sender.hasPermission("openeco.command.storage")) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            List<String> options = new ArrayList<>(STORAGE_SOURCES);
            EconomyMigrationBridge bridge = economyBridge();
            if (bridge != null) {
                options.addAll(bridge.sourceIds());
            }
            return filterPrefix(options, args[0]);
        }
        if (args.length >= 2) {
            return filterPrefix(FLAGS, args[args.length - 1]);
        }
        return Collections.emptyList();
    }

    private static List<String> filterPrefix(List<String> options, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower))
                .collect(Collectors.toCollection(ArrayList::new));
    }
}
