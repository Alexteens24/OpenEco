package dev.alexisbinh.simpleeco.api;

import java.math.BigDecimal;
import java.util.UUID;

public record AccountSnapshot(
        UUID id,
        String lastKnownName,
        BigDecimal balance,
        long createdAt,
        long updatedAt
) {
}