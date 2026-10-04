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
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

final class ConfigMigrator {

    private static final String LEGACY_CURRENCY_ROOT = "currency";
    private static final String LEGACY_LOAD_STRATEGY = "accounts.load-strategy";

    private ConfigMigrator() {
    }

    /**
     * Brings an existing {@code config.yml} up to the bundled schema <em>in place</em>.
     *
     * <p>The object is mutated rather than rebuilt because a document's leading comment block -
     * the Apache licence header - is held by the configuration object itself and is not exposed
     * through {@link ConfigurationSection#getComments(String)}. Rebuilding a fresh configuration
     * would therefore silently discard the licence header and every blank line separator, so the
     * rewrite works on what we loaded and only ever adds the keys an upgrade introduced.
     *
     * <p>Because it mutates in place, callers must snapshot {@link #keyPaths(ConfigurationSection)}
     * before the call to find out whether anything was actually added.
     *
     * @return the same instance, for convenience
     */
    static YamlConfiguration rewrite(YamlConfiguration currentConfig, YamlConfiguration defaultConfig) {
        Objects.requireNonNull(currentConfig, "currentConfig");
        Objects.requireNonNull(defaultConfig, "defaultConfig");

        migrateLegacyCurrencySection(currentConfig, currentConfig);
        migrateRenamedSettings(currentConfig, currentConfig);
        currentConfig.set(LEGACY_LOAD_STRATEGY, null);
        dropSectionIfEmpty(currentConfig, "accounts");
        addMissingDefaults(defaultConfig, currentConfig);
        adoptTemplateComments(defaultConfig, currentConfig);
        return currentConfig;
    }

    /**
     * All key paths in this configuration, sections included. Comparing this set before and
     * after {@link #rewrite} tells a caller whether new keys were introduced, which is the only
     * thing worth writing back. Comparing serialised text instead would fire on cosmetic value
     * changes such as {@code 0.00} becoming {@code 0.0} and rewrite the file for nothing.
     */
    static Set<String> keyPaths(ConfigurationSection section) {
        return new TreeSet<>(section.getKeys(true));
    }

    private static void addMissingDefaults(ConfigurationSection template, ConfigurationSection target) {
        for (String key : template.getKeys(false)) {
            ConfigurationSection templateChild = template.getConfigurationSection(key);

            if (templateChild == null) {
                if (target.contains(key)) {
                    continue;
                }
                target.set(key, template.get(key));
                continue;
            }

            ConfigurationSection targetChild = target.getConfigurationSection(key);
            if (targetChild == null) {
                targetChild = target.createSection(key);
                addMissingDefaults(templateChild, targetChild);
                continue;
            }

            addMissingDefaults(templateChild, targetChild);
        }
    }

    /**
     * Fills in the bundled documentation wherever the operator has none of their own, so an
     * untouched setting keeps explaining itself and a deleted comment does not hide the reason
     * a setting exists. A comment the operator wrote always wins.
     */
    private static void adoptTemplateComments(ConfigurationSection template, ConfigurationSection target) {
        for (String key : template.getKeys(false)) {
            if (!target.contains(key)) {
                continue;
            }

            // A comment block above a key belongs to that key's path, section headers included.
            if (target.getComments(key).isEmpty() && !template.getComments(key).isEmpty()) {
                target.setComments(key, template.getComments(key));
            }
            if (target.getInlineComments(key).isEmpty() && !template.getInlineComments(key).isEmpty()) {
                target.setInlineComments(key, template.getInlineComments(key));
            }

            ConfigurationSection templateChild = template.getConfigurationSection(key);
            ConfigurationSection targetChild = target.getConfigurationSection(key);
            if (templateChild != null && targetChild != null) {
                adoptTemplateComments(templateChild, targetChild);
            }
        }
    }

    private static void dropSectionIfEmpty(YamlConfiguration config, String path) {
        ConfigurationSection section = config.getConfigurationSection(path);
        if (section != null && section.getKeys(false).isEmpty()) {
            config.set(path, null);
        }
    }

    private static void migrateRenamedSettings(YamlConfiguration currentConfig, YamlConfiguration targetConfig) {
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

    private static void migrateRenamedSetting(YamlConfiguration source, YamlConfiguration target,
                                              String oldPath, String newPath) {
        if (source.contains(oldPath) && !source.contains(newPath)) {
            target.set(newPath, source.get(oldPath));
        }
        target.set(oldPath, null);
    }

    /**
     * Carries a legacy root-level {@code currency} section over to {@code currencies.definitions}.
     *
     * <p>Runs even when a {@code currencies} section already exists, because a partially migrated
     * config would otherwise lose its legacy values silently. Existing keys are never overwritten.
     */
    private static void migrateLegacyCurrencySection(YamlConfiguration currentConfig, YamlConfiguration targetConfig) {
        if (!currentConfig.isConfigurationSection(LEGACY_CURRENCY_ROOT)) {
            return;
        }

        String legacyCurrencyId = sanitized(currentConfig.getString("currency.id"), "openeco");
        String definitionPath = "currencies.definitions." + legacyCurrencyId;

        if (!targetConfig.contains("currencies.default")) {
            targetConfig.set("currencies.default", legacyCurrencyId);
        }
        if (!targetConfig.contains(definitionPath + ".name-singular")) {
            targetConfig.set(definitionPath + ".name-singular",
                    sanitized(currentConfig.getString("currency.name-singular"), "Dollar"));
        }
        if (!targetConfig.contains(definitionPath + ".name-plural")) {
            targetConfig.set(definitionPath + ".name-plural",
                    sanitized(currentConfig.getString("currency.name-plural"), "Dollars"));
        }
        if (!targetConfig.contains(definitionPath + ".decimal-digits")) {
            targetConfig.set(definitionPath + ".decimal-digits",
                    clampFractionalDigits(currentConfig.getInt("currency.decimal-digits", 2)));
        }
        if (!targetConfig.contains(definitionPath + ".starting-balance")) {
            targetConfig.set(definitionPath + ".starting-balance",
                    currentConfig.getDouble("currency.starting-balance", 0.0));
        }
        if (!targetConfig.contains(definitionPath + ".max-balance")) {
            targetConfig.set(definitionPath + ".max-balance",
                    currentConfig.getDouble("currency.max-balance", -1.0));
        }

        // Only the root section is retired. A nested "currency" key belongs to another plugin
        // and must survive; a path-scoped removal keeps it intact.
        targetConfig.set(LEGACY_CURRENCY_ROOT, null);
    }

    /**
     * Copies every value from {@code sourceSection} into {@code targetSection}.
     *
     * <p>The legacy {@code currency} key is skipped at the root only: it is replaced by
     * {@link #migrateLegacyCurrencySection}. Skipping it at any depth would silently delete a
     * perfectly valid configuration key belonging to some other plugin.
     */
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
