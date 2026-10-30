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
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class EconomyMigrationBridgeImpl implements EconomyMigrationBridge {

    private static final MiniMessage MM = MiniMessage.miniMessage();

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
        sender.sendMessage(MM.deserialize("<gray>Economy plugins:"));
        for (EconomySourceReader reader : MigrationReaders.all()) {
            MigrationSource source = reader.source();
            boolean available = reader.isAvailable(context);
            sender.sendMessage(MM.deserialize((available ? "<green>" : "<gray>") + "• " + source.name().toLowerCase(Locale.ROOT)
                    + " <dark_gray>(" + source.displayName() + "<dark_gray>)"));
        }
        sender.sendMessage(MM.deserialize("<gray>Vault import needs the old economy plugin as the active provider."));
    }

    @Override
    public void scan(CommandSender sender, String sourceId) throws Exception {
        MigrationSource source = requireSource(sourceId);
        Optional<MigrationEngine.ScanResult> result = engine.scan(source);
        if (result.isEmpty()) {
            throw new IllegalStateException("Source not available: " + source.displayName());
        }
        MigrationEngine.ScanResult scan = result.get();
        sender.sendMessage(MM.deserialize("<gold>Scan <gray><source>",
                Placeholder.unparsed("source", scan.source().displayName())));
        sender.sendMessage(MM.deserialize("<gray>Location: <white><location>",
                Placeholder.unparsed("location", scan.location())));
        sender.sendMessage(MM.deserialize("<gray>Accounts: <white>" + scan.accounts()));
        sender.sendMessage(MM.deserialize("<gray>Total balance: <white><total>",
                Placeholder.unparsed("total", api.format(scan.totalBalance()))));
    }

    @Override
    public void migrate(CommandSender sender, String sourceId, boolean dryRun, boolean overwrite) throws Exception {
        MigrationSource source = requireSource(sourceId);
        sender.sendMessage(MM.deserialize("<gold>Migrating from <white><source>"
                        + (dryRun ? " <gray>(dry-run)" : "")
                        + (overwrite ? " <gray>(overwrite)" : "") + "<gold>...",
                Placeholder.unparsed("source", source.displayName())));

        MigrationReport report = engine.migrate(source, dryRun, overwrite);
        sender.sendMessage(MM.deserialize("<green>Migration " + (dryRun ? "preview" : "finished") + ":"));
        sender.sendMessage(MM.deserialize("<gray>Scanned: <white>" + report.scanned()
                + " <gray>| Created: <white>" + report.created()
                + " <gray>| Updated: <white>" + report.updated()
                + " <gray>| Skipped: <white>" + report.skipped()
                + " <gray>| Failed: <white>" + report.failed()));
        sender.sendMessage(MM.deserialize("<gray>Source total: <white><total>",
                Placeholder.unparsed("total", api.format(report.sourceTotal()))));
        for (String error : report.errors()) {
            sender.sendMessage(errorLine(error));
        }
        if (!dryRun && report.migratedOrWouldMigrate() > 0) {
            sender.sendMessage(MM.deserialize("<gray>Check <white>/balance <gray>for a few players to verify."));
        }
    }

    /**
     * Imported error text can contain legacy formatting codes or MiniMessage tags, so it is
     * inserted unparsed; concatenating it into the template would crash the parse or render
     * foreign data as components.
     */
    static Component errorLine(String error) {
        return MM.deserialize("<red>- <error>",
                Placeholder.unparsed("error", error == null ? "unknown error" : error));
    }

    private static MigrationSource requireSource(String sourceId) {
        return MigrationSource.parse(sourceId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown economy source: " + sourceId));
    }
}
