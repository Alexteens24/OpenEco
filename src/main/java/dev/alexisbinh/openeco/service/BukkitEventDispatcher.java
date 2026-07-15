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

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class BukkitEventDispatcher implements EventDispatcher {

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
        plugin.getServer().getGlobalRegionScheduler().run(plugin, ignored -> {
            try {
                Bukkit.getPluginManager().callEvent(event);
                dispatched.complete(null);
            } catch (Throwable error) {
                dispatched.completeExceptionally(error);
            }
        });
        try {
            dispatched.get(30L, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while dispatching economy event", error);
        } catch (TimeoutException error) {
            throw new IllegalStateException("Timed out dispatching economy event", error);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtimeException) throw runtimeException;
            if (cause instanceof Error fatalError) throw fatalError;
            throw new IllegalStateException("Economy event dispatch failed", cause);
        }
    }
}
