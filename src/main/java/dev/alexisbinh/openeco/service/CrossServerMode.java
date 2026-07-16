/*
 * Copyright 2026 alexisbinh
 * Licensed under the Apache License, Version 2.0.
 */
package dev.alexisbinh.openeco.service;

import java.util.Locale;

public enum CrossServerMode {
    HANDOFF,
    MULTI_WRITER;

    public static CrossServerMode fromConfig(String value) {
        String normalized = value == null ? "multi-writer"
                : value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return switch (normalized) {
            case "handoff" -> HANDOFF;
            case "multi-writer", "multiwriter" -> MULTI_WRITER;
            default -> throw new IllegalArgumentException(
                    "cross-server.mode must be handoff or multi-writer (was '" + value + "')");
        };
    }
}
