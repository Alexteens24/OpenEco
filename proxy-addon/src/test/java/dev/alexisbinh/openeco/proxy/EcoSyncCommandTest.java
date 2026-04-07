package dev.alexisbinh.openeco.proxy;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EcoSyncCommandTest {

    @Mock
    private ProxyServer proxy;

    @Mock
    private FlushAckTracker flushAckTracker;

    @Mock
    private Logger logger;

    @Mock
    private SimpleCommand.Invocation invocation;

    @Mock
    private CommandSource source;

    @Mock
    private Player player;

    @Mock
    private ServerConnection connection;

    @Mock
    private ServerInfo serverInfo;

    private EcoSyncCommand command;
    private UUID playerId;

    @BeforeEach
    void setUp() {
        command = new EcoSyncCommand(proxy, flushAckTracker, logger);
        playerId = UUID.randomUUID();

        when(invocation.source()).thenReturn(source);
        when(invocation.arguments()).thenReturn(new String[]{"Alice"});
        when(proxy.getPlayer("Alice")).thenReturn(Optional.of(player));
        when(player.getUsername()).thenReturn("Alice");
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getCurrentServer()).thenReturn(Optional.of(connection));
        when(connection.getServerInfo()).thenReturn(serverInfo);
        when(serverInfo.getName()).thenReturn("survival");
    }

    @Test
    void acknowledgedFlushSendsRefresh() {
        when(flushAckTracker.register(playerId)).thenReturn(
                CompletableFuture.completedFuture(FlushAckTracker.FlushOutcome.ACKNOWLEDGED));

        command.execute(invocation);

        ArgumentCaptor<byte[]> messages = ArgumentCaptor.forClass(byte[].class);
        verify(connection, times(2)).sendPluginMessage(eq(PlayerServerSwitchListener.CHANNEL), messages.capture());
        List<String> decoded = messages.getAllValues().stream()
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                .toList();

        assertEquals("flush " + playerId, decoded.get(0));
        assertEquals("refresh " + playerId, decoded.get(1));
        verify(source, times(2)).sendMessage(any(Component.class));
        verify(logger).info("Manual sync for {} ({}) completed on {}", "Alice", playerId, "survival");
    }

    @Test
    void timedOutFlushDoesNotSendRefresh() {
        when(flushAckTracker.register(playerId)).thenReturn(
                CompletableFuture.completedFuture(FlushAckTracker.FlushOutcome.TIMED_OUT));

        command.execute(invocation);

        ArgumentCaptor<byte[]> messages = ArgumentCaptor.forClass(byte[].class);
        verify(connection).sendPluginMessage(eq(PlayerServerSwitchListener.CHANNEL), messages.capture());
        assertEquals("flush " + playerId, new String(messages.getValue(), StandardCharsets.UTF_8));
        verify(source, times(2)).sendMessage(any(Component.class));
        verify(logger).warn("Manual sync for {} ({}) timed out waiting for backend ack from {}",
                "Alice", playerId, "survival");
    }
}