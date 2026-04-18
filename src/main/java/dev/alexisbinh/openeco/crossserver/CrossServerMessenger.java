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

package dev.alexisbinh.openeco.crossserver;

import dev.alexisbinh.openeco.service.AccountService;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Handles the {@code openeco:sync} plugin messaging channel for cross-server balance
 * synchronisation via a Velocity/BungeeCord proxy.
 *
 * <p>Supported incoming messages (UTF-8 text, sent by the proxy addon):
 * <ul>
 *   <li>{@code flush <uuid>} — immediately write this account to the database and
 *       reply with {@code flushed <uuid>} through the same player's connection.</li>
 *   <li>{@code refresh <uuid>} — re-read this account from the database so the
 *       server has the latest balances after a cross-server transfer.</li>
 * </ul>
 */
public class CrossServerMessenger implements PluginMessageListener {

    public static final String CHANNEL = "openeco:sync";

    private final JavaPlugin plugin;
    private final AccountService service;
    private final Logger log;

    public CrossServerMessenger(JavaPlugin plugin, AccountService service, Logger log) {
        this.plugin = plugin;
        this.service = service;
        this.log = log;
    }

    public void register() {
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, this);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
        log.info("Cross-server plugin messaging channel registered: " + CHANNEL);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel)) return;
        String msg = new String(message, StandardCharsets.UTF_8).trim();
        int space = msg.indexOf(' ');
        if (space < 0) return;

        String cmd = msg.substring(0, space);
        if (!"flush".equals(cmd) && !"refresh".equals(cmd)) {
            return;
        }

        String uuidStr = msg.substring(space + 1).trim();
        UUID uuid;
        try {
            uuid = UUID.fromString(uuidStr);
        } catch (IllegalArgumentException e) {
            return; // malformed UUID — ignore
        }

        UUID playerId = player.getUniqueId();
        if (!playerId.equals(uuid)) {
            log.warning("Ignoring cross-server " + cmd + " request for " + uuid
                    + " from " + playerId + " due to UUID mismatch.");
            return;
        }

        switch (cmd) {
            case "flush" -> {
                UUID id = playerId;
                plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
                    service.flushAccount(id);
                    // Notify proxy that flush is done
                    Player online = plugin.getServer().getPlayer(id);
                    if (online != null && online.isOnline()) {
                        online.sendPluginMessage(plugin, CHANNEL,
                                ("flushed " + id).getBytes(StandardCharsets.UTF_8));
                    }
                });
            }
            case "refresh" -> {
                UUID id = playerId;
                plugin.getServer().getAsyncScheduler().runNow(plugin,
                        task -> service.refreshAccount(id));
            }
            default -> { /* unknown command — ignore */ }
        }
    }
}
