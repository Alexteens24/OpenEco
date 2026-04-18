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

/**
 * Result of {@link OpenEcoApi#canTransfer(java.util.UUID, java.util.UUID, BigDecimal)}.
 */
public record TransferCheckResult(Status status, BigDecimal amount) {

    public boolean isAllowed() {
        return status == Status.ALLOWED;
    }

    public enum Status {
        ALLOWED,
        UNKNOWN_CURRENCY,
        ACCOUNT_NOT_FOUND,
        INVALID_AMOUNT,
        INSUFFICIENT_FUNDS,
        BALANCE_LIMIT,
        SELF_TRANSFER,
        FROZEN
    }
}
