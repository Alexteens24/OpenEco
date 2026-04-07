package dev.alexisbinh.openeco.proxy;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.util.List;
import java.util.UUID;

/**
 * {@code /ecosync <player>} — admin command on the Velocity proxy to manually
 * force a flush-then-refresh cycle for an online player.
 *
 * <p>Useful after direct database edits or when debugging a suspected stale balance.
 *
 * <p>Permission: {@code openeco.admin.sync} (op-equivalent).
 */
public class EcoSyncCommand implements SimpleCommand {

    private final ProxyServer proxy;
    private final FlushAckTracker flushAckTracker;
    private final Logger logger;

    public EcoSyncCommand(ProxyServer proxy, FlushAckTracker flushAckTracker, Logger logger) {
        this.proxy = proxy;
        this.flushAckTracker = flushAckTracker;
        this.logger = logger;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        if (args.length < 1) {
            source.sendMessage(Component.text(
                    "Usage: /ecosync <player>", NamedTextColor.YELLOW));
            return;
        }

        String targetName = args[0];
        proxy.getPlayer(targetName).ifPresentOrElse(
                player -> syncPlayer(source, player),
                () -> source.sendMessage(Component.text(
                        "Player not found or not online: " + targetName, NamedTextColor.RED)));
    }

    private void syncPlayer(CommandSource source, Player player) {
        player.getCurrentServer().ifPresentOrElse(conn -> {
            UUID uuid = player.getUniqueId();
            String serverName = conn.getServerInfo().getName();

            source.sendMessage(Component.text(
                    "Syncing " + player.getUsername() + " on " + serverName + "...",
                    NamedTextColor.GRAY));

            java.util.concurrent.CompletableFuture<FlushAckTracker.FlushOutcome> ackFuture = flushAckTracker.register(uuid);
            conn.sendPluginMessage(PlayerServerSwitchListener.CHANNEL,
                    PlayerServerSwitchListener.encode("flush " + uuid));

            ackFuture.thenAccept(outcome -> {
                if (outcome == FlushAckTracker.FlushOutcome.TIMED_OUT) {
                    source.sendMessage(Component.text(
                            "Timed out waiting for a flush acknowledgement from " + serverName
                            + ". No automatic refresh was sent.",
                            NamedTextColor.YELLOW));
                    logger.warn("Manual sync for {} ({}) timed out waiting for backend ack from {}",
                            player.getUsername(), uuid, serverName);
                    return;
                }

                // Re-read current server — player could have moved during flush
                player.getCurrentServer().ifPresent(liveConn -> {
                    liveConn.sendPluginMessage(PlayerServerSwitchListener.CHANNEL,
                            PlayerServerSwitchListener.encode("refresh " + uuid));
                    source.sendMessage(Component.text(
                            "Synced " + player.getUsername() + " on "
                            + liveConn.getServerInfo().getName() + ".",
                            NamedTextColor.GREEN));
                    logger.info("Manual sync for {} ({}) completed on {}",
                            player.getUsername(), uuid, liveConn.getServerInfo().getName());
                });
            });
        }, () -> source.sendMessage(Component.text(
                player.getUsername() + " is not connected to any backend server.",
                NamedTextColor.RED)));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (invocation.arguments().length != 1) return List.of();
        String prefix = invocation.arguments()[0].toLowerCase();
        return proxy.getAllPlayers().stream()
                .map(Player::getUsername)
                .filter(name -> name.toLowerCase().startsWith(prefix))
                .sorted()
                .toList();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("openeco.admin.sync");
    }
}
