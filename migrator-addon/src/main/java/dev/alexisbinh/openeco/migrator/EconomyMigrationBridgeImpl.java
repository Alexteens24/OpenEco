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

import dev.alexisbinh.openeco.api.EconomyMigrationBridge;
import dev.alexisbinh.openeco.api.OpenEcoApi;
import dev.alexisbinh.openeco.migrator.model.MigrationReport;
import dev.alexisbinh.openeco.migrator.model.MigrationSource;
import dev.alexisbinh.openeco.migrator.source.EconomySourceReader;
import dev.alexisbinh.openeco.migrator.source.MigrationContext;
import dev.alexisbinh.openeco.migrator.source.MigrationReaders;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class EconomyMigrationBridgeImpl implements EconomyMigrationBridge {

    private final OpenEcoApi api;
    private final MigrationEngine engine;
    private final MigrationContext context;

    public EconomyMigrationBridgeImpl(OpenEcoApi api, MigrationEngine engine, MigrationContext context) {
        this.api = api;
        this.engine = engine;
        this.context = context;
    }

    @Override
    public List<String> sourceIds() {
        List<String> ids = new ArrayList<>();
        for (MigrationSource source : MigrationSource.values()) {
            ids.add(source.name().toLowerCase(Locale.ROOT));
        }
        return ids;
    }

    @Override
    public void sendSourceList(CommandSender sender) {
        sender.sendMessage("§7Economy plugins:");
        for (EconomySourceReader reader : MigrationReaders.all()) {
            MigrationSource source = reader.source();
            boolean available = reader.isAvailable(context);
            sender.sendMessage((available ? "§a" : "§7") + "• " + source.name().toLowerCase(Locale.ROOT)
                    + " §8(" + source.displayName() + "§8)");
        }
        sender.sendMessage("§7Vault import needs the old economy plugin as the active provider.");
    }

    @Override
    public void scan(CommandSender sender, String sourceId) throws Exception {
        MigrationSource source = requireSource(sourceId);
        Optional<MigrationEngine.ScanResult> result = engine.scan(source);
        if (result.isEmpty()) {
            throw new IllegalStateException("Source not available: " + source.displayName());
        }
        MigrationEngine.ScanResult scan = result.get();
        sender.sendMessage("§6Scan §7" + scan.source().displayName());
        sender.sendMessage("§7Location: §f" + scan.location());
        sender.sendMessage("§7Accounts: §f" + scan.accounts());
        sender.sendMessage("§7Total balance: §f" + api.format(scan.totalBalance()));
    }

    @Override
    public void migrate(CommandSender sender, String sourceId, boolean dryRun, boolean overwrite) throws Exception {
        MigrationSource source = requireSource(sourceId);
        sender.sendMessage("§6Migrating from §f" + source.displayName()
                + (dryRun ? " §7(dry-run)" : "")
                + (overwrite ? " §7(overwrite)" : "") + "§6...");

        MigrationReport report = engine.migrate(source, dryRun, overwrite);
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
            sender.sendMessage("§7Check §f/balance §7for a few players to verify.");
        }
    }

    private static MigrationSource requireSource(String sourceId) {
        return MigrationSource.parse(sourceId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown economy source: " + sourceId));
    }
}
