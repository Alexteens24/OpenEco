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
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * Velocity-side listener that drives the cross-server sync protocol.
 *
 * <p>Flow when a player switches from Server A → Server B:
 * <ol>
 *   <li>{@link ServerPreConnectEvent}: proxy sends {@code flush <uuid>} to Server A
 *       and <em>suspends the event</em> until Server A replies with
 *       {@code flushed <uuid>} (or the ${@value FlushAckTracker#DEFAULT_TIMEOUT_MS} ms
 *       timeout elapses). If the timeout is hit, the switch still proceeds and the
 *       refresh on the destination server becomes best-effort rather than guaranteed.</li>
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
        java.util.concurrent.CompletableFuture<Void> flushDone = flushAckTracker.register(uuid)
                .thenAccept(outcome -> {
                if (outcome == FlushAckTracker.FlushOutcome.TIMED_OUT) {
                    logger.warn("Timed out waiting for flush ack from {} for player {}. Proceeding with best-effort sync.",
                            currentServer.getServerInfo().getName(), uuid);
                }
            });
        currentServer.sendPluginMessage(CHANNEL, encode("flush " + uuid));
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
     * Receive {@code flushed <uuid>} acknowledgements from backend servers and
     * complete the corresponding pending future so the server-switch can proceed.
     */
    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(CHANNEL)) return;
        if (!(event.getSource() instanceof ServerConnection)) return;

        // Prevent the message from being forwarded to the player's client.
        event.setResult(PluginMessageEvent.ForwardResult.handled());

        String msg = new String(event.getData(), StandardCharsets.UTF_8).trim();
        if (msg.startsWith("flushed ")) {
            try {
                UUID uuid = UUID.fromString(msg.substring(8).trim());
                flushAckTracker.acknowledge(uuid);
                logger.debug("Received flush ack from backend for player {}", uuid);
            } catch (IllegalArgumentException ignored) {
                // Malformed UUID from backend — ignore safely.
            }
        }
    }

    /**
     * When a player fully disconnects from the proxy, ensure a final flush is triggered
     * (guards against a missed {@link ServerPreConnectEvent} during an unexpected disconnect).
     */
    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        event.getPlayer().getCurrentServer().ifPresent(conn -> {
            UUID uuid = event.getPlayer().getUniqueId();
            conn.sendPluginMessage(CHANNEL, encode("flush " + uuid));
            logger.debug("Sent flush-on-disconnect to {} for player {}",
                    conn.getServerInfo().getName(), uuid);
        });
    }

    static byte[] encode(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
