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
import dev.alexisbinh.openeco.migrator.source.MigrationContext;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public final class OpenEcoMigratorPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();

        RegisteredServiceProvider<OpenEcoApi> rsp =
                getServer().getServicesManager().getRegistration(OpenEcoApi.class);
        if (rsp == null) {
            getLogger().severe("OpenEcoApi not found — is OpenEco loaded and enabled?");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        OpenEcoApi api = rsp.getProvider();
        String currencyId = getConfig().getString("target-currency", "openeco");
        if (!api.hasCurrency(currencyId)) {
            getLogger().severe("Target currency '" + currencyId + "' is not configured in OpenEco.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        MigrationContext context = new MigrationContext(
                this,
                getDataFolder().getParentFile().toPath(),
                MigrationSupport.pathOverrides(this));
        MigrationEngine engine = new MigrationEngine(api, context, currencyId);
        bridge = new EconomyMigrationBridgeImpl(api, engine, context);
        getServer().getServicesManager().register(EconomyMigrationBridge.class, bridge, this, org.bukkit.plugin.ServicePriority.Normal);

        getLogger().info("OpenEcoMigrator enabled. Target currency: " + currencyId);
        getLogger().info("Use /openecomigrate <source> on the main OpenEco plugin.");
    }

    private EconomyMigrationBridge bridge;

    @Override
    public void onDisable() {
        if (bridge != null) {
            getServer().getServicesManager().unregister(EconomyMigrationBridge.class, bridge);
        }
    }
}
