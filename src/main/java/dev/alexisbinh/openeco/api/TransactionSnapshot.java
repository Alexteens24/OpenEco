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
import java.util.UUID;

public record TransactionSnapshot(
        TransactionKind kind,
        UUID counterpartId,
        UUID targetId,
        @Nullable String currencyId,
        BigDecimal amount,
        BigDecimal balanceBefore,
        BigDecimal balanceAfter,
        long timestamp,
        @Nullable String source,
        @Nullable String note
) {

        public TransactionSnapshot(
                TransactionKind kind,
                UUID counterpartId,
                UUID targetId,
                BigDecimal amount,
                BigDecimal balanceBefore,
                BigDecimal balanceAfter,
                long timestamp,
                @Nullable String source,
                @Nullable String note
        ) {
                this(kind, counterpartId, targetId, null, amount, balanceBefore, balanceAfter, timestamp, source, note);
        }

        public boolean hasCurrencyId() {
                return currencyId != null;
        }

        public boolean hasSource() {
                return source != null;
        }

        public boolean hasNote() {
                return note != null;
        }

        public boolean hasMetadata() {
                return source != null || note != null;
        }
}