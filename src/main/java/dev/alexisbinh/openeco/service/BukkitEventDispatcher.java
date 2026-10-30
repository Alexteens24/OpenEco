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

package dev.alexisbinh.openeco.service;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

final class BukkitEventDispatcher implements EventDispatcher {

    private static final long DISPATCH_TIMEOUT_SECONDS = 30L;

    private final JavaPlugin plugin;

    BukkitEventDispatcher(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void dispatch(Event event) {
        if (Bukkit.isPrimaryThread()) {
            Bukkit.getPluginManager().callEvent(event);
            return;
        }

        CompletableFuture<Void> dispatched = new CompletableFuture<>();
        AtomicReference<ScheduledTask> scheduled = new AtomicReference<>();
        scheduled.set(plugin.getServer().getGlobalRegionScheduler().run(plugin, ignored -> {
            if (dispatched.isCancelled()) return;
            try {
                Bukkit.getPluginManager().callEvent(event);
                dispatched.complete(null);
            } catch (Throwable error) {
                dispatched.completeExceptionally(error);
            }
        }));
        await(dispatched, scheduled, "economy event");
    }

    /**
     * Runs the whole operation on the primary thread. Account monitors must only be acquired
     * there: acquiring them off-thread and then blocking for the primary thread to dispatch an
     * event inverts the lock order and freezes the server until the timeout.
     */
    @Override
    public <T> T runOnDispatchThread(Callable<T> task) {
        if (Bukkit.isPrimaryThread()) {
            return call(task);
        }

        CompletableFuture<T> result = new CompletableFuture<>();
        AtomicReference<ScheduledTask> scheduled = new AtomicReference<>();
        scheduled.set(plugin.getServer().getGlobalRegionScheduler().run(plugin, ignored -> {
            // Cancelled means the caller already gave up (timeout/interrupt): never run a
            // money operation late on its behalf.
            if (result.isCancelled()) return;
            try {
                result.complete(task.call());
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        }));

        try {
            return result.get(DISPATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            cancel(result, scheduled);
            throw new IllegalStateException("Interrupted while running an economy operation", error);
        } catch (TimeoutException error) {
            cancel(result, scheduled);
            throw new IllegalStateException("Timed out running an economy operation", error);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error fatalError) {
                throw fatalError;
            }
            throw new IllegalStateException("Economy operation failed", cause);
        }
    }

    private static void await(CompletableFuture<Void> dispatched, AtomicReference<ScheduledTask> scheduled, String description) {
        try {
            dispatched.get(DISPATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            cancel(dispatched, scheduled);
            throw new IllegalStateException("Interrupted while dispatching " + description, error);
        } catch (TimeoutException error) {
            cancel(dispatched, scheduled);
            throw new IllegalStateException("Timed out dispatching " + description, error);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error fatalError) {
                throw fatalError;
            }
            throw new IllegalStateException("Dispatching " + description + " failed", cause);
        }
    }

    private static void cancel(CompletableFuture<?> future, AtomicReference<ScheduledTask> scheduled) {
        ScheduledTask task = scheduled.get();
        if (task != null) {
            try {
                task.cancel();
            } catch (IllegalStateException ignored) {
                // The task already left the scheduler's control; there is nothing to cancel.
            }
        }
        future.cancel(false);
    }

    private static <T> T call(Callable<T> task) {
        try {
            return task.call();
        } catch (RuntimeException | Error error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("Economy operation failed", error);
        }
    }
}
