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

import java.math.BigDecimal;

public record TransferResult(Status status, BigDecimal sent, BigDecimal received, BigDecimal tax, long cooldownRemainingMs) {

    public enum Status {
        SUCCESS,
        UNKNOWN_CURRENCY,
        COOLDOWN,
        INSUFFICIENT_FUNDS,
        ACCOUNT_NOT_FOUND,
        BALANCE_LIMIT,
        CANCELLED,
        TOO_LOW,
        INVALID_AMOUNT,
        SELF_TRANSFER,
        FROZEN
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }
}