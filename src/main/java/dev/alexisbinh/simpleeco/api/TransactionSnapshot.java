package dev.alexisbinh.simpleeco.api;

import java.math.BigDecimal;
import java.util.UUID;

public record TransactionSnapshot(
        TransactionKind kind,
        UUID counterpartId,
        UUID targetId,
        BigDecimal amount,
        BigDecimal balanceBefore,
        BigDecimal balanceAfter,
        long timestamp
) {
}