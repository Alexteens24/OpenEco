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
        int balTopPageSize,
        int historyPageSize,
        int historyRetentionDays
) {

    static EconomyConfigSnapshot from(FileConfiguration config) {
        Objects.requireNonNull(config, "config");

        CurrencyRegistry currencies = parseCurrencies(config);
        CurrencyDefinition defaultCurrency = currencies.defaultCurrency();

        double taxPercent = config.getDouble("pay.tax-percent", 0.0);
        requireFiniteRange(taxPercent, "pay.tax-percent", 0.0, 100.0);
        BigDecimal payTaxRate = BigDecimal.valueOf(taxPercent);

        double minPay = config.getDouble("pay.min-amount", 0.01);
        requireFiniteRange(minPay, "pay.min-amount", 0.0, Double.MAX_VALUE);
        BigDecimal payMinAmount = minPay > 0
                ? BigDecimal.valueOf(minPay).setScale(defaultCurrency.fractionalDigits(), RoundingMode.HALF_UP)
                : null;

        long leaderboardRefreshMs = positiveLong(config, "baltop.refresh-interval-seconds", 30) * 1000L;
        int balTopPageSize = positiveInt(config, "baltop.page-size", 10);
        int historyPageSize = positiveInt(config, "history.page-size", 10);
        int configuredHistoryRetentionDays = config.getInt("history.retention-days", -1);
        int historyRetentionDays = configuredHistoryRetentionDays > 0 ? configuredHistoryRetentionDays : -1;

        return new EconomyConfigSnapshot(
                                currencies,
                                defaultCurrency.id(),
                                defaultCurrency.singularName(),
                                defaultCurrency.pluralName(),
                                defaultCurrency.fractionalDigits(),
                                defaultCurrency.startingBalance(),
                nonNegativeLong(config, "pay.cooldown-seconds", 0) * 1000L,
                payTaxRate,
                payMinAmount,
                defaultCurrency.maxBalance(),
                leaderboardRefreshMs,
                balTopPageSize,
                historyPageSize,
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
                int fractionalDigits = fractionalDigits(
                                config.getInt("currency.decimal-digits", 2), "currency.decimal-digits");
                BigDecimal startingBalance = scaledNonNegative(
                                config.getDouble("currency.starting-balance", 0.0),
                                fractionalDigits,
                                "currency.starting-balance");
                BigDecimal maxBalance = scaledPositiveOrNull(
                                config.getDouble("currency.max-balance", -1.0),
                                fractionalDigits,
                                "currency.max-balance");

                return new CurrencyDefinition(
                                currencyId,
                                defaultText(config.getString("currency.name-singular"), "Dollar"),
                                defaultText(config.getString("currency.name-plural"), "Dollars"),
                                fractionalDigits,
                                startingBalance,
                                maxBalance,
                                "<amount> <name>",
                                false,
                                '.',
                                ',');
        }

        private static CurrencyDefinition readCurrencyDefinition(ConfigurationSection section,
                                                                                                                         String currencyId,
                                                                                                                         String defaultSingular,
                                                                                                                         String defaultPlural) {
                String root = "currencies.definitions." + currencyId + ".";
                int fractionalDigits = fractionalDigits(section.getInt("decimal-digits", 2), root + "decimal-digits");
                BigDecimal startingBalance = scaledNonNegative(
                                section.getDouble("starting-balance", 0.0), fractionalDigits, root + "starting-balance");
                BigDecimal maxBalance = scaledPositiveOrNull(
                                section.getDouble("max-balance", -1.0), fractionalDigits, root + "max-balance");
                char decimalSeparator = singleCharacter(
                                section.getString("decimal-separator", "."), root + "decimal-separator");
                char groupingSeparator = singleCharacter(
                                section.getString("grouping-separator", ","), root + "grouping-separator");
                if (decimalSeparator == groupingSeparator) {
                        throw new IllegalArgumentException(
                                        root + "decimal-separator and grouping-separator must be different");
                }

                return new CurrencyDefinition(
                                currencyId,
                                defaultText(section.getString("name-singular"), defaultSingular),
                                defaultText(section.getString("name-plural"), defaultPlural),
                                fractionalDigits,
                                startingBalance,
                                maxBalance,
                                requireFormat(section.getString("format", "<amount> <name>"), currencyId),
                                section.getBoolean("grouping", false),
                                decimalSeparator,
                                groupingSeparator);
        }

        private static String requireFormat(String value, String currencyId) {
                String format = requireText(value, "currencies.definitions." + currencyId + ".format");
                if (!format.contains("<amount>")) {
                        throw new IllegalArgumentException(
                                        "currencies.definitions." + currencyId + ".format must contain <amount>");
                }
                return format;
        }

        private static char singleCharacter(String value, String fieldName) {
                if (value == null || value.length() != 1) {
                        throw new IllegalArgumentException(fieldName + " must be exactly one character");
                }
                return value.charAt(0);
        }

        private static long positiveLong(FileConfiguration config, String path, long fallback) {
                long value = config.getLong(path, fallback);
                if (value <= 0) {
                        throw new IllegalArgumentException(path + " must be greater than 0");
                }
                return value;
        }

        private static long nonNegativeLong(FileConfiguration config, String path, long fallback) {
                long value = config.getLong(path, fallback);
                if (value < 0) {
                        throw new IllegalArgumentException(path + " must not be negative");
                }
                return value;
        }

        private static int positiveInt(FileConfiguration config, String path, int fallback) {
                int value = config.getInt(path, fallback);
                if (value <= 0) {
                        throw new IllegalArgumentException(path + " must be greater than 0");
                }
                return value;
        }

        private static int fractionalDigits(int value, String path) {
                if (value < 0 || value > 8) {
                        throw new IllegalArgumentException(path + " must be between 0 and 8");
                }
                return value;
        }

        private static BigDecimal scaledNonNegative(double value, int fractionalDigits, String path) {
                requireFiniteRange(value, path, 0.0, Double.MAX_VALUE);
                return BigDecimal.valueOf(value).setScale(fractionalDigits, RoundingMode.HALF_UP);
        }

        private static BigDecimal scaledPositiveOrNull(double value, int fractionalDigits, String path) {
                if (!Double.isFinite(value) || (value <= 0 && value != -1.0)) {
                        throw new IllegalArgumentException(path + " must be -1 or greater than 0");
                }
                return value == -1.0
                                ? null
                                : BigDecimal.valueOf(value).setScale(fractionalDigits, RoundingMode.HALF_UP);
        }

        private static void requireFiniteRange(double value, String path, double min, double max) {
                if (!Double.isFinite(value) || value < min || value > max) {
                        throw new IllegalArgumentException(path + " must be between " + min + " and " + max);
                }
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
