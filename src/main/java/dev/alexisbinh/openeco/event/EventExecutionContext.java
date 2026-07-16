/*
 * Copyright 2026 alexisbinh
 * Licensed under the Apache License, Version 2.0.
 */
package dev.alexisbinh.openeco.event;

import java.util.function.Supplier;

/** Internal marker used so async API calls emit Bukkit events with async semantics. */
public final class EventExecutionContext {
    private static final ThreadLocal<Boolean> ASYNC = ThreadLocal.withInitial(() -> false);

    private EventExecutionContext() {}

    public static boolean isAsync() {
        return ASYNC.get();
    }

    public static <T> T callAsync(Supplier<T> action) {
        boolean previous = ASYNC.get();
        ASYNC.set(true);
        try {
            return action.get();
        } finally {
            ASYNC.set(previous);
        }
    }
}
