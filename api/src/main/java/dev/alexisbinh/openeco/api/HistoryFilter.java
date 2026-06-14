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

/**
 * Optional filter for {@link OpenEcoApi#getHistory(java.util.UUID, int, int, HistoryFilter)}.
 *
 * <p>Pass {@link #NONE} (or {@code null} for any nullable parameter) to apply no filtering.
 * Use {@link #builder()} for a fluent construction experience.</p>
 */
public record HistoryFilter(
        @Nullable TransactionKind kind,
        long fromMs,
        long toMs,
        @Nullable String currencyId
) {

    /** A filter that applies no restrictions — equivalent to the unfiltered history. */
    public static final HistoryFilter NONE = new HistoryFilter(null, 0L, Long.MAX_VALUE, null);

    public HistoryFilter(@Nullable TransactionKind kind, long fromMs, long toMs) {
        this(kind, fromMs, toMs, null);
    }

    public HistoryFilter {
        if (fromMs < 0) throw new IllegalArgumentException("fromMs must be >= 0");
        if (toMs < 0) throw new IllegalArgumentException("toMs must be >= 0");
        if (fromMs > toMs) throw new IllegalArgumentException("fromMs must be <= toMs");
        if (currencyId != null && currencyId.isBlank()) throw new IllegalArgumentException("currencyId must not be blank");
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private @Nullable TransactionKind kind = null;
        private long fromMs = 0L;
        private long toMs = Long.MAX_VALUE;
        private @Nullable String currencyId = null;

        private Builder() {}

        public Builder kind(TransactionKind kind) {
            this.kind = kind;
            return this;
        }

        public Builder fromMs(long fromMs) {
            this.fromMs = fromMs;
            return this;
        }

        public Builder toMs(long toMs) {
            this.toMs = toMs;
            return this;
        }

        public Builder currencyId(String currencyId) {
            this.currencyId = currencyId;
            return this;
        }

        public HistoryFilter build() {
            return new HistoryFilter(kind, fromMs, toMs, currencyId);
        }
    }
}
