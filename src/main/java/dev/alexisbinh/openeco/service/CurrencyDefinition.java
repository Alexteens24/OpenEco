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

import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

record CurrencyDefinition(
        String id,
        String singularName,
        String pluralName,
        int fractionalDigits,
        BigDecimal startingBalance,
        @Nullable BigDecimal maxBalance,
        String formatPattern,
        boolean grouping,
        char decimalSeparator,
        char groupingSeparator
) {

    CurrencyDefinition {
        id = requireText(id, "id");
        singularName = requireText(singularName, "singularName");
        pluralName = requireText(pluralName, "pluralName");
        startingBalance = Objects.requireNonNull(startingBalance, "startingBalance");
        formatPattern = requireText(formatPattern, "formatPattern");
        if (!formatPattern.contains("<amount>")) {
            throw new IllegalArgumentException("formatPattern must contain <amount>");
        }
        if (decimalSeparator == groupingSeparator) {
            throw new IllegalArgumentException("decimalSeparator and groupingSeparator must be different");
        }
    }

    boolean hasMaxBalance() {
        return maxBalance != null;
    }

    String format(BigDecimal amount) {
        BigDecimal scaled = amount.setScale(fractionalDigits, RoundingMode.HALF_UP);
        String unit = scaled.abs().compareTo(BigDecimal.ONE) == 0 ? singularName : pluralName;
        return formatPattern
                .replace("<amount>", formatNumber(scaled))
                .replace("<name>", unit)
                .replace("<currency>", id);
    }

    private String formatNumber(BigDecimal amount) {
        String plain = amount.toPlainString();
        int decimalIndex = plain.indexOf('.');
        String integer = decimalIndex >= 0 ? plain.substring(0, decimalIndex) : plain;
        String fraction = decimalIndex >= 0 ? plain.substring(decimalIndex + 1) : "";

        if (grouping) {
            boolean negative = integer.startsWith("-");
            String digits = negative ? integer.substring(1) : integer;
            StringBuilder grouped = new StringBuilder(integer.length() + integer.length() / 3);
            if (negative) grouped.append('-');
            int firstGroup = digits.length() % 3;
            if (firstGroup == 0) firstGroup = 3;
            grouped.append(digits, 0, firstGroup);
            for (int i = firstGroup; i < digits.length(); i += 3) {
                grouped.append(groupingSeparator).append(digits, i, i + 3);
            }
            integer = grouped.toString();
        }

        return fraction.isEmpty() ? integer : integer + decimalSeparator + fraction;
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return trimmed;
    }
}
