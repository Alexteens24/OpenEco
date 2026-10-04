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

import dev.alexisbinh.openeco.service.AmountBounds;

import java.math.BigDecimal;

/**
 * Amount parsing shared by {@code /pay} and {@code /eco} so both commands reject exactly the
 * same inputs for exactly the same reasons. Anything that survives this check can still be
 * persisted; anything that does not is reported before it reaches the in-memory balance.
 */
final class AmountArgument {

    private AmountArgument() {
    }

    /**
     * @param raw      the raw command argument
     * @param allowZero whether zero is acceptable ({@code /eco set} allows it, {@code /pay} does not)
     */
    static AmountParse parse(String raw, boolean allowZero) {
        BigDecimal amount;
        try {
            amount = new BigDecimal(raw.trim());
        } catch (NumberFormatException error) {
            return AmountParse.rejected("invalid-amount", "not a number");
        }

        if (amount.precision() > AmountBounds.COLUMN_PRECISION || Math.abs(amount.scale()) > 18) {
            return AmountParse.rejected("invalid-amount", "scale or precision out of range");
        }

        try {
            AmountBounds.requirePersistable(amount);
        } catch (IllegalArgumentException error) {
            return AmountParse.rejected("invalid-amount", error.getMessage());
        }

        if (amount.signum() < 0 || (!allowZero && amount.signum() == 0)) {
            return AmountParse.rejected("negative-amount",
                    allowZero ? "must not be negative" : "must be greater than zero");
        }

        return AmountParse.accepted(amount);
    }

    /**
     * Outcome of parsing an amount argument.
     *
     * @param amount     the parsed value, only meaningful when {@link #accepted()}
     * @param messageKey message key to send back when the argument was rejected
     * @param reason     human readable detail shown as {@code <reason>}
     */
    record AmountParse(BigDecimal amount, String messageKey, String reason) {

        static AmountParse accepted(BigDecimal amount) {
            return new AmountParse(amount, null, null);
        }

        static AmountParse rejected(String messageKey, String reason) {
            return new AmountParse(null, messageKey, reason);
        }

        boolean accepted() {
            return messageKey == null;
        }
    }
}