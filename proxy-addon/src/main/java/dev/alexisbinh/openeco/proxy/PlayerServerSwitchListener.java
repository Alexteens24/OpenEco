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

package dev.alexisbinh.openeco.proxy;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * Velocity-side listener that drives the cross-server sync protocol.
 *
 * <p>Flow when a player switches from Server A → Server B:
 * <ol>
 *   <li>{@link ServerPreConnectEvent}: proxy sends {@code flush <uuid> <token>} to Server A
 *       and <em>suspends the event</em> until Server A replies with
 *       {@code flushed <uuid> <token>} (or the {@link FlushAckTracker} timeout elapses).
 *       On timeout, a reported flush failure, or a dead origin connection the switch is
 *       <em>denied</em>, so the destination can never load a stale balance.</li>
 *   <li>{@link ServerConnectedEvent}: proxy sends {@code refresh <uuid>} to Server B
 *       so it discards its cached balance and reads the authoritative value from DB.</li>
 * </ol>
 *
 * <p>{@link DisconnectEvent} also triggers a fire-and-forget flush so balances are
 * always persisted when a player fully disconnects from the network.
 */
public class PlayerServerSwitchListener {

    static final ChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from("openeco:sync");

    private final FlushAckTracker flushAckTracker;
    private final Logger logger;

    public PlayerServerSwitchListener(FlushAckTracker flushAckTracker, Logger logger) {
        this.flushAckTracker = flushAckTracker;
        this.logger = logger;
    }

    /**
     * Before the player leaves the current server, tell that server to flush the
     * account and suspend the event until the flush is acknowledged (or times out).
     */
    @Subscribe
    public EventTask onServerPreConnect(ServerPreConnectEvent event) {
        if (!event.getResult().isAllowed()) return null;
        Optional<ServerConnection> current = event.getPlayer().getCurrentServer();
        if (current.isEmpty()) return null; // initial connection — nothing to flush

        ServerConnection currentServer = current.get();
        UUID uuid = event.getPlayer().getUniqueId();
        String originServer = currentServer.getServerInfo().getName();
        FlushAckTracker.PendingFlush pendingFlush = flushAckTracker.register(uuid);
        java.util.concurrent.CompletableFuture<Void> flushDone = pendingFlush.future()
                .thenAccept(outcome -> {
                // Only ACKNOWLEDGED means the balance is durable. Anything else must not let the
                // player through, because the destination server would load a stale balance.
                // SUPERSEDED is the exception: that flush was replaced by a newer one, so the
                // event it belonged to is already finished and must not be touched.
                if (outcome == FlushAckTracker.FlushOutcome.SUPERSEDED) {
                    return;
                }
                if (outcome == FlushAckTracker.FlushOutcome.TIMED_OUT) {
                    logger.warn("Timed out waiting for flush ack from {} for player {}. Cancelling server switch to prevent stale data.",
                            originServer, uuid);
                    deny(event, "Failed to save your economy data in time. Please try switching servers again.");
                } else if (outcome == FlushAckTracker.FlushOutcome.FLUSH_FAILED) {
                    logger.error("Server {} reported a failed balance flush for player {}. Cancelling server switch.",
                            originServer, uuid);
                    deny(event, "Your balance could not be saved right now, so the server switch was cancelled. Please try again.");
                }
            });
        try {
            currentServer.sendPluginMessage(CHANNEL, encode("flush " + uuid + " " + pendingFlush.token()));
        } catch (RuntimeException error) {
            // The connection to the origin server is already dead. Fail closed: without a flush
            // the destination would load a stale balance. reportFailure also resolves the
            // pending wait, which denies the switch through the handler above.
            logger.error("Failed to send flush to {} for player {}: {}",
                    originServer, uuid, error.getMessage());
            flushAckTracker.reportFailure(uuid, pendingFlush.token());
            return null;
        }
        logger.debug("Sent flush to {} for player {} — waiting for ack",
                currentServer.getServerInfo().getName(), uuid);

        // Suspend the Velocity event until the ack arrives or the timeout downgrades this to best-effort.
        return EventTask.resumeWhenComplete(flushDone);
    }

