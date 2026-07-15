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

package dev.alexisbinh.openeco;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;

final class ConfigMigrator {

    private static final String LEGACY_CURRENCY_ROOT = "currency";
    private static final String NEW_CURRENCIES_ROOT = "currencies";

    private ConfigMigrator() {
    }

    static YamlConfiguration rewrite(FileConfiguration currentConfig, FileConfiguration defaultConfig) {
        Objects.requireNonNull(currentConfig, "currentConfig");
        Objects.requireNonNull(defaultConfig, "defaultConfig");

        YamlConfiguration normalizedSource = copySourceWithoutLegacy(currentConfig);
        migrateLegacyCurrencySection(currentConfig, normalizedSource);
        migrateRenamedSettings(currentConfig, normalizedSource);

        YamlConfiguration orderedConfig = new YamlConfiguration();
        mergeSection(defaultConfig, normalizedSource, orderedConfig);
        return orderedConfig;
    }

    private static YamlConfiguration copySourceWithoutLegacy(FileConfiguration currentConfig) {
        YamlConfiguration normalizedSource = new YamlConfiguration();
        copySection(currentConfig, normalizedSource);
        normalizedSource.set("accounts.load-strategy", null);
        ConfigurationSection accounts = normalizedSource.getConfigurationSection("accounts");
        if (accounts != null && accounts.getKeys(false).isEmpty()) {
            normalizedSource.set("accounts", null);
        }
        return normalizedSource;
    }

    private static void migrateRenamedSettings(FileConfiguration currentConfig, YamlConfiguration targetConfig) {
        migrateRenamedSetting(currentConfig, targetConfig,
                "autosave-interval", "persistence.autosave-interval-seconds");
        migrateRenamedSetting(currentConfig, targetConfig,
                "baltop.cache-ttl-seconds", "baltop.refresh-interval-seconds");
        migrateRenamedSetting(currentConfig, targetConfig,
                "account-cache.mode", "account-loading.mode");
        migrateRenamedSetting(currentConfig, targetConfig,
                "account-cache.enabled", "account-loading.lazy.cache.enabled");
        migrateRenamedSetting(currentConfig, targetConfig,
                "account-cache.maximum-size", "account-loading.lazy.cache.maximum-size");
        migrateRenamedSetting(currentConfig, targetConfig,
                "account-cache.expire-after-access-minutes",
                "account-loading.lazy.cache.expire-after-access-minutes");
        ConfigurationSection legacyAccountCache = targetConfig.getConfigurationSection("account-cache");
        if (legacyAccountCache != null && legacyAccountCache.getKeys(false).isEmpty()) {
            targetConfig.set("account-cache", null);
        }
    }

    private static void migrateRenamedSetting(FileConfiguration source, YamlConfiguration target,
                                              String oldPath, String newPath) {
        if (source.contains(oldPath) && !source.contains(newPath)) {
            target.set(newPath, source.get(oldPath));
        }
        target.set(oldPath, null);
    }

    private static void migrateLegacyCurrencySection(FileConfiguration currentConfig, YamlConfiguration targetConfig) {
        if (!currentConfig.isConfigurationSection(LEGACY_CURRENCY_ROOT)) {
            return;
        }

        if (!currentConfig.isConfigurationSection(NEW_CURRENCIES_ROOT)) {
            String legacyCurrencyId = sanitized(currentConfig.getString("currency.id"), "openeco");
            String singular = sanitized(currentConfig.getString("currency.name-singular"), "Dollar");
            String plural = sanitized(currentConfig.getString("currency.name-plural"), "Dollars");
            int fractionalDigits = clampFractionalDigits(currentConfig.getInt("currency.decimal-digits", 2));
            double startingBalance = currentConfig.getDouble("currency.starting-balance", 0.0);
            double maxBalance = currentConfig.getDouble("currency.max-balance", -1.0);

            targetConfig.set("currencies.default", legacyCurrencyId);
            targetConfig.set("currencies.definitions." + legacyCurrencyId + ".name-singular", singular);
            targetConfig.set("currencies.definitions." + legacyCurrencyId + ".name-plural", plural);
            targetConfig.set("currencies.definitions." + legacyCurrencyId + ".decimal-digits", fractionalDigits);
            targetConfig.set("currencies.definitions." + legacyCurrencyId + ".starting-balance", startingBalance);
            targetConfig.set("currencies.definitions." + legacyCurrencyId + ".max-balance", maxBalance);
        }
    }

    private static void copySection(ConfigurationSection sourceSection, ConfigurationSection targetSection) {
        for (String key : sourceSection.getKeys(false)) {
            if (LEGACY_CURRENCY_ROOT.equals(key)) {
                continue;
            }

            ConfigurationSection sourceChild = sourceSection.getConfigurationSection(key);
            if (sourceChild != null) {
                ConfigurationSection targetChild = targetSection.createSection(key);
                copySection(sourceChild, targetChild);
            } else {
                targetSection.set(key, sourceSection.get(key));
            }
        }
    }

    private static void mergeSection(ConfigurationSection templateSection, ConfigurationSection sourceSection,
                                     ConfigurationSection targetSection) {
        Set<String> templateKeys = templateSection == null ? Collections.emptySet() : templateSection.getKeys(false);

        if (templateSection != null) {
            for (String key : templateKeys) {
                ConfigurationSection templateChild = templateSection.getConfigurationSection(key);
                ConfigurationSection sourceChild = sourceSection == null ? null : sourceSection.getConfigurationSection(key);

                if (templateChild != null) {
                    ConfigurationSection targetChild = targetSection.createSection(key);
                    mergeSection(templateChild, sourceChild, targetChild);
                } else {
                    Object value = sourceSection != null && sourceSection.contains(key)
                            ? sourceSection.get(key)
                            : templateSection.get(key);
                    targetSection.set(key, value);
                }
            }
        }

        if (sourceSection == null) {
            return;
        }

        for (String key : sourceSection.getKeys(false)) {
            if (templateKeys.contains(key)) {
                continue;
            }

            ConfigurationSection sourceChild = sourceSection.getConfigurationSection(key);
            if (sourceChild != null) {
                ConfigurationSection targetChild = targetSection.createSection(key);
                mergeSection(null, sourceChild, targetChild);
            } else {
                targetSection.set(key, sourceSection.get(key));
            }
        }
    }

    private static String sanitized(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private static int clampFractionalDigits(int fractionalDigits) {
        return Math.max(0, Math.min(8, fractionalDigits));
    }
}
