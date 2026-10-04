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

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Storage capacity limits for monetary amounts.
 *
 * <p>Balances are persisted as {@code DECIMAL(30,8)} on every dialect, so a value may carry at
 * most {@value #MAX_INTEGER_DIGITS} integer digits no matter how the currency is configured:
 * the column always reserves eight digits for the fractional part. An amount beyond that is
 * silently accepted in memory and then rejected by the driver, which aborts the whole
 * autosave batch. Validating at the API boundary turns that into an actionable error.
 */
public final class AmountBounds {

    /** Total significant digits in the {@code DECIMAL(30,8)} balance column. */
    public static final int COLUMN_PRECISION = 30;

    /** Digits the column reserves for the fractional part, independent of the currency. */
    public static final int COLUMN_SCALE = 8;

    /** Largest number of integer digits that survives a round trip through storage. */
    public static final int MAX_INTEGER_DIGITS = COLUMN_PRECISION - COLUMN_SCALE;

    private AmountBounds() {
    }

    /**
     * Integer digits this amount needs once stored, i.e. the digits before the decimal point
     * after rounding to the column scale. Never negative: anything below one is zero digits.
     */
    public static int integerDigits(BigDecimal amount) {
        BigDecimal stored = amount.setScale(COLUMN_SCALE, RoundingMode.HALF_UP);
        return Math.max(0, stored.precision() - COLUMN_SCALE);
    }

    /**
     * @return true when the amount fits the balance column and can be persisted
     */
    public static boolean fitsColumn(BigDecimal amount) {
        return integerDigits(amount) <= MAX_INTEGER_DIGITS;
    }

    /**
     * Rejects amounts the storage layer could never write back.
     *
     * @throws IllegalArgumentException when the amount exceeds {@link #MAX_INTEGER_DIGITS}
     */
    public static BigDecimal requirePersistable(BigDecimal amount) {
        if (!fitsColumn(amount)) {
            throw new IllegalArgumentException("amount has " + integerDigits(amount)
                    + " integer digits but storage supports at most " + MAX_INTEGER_DIGITS
                    + " (balance column is DECIMAL(" + COLUMN_PRECISION + "," + COLUMN_SCALE + "))");
        }
        return amount;
    }
}