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
import dev.alexisbinh.openeco.OpenEcoPlugin;
import dev.alexisbinh.openeco.service.AccountService;
import dev.alexisbinh.openeco.storage.DatabaseDialect;
import dev.alexisbinh.openeco.storage.JdbcAccountRepository;
import dev.alexisbinh.openeco.storage.StorageMigrationReport;
import dev.alexisbinh.openeco.storage.StorageMigrationStats;
import dev.alexisbinh.openeco.storage.StorageMigrator;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
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

public final class MigrateCommand implements CommandExecutor, TabCompleter {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String SQLITE_TO_MYSQL = "sqlitetomysql";
    /**
     * Dynamic text (exception messages, migration errors, command labels) is inserted with
     * {@link Placeholder#unparsed}: concatenating it into the template would let legacy
     * formatting codes crash the parse and tags in imported data render as components.
     */
    private static final String UNKNOWN_ERROR = "unknown error";
    private static final String MYSQL_TO_SQLITE = "mysqltosqlite";
    private static final List<String> STORAGE_SOURCES = List.of(SQLITE_TO_MYSQL, MYSQL_TO_SQLITE);
    private static final List<String> FLAGS = List.of("--scan", "--dry-run", "--overwrite");

    private final OpenEcoPlugin plugin;
    private final AccountService service;
    private final Messages messages;

    public MigrateCommand(OpenEcoPlugin plugin, AccountService service) {
        this(plugin, service, plugin != null ? plugin.getMessages() : null);
    }

    public MigrateCommand(OpenEcoPlugin plugin, AccountService service, Messages messages) {
        this.plugin = plugin;
        this.service = service;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                               @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("openeco.migrator.admin")
                && !sender.hasPermission("openeco.command.storage")) {
            if (messages != null) {
                messages.send(sender, "no-permission");
            } else {
                sender.sendMessage(MM.deserialize("<red>You don't have permission to do that."));
            }
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
            sender.sendMessage(MM.deserialize("<red>Unknown flag. Use: --scan, --dry-run, --overwrite"));
            return true;
        }

        if (STORAGE_SOURCES.contains(source)) {
            return handleStorage(sender, source, scan, dryRun, overwrite);
        }

        if (!EconomyMigrationInvoker.isAvailable()) {
            sender.sendMessage(MM.deserialize("<red>Economy plugin import requires the <white>OpenEcoMigrator <red>addon."));
            sender.sendMessage(MM.deserialize(
                    "<gray>Storage migration: <white>/<label> sqlitetomysql <gray>or <white>/<label> mysqltosqlite",
                    Placeholder.unparsed("label", label)));
            return true;
        }

        try {
            if (scan) {
                EconomyMigrationInvoker.scan(sender, source);
            } else {
                EconomyMigrationInvoker.migrate(sender, source, dryRun, overwrite);
            }
        } catch (ReflectiveOperationException e) {
            sender.sendMessage(migrationFailed(rootMessage(e)));
            plugin.getLogger().warning("Economy migration failed: " + rootMessage(e));
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
            sender.sendMessage(migrationFailed(e.getMessage()));
            plugin.getLogger().severe("Storage migration failed: " + e.getMessage());
            return true;
        }
    }

    private boolean handleSqliteToMysql(CommandSender sender, boolean scan,
                                        boolean dryRun, boolean overwrite) throws Exception {
        DatabaseDialect active = activeDialect();
        if (!active.isLocal() && !usesBackupSource()) {
            sender.sendMessage(MM.deserialize(
                    "<red>Active storage is already remote. Configure storage.migration.source-* for a local backup file."));
            return true;
        }
        if (active == DatabaseDialect.MYSQL && !usesBackupSource()) {
            sender.sendMessage(MM.deserialize(
                    "<red>Already using MySQL. Use <white>/openecomigrate mysqltosqlite <red>to export locally."));
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
            sender.sendMessage(MM.deserialize(
                    "<red>Active storage is already local. Use <white>/openecomigrate sqlitetomysql <red>to upload to MySQL."));
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
            sender.sendMessage(MM.deserialize("<gold>Source <gray>(" + source.dialect().name().toLowerCase(Locale.ROOT) + "<gray>): <white>"
                    + sourceStats.accounts() + " <gray>account(s), <white>"
                    + sourceStats.transactions() + " <gray>transaction(s)"));
            StorageMigrationStats targetStats = StorageMigrator.scan(target);
            sender.sendMessage(MM.deserialize("<gold>Target <gray>(" + targetDialect.name().toLowerCase(Locale.ROOT) + "<gray>): <white>"
                    + targetStats.accounts() + " <gray>account(s), <white>"
                    + targetStats.transactions() + " <gray>transaction(s)"));
            return true;
        }

        service.flushDirty();
        StorageMigrationReport report = StorageMigrator.migrate(source, target, targetDialect, dryRun, overwrite);
        String mode = dryRun ? "<yellow>(dry-run)" : "<green>(completed)";
        sender.sendMessage(MM.deserialize("<gold>Storage migration " + mode));
        sender.sendMessage(MM.deserialize("<gray>Accounts: <white>" + report.accountsCopied()));
        sender.sendMessage(MM.deserialize("<gray>Transactions: <white>" + report.transactionsCopied()));
        if (!report.errors().isEmpty()) {
            sender.sendMessage(MM.deserialize("<red>Errors:"));
            for (String error : report.errors()) {
                sender.sendMessage(migrationErrorLine(error));
            }
            return true;
        }
        if (!dryRun) {
            String hint = targetDialect == DatabaseDialect.MYSQL
                    ? "storage.type: mysql"
                    : "storage.type: sqlite";
            sender.sendMessage(MM.deserialize("<green>Migration finished. Set <white>" + hint + " <green>in config.yml, then restart."));
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

    private static String rootMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    static Component migrationFailed(String reason) {
        return MM.deserialize("<red>Migration failed: <gray><reason>",
                Placeholder.unparsed("reason", reason == null ? UNKNOWN_ERROR : reason));
    }

    static Component migrationErrorLine(String error) {
        return MM.deserialize("<red>- <error>",
                Placeholder.unparsed("error", error == null ? UNKNOWN_ERROR : error));
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
        sender.sendMessage(MM.deserialize("<gold>/<label> <source> [flags]",
                Placeholder.unparsed("label", label)));
        sender.sendMessage(MM.deserialize("<gray>Flags: <white>--scan <gray>preview, <white>--dry-run <gray>preview import, <white>--overwrite <gray>replace target data"));
        sender.sendMessage(MM.deserialize("<gray>Storage: <white>sqlitetomysql<gray>, <white>mysqltosqlite"));
        if (EconomyMigrationInvoker.isAvailable()) {
            try {
                EconomyMigrationInvoker.sendSourceList(sender);
            } catch (ReflectiveOperationException ignored) {
                sender.sendMessage(MM.deserialize("<gray>Economy sources: <dark_gray>(OpenEcoMigrator failed to list sources)"));
            }
        } else {
            sender.sendMessage(MM.deserialize("<gray>Economy sources: <dark_gray>(install OpenEcoMigrator addon)"));
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
            if (EconomyMigrationInvoker.isAvailable()) {
                options.addAll(EconomyMigrationInvoker.sourceIds());
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
