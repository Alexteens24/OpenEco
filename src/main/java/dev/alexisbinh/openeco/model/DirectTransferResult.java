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

package dev.alexisbinh.openeco.model;

import java.math.BigDecimal;

/** Result of an atomic peer transfer without pay cooldown, tax, or cancellable events. */
public record DirectTransferResult(
        Status status,
        BigDecimal amount,
        BigDecimal fromBalance,
        BigDecimal toBalance,
        String message) {

    public enum Status {
        SUCCESS,
        UNKNOWN_CURRENCY,
        INVALID_AMOUNT,
        SELF_TRANSFER,
        ACCOUNT_NOT_FOUND,
        FROZEN,
        INSUFFICIENT_FUNDS,
        BALANCE_LIMIT,
        STORAGE_ERROR,
        POLICY_REJECTED
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    public static DirectTransferResult success(BigDecimal amount, BigDecimal fromBalance, BigDecimal toBalance) {
        return new DirectTransferResult(Status.SUCCESS, amount, fromBalance, toBalance, "");
    }

    public static DirectTransferResult failure(Status status, BigDecimal amount, String message) {
        return new DirectTransferResult(status, amount, BigDecimal.ZERO, BigDecimal.ZERO, message);
    }
}
