package dev.alexisbinh.openeco.service;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EconomyConfigSnapshotTest {

    @Test
    void legacyCurrencyBlockIsLiftedIntoDefaultCurrencyRegistry() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("currency.id", "coins");
        config.set("currency.name-singular", "Coin");
        config.set("currency.name-plural", "Coins");
        config.set("currency.decimal-digits", 3);
        config.set("currency.starting-balance", 1.234);
        config.set("currency.max-balance", 999.999);

        EconomyConfigSnapshot snapshot = EconomyConfigSnapshot.from(config);

        assertEquals("coins", snapshot.currencyId());
        assertEquals("Coin", snapshot.currencySingular());
        assertEquals("Coins", snapshot.currencyPlural());
        assertEquals(3, snapshot.fractionalDigits());
        assertEquals(new BigDecimal("1.234"), snapshot.startingBalance());
        assertEquals(new BigDecimal("999.999"), snapshot.maxBalance());
        assertEquals(1, snapshot.currencies().size());
        assertEquals("coins", snapshot.currencies().defaultCurrencyId());
        assertEquals("coins", snapshot.defaultCurrency().id());
    }

    @Test
    void multiCurrencyCatalogUsesConfiguredDefaultCurrency() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("currencies.default", "gems");
        config.set("currencies.definitions.openeco.name-singular", "Dollar");
        config.set("currencies.definitions.openeco.name-plural", "Dollars");
        config.set("currencies.definitions.openeco.decimal-digits", 2);
        config.set("currencies.definitions.openeco.starting-balance", 0.0);
        config.set("currencies.definitions.openeco.max-balance", -1.0);
        config.set("currencies.definitions.gems.name-singular", "Gem");
        config.set("currencies.definitions.gems.name-plural", "Gems");
        config.set("currencies.definitions.gems.decimal-digits", 0);
        config.set("currencies.definitions.gems.starting-balance", 5.0);
        config.set("currencies.definitions.gems.max-balance", 5000.0);
        config.set("pay.min-amount", 1.0);

        EconomyConfigSnapshot snapshot = EconomyConfigSnapshot.from(config);

        assertEquals("gems", snapshot.currencyId());
        assertEquals("Gem", snapshot.currencySingular());
        assertEquals("Gems", snapshot.currencyPlural());
        assertEquals(0, snapshot.fractionalDigits());
        assertEquals(new BigDecimal("5"), snapshot.startingBalance());
        assertEquals(new BigDecimal("5000"), snapshot.maxBalance());
        assertEquals(2, snapshot.currencies().size());
        assertTrue(snapshot.currencies().has("openeco"));
        assertTrue(snapshot.currencies().has("GEMS"));
        assertNotNull(snapshot.currencies().get("openeco"));
    }

    @Test
    void nonPositiveHistoryRetentionIsNormalizedToKeepForever() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("history.retention-days", 0);

        EconomyConfigSnapshot snapshot = EconomyConfigSnapshot.from(config);

        assertEquals(-1, snapshot.historyRetentionDays());
    }

    @Test
    void positiveHistoryRetentionIsPreserved() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("history.retention-days", 7);

        EconomyConfigSnapshot snapshot = EconomyConfigSnapshot.from(config);

        assertEquals(7, snapshot.historyRetentionDays());
        assertTrue(snapshot.historyRetentionDays() > 0);
    }

    @Test
    void missingDefaultCurrencyInCatalogFailsFast() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("currencies.definitions.coins.name-singular", "Coin");
        config.set("currencies.definitions.coins.name-plural", "Coins");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> EconomyConfigSnapshot.from(config));

        assertTrue(error.getMessage().contains("currencies.default"));
    }

    @Test
    void duplicateCurrencyIdsIgnoringCaseAreRejected() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("currencies.default", "coins");
        config.set("currencies.definitions.coins.name-singular", "Coin");
        config.set("currencies.definitions.coins.name-plural", "Coins");
        config.set("currencies.definitions.Coins.name-singular", "Alt Coin");
        config.set("currencies.definitions.Coins.name-plural", "Alt Coins");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> EconomyConfigSnapshot.from(config));

        assertTrue(error.getMessage().contains("Duplicate currency id"));
    }
}