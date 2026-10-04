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

package dev.alexisbinh.openeco.api;

import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;

/**
 * Outcome of converting an amount of one currency into another on a single account.
 *
 * <p>Both legs either apply together or not at all: there is no window in which one currency has
 * been debited while the other has not been credited.
 *
 * @param status outcome; only {@link Status#SUCCESS} mutated the account
 * @param debited amount taken out of {@code fromCurrencyId}
 * @param credited amount added to {@code toCurrencyId}
 * @param fromBalanceAfter resulting balance of the source currency, {@code null} unless successful
 * @param toBalanceAfter resulting balance of the target currency, {@code null} unless successful
 */
public record ExchangeResult(
        Status status,
        BigDecimal debited,
        BigDecimal credited,
        @Nullable BigDecimal fromBalanceAfter,
        @Nullable BigDecimal toBalanceAfter) {

    public enum Status {
        SUCCESS,
        UNKNOWN_CURRENCY,
        SAME_CURRENCY,
        INVALID_AMOUNT,
        ACCOUNT_NOT_FOUND,
        FROZEN,
        INSUFFICIENT_FUNDS,
        BALANCE_LIMIT,
        CANCELLED
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    public static ExchangeResult failed(Status status, BigDecimal debited, BigDecimal credited) {
        return new ExchangeResult(status, debited, credited, null, null);
    }

    public static ExchangeResult success(BigDecimal debited, BigDecimal credited,
                                  BigDecimal fromBalanceAfter, BigDecimal toBalanceAfter) {
        return new ExchangeResult(Status.SUCCESS, debited, credited, fromBalanceAfter, toBalanceAfter);
    }
}