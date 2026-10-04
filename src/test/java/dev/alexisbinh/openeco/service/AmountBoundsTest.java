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

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AmountBoundsTest {

    /**
     * DECIMAL(30,8) always reserves eight digits for the fractional part, so the integer
     * budget is fixed at 22 regardless of how a currency is configured.
     */
    @Test
    void columnBudgetIsFixedAtTwentyTwoIntegerDigits() {
        assertAll(
                () -> assertEquals(30, AmountBounds.COLUMN_PRECISION),
                () -> assertEquals(8, AmountBounds.COLUMN_SCALE),
                () -> assertEquals(22, AmountBounds.MAX_INTEGER_DIGITS));
    }

    @Test
    void countsIntegerDigitsAfterRoundingToColumnScale() {
        assertAll(
                digits("0", 0),
                digits("0.01", 0),
                digits("500.00", 3),
                digits("0.9999999999", 1),
                digits("99999999999999999999", 20),
                digits("1234567890123456789012", 22),
                digits("1E+18", 19),
                digits("1E+19", 20),
                digits("1E+29", 30),
                digits("12345678901234567890123", 23),
                digits("123456789012345678901234", 24));
    }

    @Test
    void acceptsAmountsThatFitTheColumn() {
        for (String raw : new String[]{
                "0", "500.00", "0.9999999999", "99999999999999999999", "1234567890123456789012", "1E+18"}) {
            assertTrue(AmountBounds.fitsColumn(new BigDecimal(raw)), raw + " should fit");
        }
    }

    @Test
    void rejectsAmountsThatCannotBePersisted() {
        for (String raw : new String[]{
                "12345678901234567890123",
                "123456789012345678901234",
                "1E+29",
                "999999999999999999999999999999"}) {
            assertFalse(AmountBounds.fitsColumn(new BigDecimal(raw)), raw + " should not fit");
        }
    }

    @Test
    void requirePersistableReturnsTheSameInstanceWhenValid() {
        BigDecimal amount = new BigDecimal("500.00");
        assertSame(amount, AmountBounds.requirePersistable(amount));
    }

    @Test
    void requirePersistableExplainsTheLimit() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AmountBounds.requirePersistable(new BigDecimal("12345678901234567890123")));
        assertAll(
                () -> assertTrue(error.getMessage().contains("23 integer digits"), error.getMessage()),
                () -> assertTrue(error.getMessage().contains("at most 22"), error.getMessage()));
    }

    /**
     * A value sitting exactly on the limit must round-trip: the earlier repro showed the
     * driver rejecting the 23 digit value, so the 22 digit boundary is the real contract.
     */
    @Test
    void boundaryValueIsAcceptedAndTheNextDigitIsNot() {
        assertAll(
                () -> assertTrue(AmountBounds.fitsColumn(new BigDecimal("1234567890123456789012"))),
                () -> assertFalse(AmountBounds.fitsColumn(new BigDecimal("12345678901234567890123"))));
    }

    private static org.junit.jupiter.api.function.Executable digits(String raw, int expected) {
        BigDecimal amount = new BigDecimal(raw);
        return () -> assertEquals(expected, AmountBounds.integerDigits(amount),
                () -> raw + " should have " + expected + " integer digits");
    }
}