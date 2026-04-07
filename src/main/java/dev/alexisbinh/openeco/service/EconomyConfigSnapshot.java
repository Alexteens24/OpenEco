package dev.alexisbinh.openeco.service;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

record EconomyConfigSnapshot(
        CurrencyRegistry currencies,
        String currencyId,
        String currencySingular,
        String currencyPlural,
        int fractionalDigits,
        BigDecimal startingBalance,
        long payCooldownMs,
        BigDecimal payTaxRate,
        BigDecimal payMinAmount,
        BigDecimal maxBalance,
        long balTopCacheTtlMs,
        int historyRetentionDays
) {

    static EconomyConfigSnapshot from(FileConfiguration config) {
        Objects.requireNonNull(config, "config");

        CurrencyRegistry currencies = parseCurrencies(config);
        CurrencyDefinition defaultCurrency = currencies.defaultCurrency();

        BigDecimal payTaxRate = BigDecimal.valueOf(
                Math.max(0.0, Math.min(100.0, config.getDouble("pay.tax-percent", 0.0))));

        double minPay = config.getDouble("pay.min-amount", 0.01);
        BigDecimal payMinAmount = minPay > 0
                ? BigDecimal.valueOf(minPay).setScale(defaultCurrency.fractionalDigits(), RoundingMode.HALF_UP)
                : null;

        long balTopCacheTtlMs = Math.max(1, config.getLong("baltop.cache-ttl-seconds", 30)) * 1000L;
        int configuredHistoryRetentionDays = config.getInt("history.retention-days", -1);
        int historyRetentionDays = configuredHistoryRetentionDays > 0 ? configuredHistoryRetentionDays : -1;

        return new EconomyConfigSnapshot(
                                currencies,
                                defaultCurrency.id(),
                                defaultCurrency.singularName(),
                                defaultCurrency.pluralName(),
                                defaultCurrency.fractionalDigits(),
                                defaultCurrency.startingBalance(),
                Math.max(0, config.getLong("pay.cooldown-seconds", 0)) * 1000L,
                payTaxRate,
                payMinAmount,
                                defaultCurrency.maxBalance(),
                balTopCacheTtlMs,
                historyRetentionDays);
    }

        CurrencyDefinition defaultCurrency() {
                return currencies.defaultCurrency();
        }

        private static CurrencyRegistry parseCurrencies(FileConfiguration config) {
                ConfigurationSection definitionsSection = config.getConfigurationSection("currencies.definitions");
                if (definitionsSection != null && !definitionsSection.getKeys(false).isEmpty()) {
                        String configuredDefault = requireText(config.getString("currencies.default"), "currencies.default");
                        List<CurrencyDefinition> definitions = new ArrayList<>();
                        for (String currencyId : definitionsSection.getKeys(false)) {
                                ConfigurationSection currencySection = definitionsSection.getConfigurationSection(currencyId);
                                if (currencySection == null) {
                                        throw new IllegalArgumentException(
                                                        "Currency definition section is missing: currencies.definitions." + currencyId);
                                }
                                definitions.add(readCurrencyDefinition(currencySection, currencyId, currencyId, currencyId + "s"));
                        }
                        return CurrencyRegistry.of(configuredDefault, definitions);
                }

                String legacyCurrencyId = defaultText(config.getString("currency.id"), "openeco");
                CurrencyDefinition legacyCurrency = readLegacyCurrencyDefinition(config, legacyCurrencyId);
                return CurrencyRegistry.of(legacyCurrencyId, List.of(legacyCurrency));
        }

        private static CurrencyDefinition readLegacyCurrencyDefinition(FileConfiguration config, String currencyId) {
                int fractionalDigits = clampFractionalDigits(config.getInt("currency.decimal-digits", 2));
                BigDecimal startingBalance = scaledNonNegative(
                                config.getDouble("currency.starting-balance", 0.0),
                                fractionalDigits);
                BigDecimal maxBalance = scaledPositiveOrNull(
                                config.getDouble("currency.max-balance", -1.0),
                                fractionalDigits);

                return new CurrencyDefinition(
                                currencyId,
                                defaultText(config.getString("currency.name-singular"), "Dollar"),
                                defaultText(config.getString("currency.name-plural"), "Dollars"),
                                fractionalDigits,
                                startingBalance,
                                maxBalance);
        }

        private static CurrencyDefinition readCurrencyDefinition(ConfigurationSection section,
                                                                                                                         String currencyId,
                                                                                                                         String defaultSingular,
                                                                                                                         String defaultPlural) {
                int fractionalDigits = clampFractionalDigits(section.getInt("decimal-digits", 2));
                BigDecimal startingBalance = scaledNonNegative(section.getDouble("starting-balance", 0.0), fractionalDigits);
                BigDecimal maxBalance = scaledPositiveOrNull(section.getDouble("max-balance", -1.0), fractionalDigits);

                return new CurrencyDefinition(
                                currencyId,
                                defaultText(section.getString("name-singular"), defaultSingular),
                                defaultText(section.getString("name-plural"), defaultPlural),
                                fractionalDigits,
                                startingBalance,
                                maxBalance);
        }

        private static int clampFractionalDigits(int fractionalDigits) {
                return Math.max(0, Math.min(8, fractionalDigits));
        }

        private static BigDecimal scaledNonNegative(double value, int fractionalDigits) {
                return BigDecimal.valueOf(Math.max(0.0, value)).setScale(fractionalDigits, RoundingMode.HALF_UP);
        }

        private static BigDecimal scaledPositiveOrNull(double value, int fractionalDigits) {
                return value > 0
                                ? BigDecimal.valueOf(value).setScale(fractionalDigits, RoundingMode.HALF_UP)
                                : null;
        }

        private static String requireText(String value, String fieldName) {
                if (value == null) {
                        throw new IllegalArgumentException(fieldName + " must not be blank");
                }
                String trimmed = value.trim();
                if (trimmed.isEmpty()) {
                        throw new IllegalArgumentException(fieldName + " must not be blank");
                }
                return trimmed;
        }

        private static String defaultText(String value, String fallback) {
                if (value == null) {
                        return fallback;
                }
                String trimmed = value.trim();
                return trimmed.isEmpty() ? fallback : trimmed;
        }
}