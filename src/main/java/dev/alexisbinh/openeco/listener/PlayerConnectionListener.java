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

package dev.alexisbinh.openeco.listener;

import dev.alexisbinh.openeco.Messages;
import dev.alexisbinh.openeco.service.AccountService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class PlayerConnectionListener implements Listener {

    private final AccountService service;
    private final Messages messages;
    private final Logger log;
    private final JavaPlugin plugin;
    private final ConcurrentHashMap<UUID, AccountService.PreparedLoginAccount> loginPins = new ConcurrentHashMap<>();

    public PlayerConnectionListener(AccountService service, Messages messages, Logger log, JavaPlugin plugin) {
        this.service = service;
        this.messages = messages;
        this.log = log;
        this.plugin = plugin;
    }

    /** Performs all storage-backed identity work before the player is admitted. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        UUID id = event.getUniqueId();
        AccountService.PreparedLoginAccount previous = loginPins.remove(id);
        if (previous != null) previous.close();
        try {
            AccountService.PreparedLoginAccount prepared = service.prepareLoginAccount(id, event.getName());
            loginPins.put(id, prepared);
            if (plugin != null) {
                plugin.getServer().getAsyncScheduler().runDelayed(plugin, ignored -> {
                    if (loginPins.remove(id, prepared)) prepared.close();
                }, 30L, TimeUnit.SECONDS);
            }
        } catch (RuntimeException error) {
            AccountService.PreparedLoginAccount prepared = loginPins.remove(id);
            if (prepared != null) prepared.close();
            log.severe("Denying login for " + event.getName() + " (" + id
                    + ") because the economy account could not be prepared: " + error.getMessage());
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, messages.get("login-storage-error"));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        service.markAccountOnline(uuid);
        AccountService.PreparedLoginAccount prepared = loginPins.remove(uuid);
        if (prepared != null) {
            if (prepared.status() == AccountService.LoginAccountStatus.READY_WITH_STALE_NAME) {
                messages.send(event.getPlayer(), "account-sync-failed");
            }
            prepared.close();
        }
    }

    /** Cross-server: flush account to DB when the player disconnects (before joining another server). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        AccountService.PreparedLoginAccount prepared = loginPins.remove(uuid);
        if (prepared != null) prepared.close();
        service.markAccountOffline(uuid);
        if (service.isCrossServerEnabled() || service.isLazyAccountModeEnabled()) {
            plugin.getServer().getAsyncScheduler().runNow(plugin, task -> service.flushAccount(uuid));
        }
    }
}
