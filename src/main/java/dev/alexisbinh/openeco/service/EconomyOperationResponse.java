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
import java.util.Objects;

/**
 * Internal response object for core economy mutations.
 * This keeps BigDecimal precision in core logic and adapters can convert at the boundary.
 *
 * <p>{@link #failureReason()} is the machine-readable failure classification; adapters must
 * switch on it instead of matching {@link #errorMessage()} text, which is display-only.
 */
public record EconomyOperationResponse(BigDecimal amount,
                                       BigDecimal balance,
                                       ResponseType type,
                                       FailureReason failureReason,
                                       String errorMessage) {

    public enum ResponseType {
        SUCCESS,
        FAILURE,
        NOT_IMPLEMENTED
    }

    public enum FailureReason {
        NONE,
        UNKNOWN_CURRENCY,
        ACCOUNT_NOT_FOUND,
        INVALID_AMOUNT,
        INSUFFICIENT_FUNDS,
        BALANCE_LIMIT,
        FROZEN,
        CANCELLED
    }

    public EconomyOperationResponse {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(balance, "balance");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(failureReason, "failureReason");
        errorMessage = errorMessage == null ? "" : errorMessage;
    }

    public boolean transactionSuccess() {
        return type == ResponseType.SUCCESS;
    }
}
