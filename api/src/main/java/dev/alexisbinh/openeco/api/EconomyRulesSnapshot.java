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
 * Immutable snapshot of the current operational rules exposed by openeco.
 */
public record EconomyRulesSnapshot(
        CurrencyInfo currency,
        long payCooldownMs,
        BigDecimal payTaxRate,
        @Nullable BigDecimal payMinAmount,
        long balTopCacheTtlMs,
        int historyRetentionDays
) {

    public boolean hasPayMinimum() {
        return payMinAmount != null;
    }

    /**
     * Background leaderboard refresh interval. This is the semantic alias for
     * {@link #balTopCacheTtlMs()}, whose original accessor remains for binary compatibility.
     *
     * @return refresh interval in milliseconds
     */
    public long balTopRefreshIntervalMs() {
        return balTopCacheTtlMs;
    }

    public boolean keepsHistoryForever() {
        return historyRetentionDays < 0;
    }
}
