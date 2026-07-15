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

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigMigratorTest {

    @Test
    void removesLegacyAccountLoadStrategyWithoutLeavingEmptySection() {
        YamlConfiguration current = new YamlConfiguration();
        current.set("accounts.load-strategy", "lazy");
        current.set("pay.cooldown-seconds", 12);

        YamlConfiguration migrated = ConfigMigrator.rewrite(current, loadBundledDefaultConfig());

        assertFalse(migrated.contains("accounts.load-strategy"));
        assertFalse(migrated.isConfigurationSection("accounts"));
        assertEquals(12, migrated.getInt("pay.cooldown-seconds"));
    }

    @Test
    void rewritesLegacyCurrencyBlockInTemplateOrder() {
        YamlConfiguration current = new YamlConfiguration();
        current.set("currency.id", "coins");
        current.set("currency.name-singular", "Coin");
        current.set("currency.name-plural", "Coins");
        current.set("currency.decimal-digits", 3);
        current.set("currency.starting-balance", 1.234);
        current.set("currency.max-balance", 999.999);
        current.set("custom.feature.enabled", true);

        YamlConfiguration defaults = loadBundledDefaultConfig();
        YamlConfiguration migrated = ConfigMigrator.rewrite(current, defaults);
        String yaml = migrated.saveToString();
        int currenciesIndex = yaml.indexOf("currencies:");
        int storageIndex = yaml.indexOf("storage:");
        int persistenceIndex = yaml.indexOf("persistence:");
        int payIndex = yaml.indexOf("pay:");
        int baltopIndex = yaml.indexOf("baltop:");
        int historyIndex = yaml.indexOf("history:");
        int crossServerIndex = yaml.indexOf("cross-server:");

        assertFalse(yaml.contains("\ncurrency:"), yaml);
        assertTrue(currenciesIndex >= 0, yaml);
        assertTrue(storageIndex >= 0, yaml);
        assertTrue(persistenceIndex >= 0, yaml);
        assertTrue(payIndex >= 0, yaml);
        assertTrue(baltopIndex >= 0, yaml);
        assertTrue(historyIndex >= 0, yaml);
        assertTrue(crossServerIndex >= 0, yaml);
        assertTrue(currenciesIndex < storageIndex,
            () -> "currencies=" + currenciesIndex + " storage=" + storageIndex + "\n" + yaml);
        assertTrue(storageIndex < persistenceIndex,
            () -> "storage=" + storageIndex + " persistence=" + persistenceIndex + "\n" + yaml);
        assertTrue(persistenceIndex < payIndex,
            () -> "persistence=" + persistenceIndex + " pay=" + payIndex + "\n" + yaml);
        assertTrue(payIndex < baltopIndex,
            () -> "pay=" + payIndex + " baltop=" + baltopIndex + "\n" + yaml);
        assertTrue(baltopIndex < historyIndex,
            () -> "baltop=" + baltopIndex + " history=" + historyIndex + "\n" + yaml);
        assertTrue(historyIndex < crossServerIndex,
            () -> "history=" + historyIndex + " crossServer=" + crossServerIndex + "\n" + yaml);
        assertTrue(yaml.contains("default: coins"));
        assertTrue(migrated.getBoolean("custom.feature.enabled"));
        assertTrue(yaml.contains("custom:"), yaml);
        assertTrue(yaml.contains("  coins:"));
    }

    @Test
    void migratesRenamedIntervalsWithoutOverwritingNewValues() {
        YamlConfiguration current = new YamlConfiguration();
        current.set("autosave-interval", 45);
        current.set("baltop.cache-ttl-seconds", 60);

        YamlConfiguration migrated = ConfigMigrator.rewrite(current, loadBundledDefaultConfig());

        assertEquals(45, migrated.getLong("persistence.autosave-interval-seconds"));
        assertEquals(60, migrated.getLong("baltop.refresh-interval-seconds"));
        assertFalse(migrated.contains("autosave-interval"));
        assertFalse(migrated.contains("baltop.cache-ttl-seconds"));

        current.set("persistence.autosave-interval-seconds", 90);
        current.set("baltop.refresh-interval-seconds", 120);
        migrated = ConfigMigrator.rewrite(current, loadBundledDefaultConfig());

        assertEquals(90, migrated.getLong("persistence.autosave-interval-seconds"));
        assertEquals(120, migrated.getLong("baltop.refresh-interval-seconds"));
    }

    @Test
    void migratesExperimentalAccountCacheSettings() {
        YamlConfiguration current = new YamlConfiguration();
        current.set("account-cache.mode", "lazy");
        current.set("account-cache.enabled", false);
        current.set("account-cache.maximum-size", 1234);
        current.set("account-cache.expire-after-access-minutes", 7);

        YamlConfiguration migrated = ConfigMigrator.rewrite(current, loadBundledDefaultConfig());

        assertEquals("lazy", migrated.getString("account-loading.mode"));
        assertFalse(migrated.getBoolean("account-loading.lazy.cache.enabled"));
        assertEquals(1234, migrated.getInt("account-loading.lazy.cache.maximum-size"));
        assertEquals(7, migrated.getLong("account-loading.lazy.cache.expire-after-access-minutes"));
        assertFalse(migrated.contains("account-cache"));
    }

    @Test
    void keepsCustomCurrenciesInsideTheCurrenciesSection() {
        YamlConfiguration current = new YamlConfiguration();
        current.set("currencies.default", "gems");
        current.set("currencies.definitions.gems.name-singular", "Gem");
        current.set("currencies.definitions.gems.name-plural", "Gems");
        current.set("currencies.definitions.gems.decimal-digits", 0);
        current.set("currencies.definitions.gems.starting-balance", 5.0);
        current.set("currencies.definitions.gems.max-balance", 5000.0);
        current.set("custom.feature.enabled", true);

        YamlConfiguration defaults = loadBundledDefaultConfig();
        YamlConfiguration migrated = ConfigMigrator.rewrite(current, defaults);
        String yaml = migrated.saveToString();
        int openecoIndex = yaml.indexOf("openeco:");
        int gemsIndex = yaml.indexOf("gems:");
        int storageIndex = yaml.indexOf("storage:");

        assertEquals("gems", migrated.getString("currencies.default"));
        assertEquals("Gem", migrated.getString("currencies.definitions.gems.name-singular"));
        assertEquals("Gems", migrated.getString("currencies.definitions.gems.name-plural"));
        assertFalse(migrated.contains("accounts.load-strategy"));
        assertTrue(openecoIndex >= 0, yaml);
        assertTrue(gemsIndex >= 0, yaml);
        assertTrue(storageIndex >= 0, yaml);
        assertTrue(openecoIndex < gemsIndex,
            () -> "openeco=" + openecoIndex + " gems=" + gemsIndex + "\n" + yaml);
        assertTrue(gemsIndex < storageIndex,
            () -> "gems=" + gemsIndex + " storage=" + storageIndex + "\n" + yaml);
        assertTrue(migrated.getBoolean("custom.feature.enabled"));
        assertTrue(yaml.contains("custom:"), yaml);
    }

    private static YamlConfiguration loadBundledDefaultConfig() {
        try (InputStream input = Objects.requireNonNull(
                ConfigMigratorTest.class.getClassLoader().getResourceAsStream("config.yml"),
                "Bundled config.yml is missing");
             InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load bundled config.yml", e);
        }
    }
}