    /**
     * After the player has arrived on the new server, tell it to refresh the account
     * from the database (which now contains the flushed balance).
     */
    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        event.getServer().sendPluginMessage(CHANNEL, encode("refresh " + uuid));
        logger.debug("Sent refresh to {} for player {}",
                event.getServer().getServerInfo().getName(), uuid);
    }

    /**
     * Receive {@code flushed <uuid>} and {@code flushfailed <uuid>} acknowledgements from
     * backend servers and complete the corresponding pending future so the server-switch can
     * proceed or be denied.
     */
    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(CHANNEL)) return;
        // Block all messages on this channel regardless of source — prevents a modded
        // client from injecting flush/refresh commands directly to the backend.
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection source)) return;

        String msg = new String(event.getData(), StandardCharsets.UTF_8).trim();
        boolean failed = msg.startsWith("flushfailed ");
        if (!failed && !msg.startsWith("flushed ")) return;

        String[] parts = msg.substring(failed ? "flushfailed ".length() : "flushed ".length())
                .trim().split("\\s+", 2);
        try {
            UUID uuid = UUID.fromString(parts[0]);
            if (parts.length >= 2) {
                long token = Long.parseLong(parts[1]);
                if (failed) {
                    flushAckTracker.reportFailure(uuid, token);
                    logger.debug("Received flush failure from backend {} for player {} (token {})",
                            source.getServerInfo().getName(), uuid, token);
                } else {
                    flushAckTracker.acknowledge(uuid, token);
                    logger.debug("Received flush ack from backend {} for player {} (token {})",
                            source.getServerInfo().getName(), uuid, token);
                }
            } else if (failed) {
                // Tokenless ack from an older backend: keep compatibility, accept it.
                flushAckTracker.reportFailure(uuid);
                logger.debug("Received tokenless flush failure from backend {} for player {}",
                        source.getServerInfo().getName(), uuid);
            } else {
                flushAckTracker.acknowledge(uuid);
                logger.debug("Received tokenless flush ack from backend {} for player {}",
                        source.getServerInfo().getName(), uuid);
            }
        } catch (IllegalArgumentException ignored) {
            // Malformed UUID or token from backend — ignore safely.
        }
    }

    private static void deny(ServerPreConnectEvent event, String reason) {
        event.setResult(ServerPreConnectEvent.ServerResult.denied());
        event.getPlayer().sendMessage(Component.text(reason, NamedTextColor.RED));
    }

    /**
     * When a player fully disconnects from the proxy, ensure a final flush is triggered
     * (guards against a missed {@link ServerPreConnectEvent} during an unexpected disconnect).
     *
     * <p>During a proxy shutdown Velocity fires {@link DisconnectEvent} for every online player
     * <em>after</em> the backend connections have already been closed, so
     * {@link ServerConnection#sendPluginMessage} will throw {@link IllegalStateException}.
     * We catch that specific exception and log at {@code DEBUG} level — the backend server
     * will recover on the player's next login via the DB-authoritative read.</p>
     */
    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        event.getPlayer().getCurrentServer().ifPresent(conn -> {
            UUID uuid = event.getPlayer().getUniqueId();
            try {
                conn.sendPluginMessage(CHANNEL, encode("flush " + uuid));
                logger.debug("Sent flush-on-disconnect to {} for player {}",
                        conn.getServerInfo().getName(), uuid);
            } catch (IllegalStateException e) {
                // Backend connection already closed (e.g. proxy is shutting down).
                // The player's balance was already persisted by the last periodic flush
                // or will be reconciled from the DB on their next login.
                logger.debug("Skipped flush-on-disconnect for player {} — connection to {} already closed: {}",
                        uuid, conn.getServerInfo().getName(), e.getMessage());
            }
        });
    }

    static byte[] encode(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
