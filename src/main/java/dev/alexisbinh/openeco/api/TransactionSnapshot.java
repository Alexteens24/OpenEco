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