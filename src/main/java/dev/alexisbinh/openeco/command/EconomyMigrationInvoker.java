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

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.List;

/**
 * Resolves {@code EconomyMigrationBridge} from OpenEcoMigrator without a compile-time dependency
 * on the addon interface (separate plugin classloader).
 */
final class EconomyMigrationInvoker {

    private static final String MIGRATOR_PLUGIN = "OpenEcoMigrator";
    private static final String BRIDGE_CLASS = "dev.alexisbinh.openeco.api.EconomyMigrationBridge";

    private EconomyMigrationInvoker() {
    }

    static boolean isAvailable() {
        return bridge() != null;
    }

    @SuppressWarnings("unchecked")
    static List<String> sourceIds() {
        Object bridge = bridge();
        if (bridge == null) {
            return List.of();
        }
        try {
            return (List<String>) bridge.getClass().getMethod("sourceIds").invoke(bridge);
        } catch (ReflectiveOperationException e) {
            return List.of();
        }
    }

    static void sendSourceList(CommandSender sender) throws ReflectiveOperationException {
        Object bridge = requireBridge();
        bridge.getClass().getMethod("sendSourceList", CommandSender.class).invoke(bridge, sender);
    }

    static void scan(CommandSender sender, String sourceId) throws ReflectiveOperationException {
        Object bridge = requireBridge();
        bridge.getClass().getMethod("scan", CommandSender.class, String.class).invoke(bridge, sender, sourceId);
    }

    static void migrate(CommandSender sender, String sourceId, boolean dryRun, boolean overwrite)
            throws ReflectiveOperationException {
        Object bridge = requireBridge();
        bridge.getClass()
                .getMethod("migrate", CommandSender.class, String.class, boolean.class, boolean.class)
                .invoke(bridge, sender, sourceId, dryRun, overwrite);
    }

    private static Object requireBridge() throws ReflectiveOperationException {
        Object bridge = bridge();
        if (bridge == null) {
            throw new IllegalStateException("OpenEcoMigrator bridge is not available");
        }
        return bridge;
    }

    private static Object bridge() {
        Plugin migrator = Bukkit.getPluginManager().getPlugin(MIGRATOR_PLUGIN);
        if (migrator == null || !migrator.isEnabled()) {
            return null;
        }
        try {
            Class<?> bridgeClass = Class.forName(BRIDGE_CLASS, true, migrator.getClass().getClassLoader());
            RegisteredServiceProvider<?> registration =
                    Bukkit.getServicesManager().getRegistration(bridgeClass);
            return registration == null ? null : registration.getProvider();
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

}
