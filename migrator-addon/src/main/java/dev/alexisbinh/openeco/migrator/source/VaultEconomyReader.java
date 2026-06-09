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

package dev.alexisbinh.openeco.migrator.source;

import dev.alexisbinh.openeco.migrator.model.ForeignAccount;
import dev.alexisbinh.openeco.migrator.model.MigrationSource;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.io.IOException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Imports through the active Vault economy provider.
 * Requires the source economy plugin to still be registered and not replaced by OpenEco.
 */
public final class VaultEconomyReader implements EconomySourceReader {

    private static final List<String> OPENECO_NAMES = List.of("openeco", "openeconomy");

    @Override
    public MigrationSource source() {
        return MigrationSource.VAULT;
    }

    @Override
    public String describeLocation(MigrationContext context) {
        Economy economy = resolveEconomy(context);
        return economy == null ? "Vault (no external economy hooked)" : "Vault -> " + economy.getName();
    }

    @Override
    public boolean isAvailable(MigrationContext context) {
        return resolveEconomy(context) != null;
    }

    @Override
    public List<ForeignAccount> read(MigrationContext context) throws IOException {
        Economy economy = resolveEconomy(context);
        if (economy == null) {
            throw new IOException("No external Vault economy provider is hooked");
        }

        List<ForeignAccount> accounts = new ArrayList<>();
        if (economy instanceof net.milkbowl.vault2.economy.Economy v2) {
            for (Map.Entry<UUID, String> entry : v2.getUUIDNameMap().entrySet()) {
                BigDecimal balance = v2.getBalance("OpenEcoMigrator", entry.getKey());
                accounts.add(new ForeignAccount(entry.getKey(), sanitizeName(entry.getValue()), balance));
            }
            return List.copyOf(accounts);
        }

        List<String> names = listAccountsViaReflection(economy);
        if (names == null) {
            throw new IOException(
                    "Vault provider " + economy.getName()
                            + " does not expose account listings; use a file/database source instead.");
        }
        for (String name : names) {
            if (!economy.hasAccount(name)) {
                continue;
            }
            @SuppressWarnings("deprecation")
            double balance = economy.getBalance(name);
            UUID id = resolveUuid(economy, name);
            if (id == null) {
                continue;
            }
            accounts.add(new ForeignAccount(id, sanitizeName(name), BigDecimal.valueOf(balance)));
        }
        return List.copyOf(accounts);
    }

    @SuppressWarnings("unchecked")
    private static List<String> listAccountsViaReflection(Economy economy) {
        try {
            Method method = economy.getClass().getMethod("getAccounts");
            Object result = method.invoke(economy);
            if (!(result instanceof List<?> list)) {
                return null;
            }
            List<String> names = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item instanceof String name) {
                    names.add(name);
                }
            }
            return names;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static Economy resolveEconomy(MigrationContext context) {
        RegisteredServiceProvider<Economy> rsp =
                context.plugin().getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return null;
        }
        Economy economy = rsp.getProvider();
        String name = economy.getName().toLowerCase(Locale.ROOT);
        if (OPENECO_NAMES.contains(name)) {
            return null;
        }
        return economy;
    }

    private static UUID resolveUuid(Economy economy, String name) {
        if (economy instanceof net.milkbowl.vault2.economy.Economy v2) {
            return v2.getUUIDNameMap().entrySet().stream()
                    .filter(entry -> entry.getValue().equalsIgnoreCase(name))
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse(null);
        }
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(name);
        UUID id = offlinePlayer.getUniqueId();
        return id == null ? null : id;
    }

    private static String sanitizeName(String name) {
        String trimmed = name == null ? "unknown" : name.trim();
        if (trimmed.length() > 16) {
            return trimmed.substring(0, 16);
        }
        return trimmed.isEmpty() ? "unknown" : trimmed;
    }
}
