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

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
import static org.mockito.Mockito.never;
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

    private FlushAckTracker.PendingFlush pendingFlush(long token, FlushAckTracker.FlushOutcome outcome) {
        return new FlushAckTracker.PendingFlush(token, CompletableFuture.completedFuture(outcome));
    }

    @Test
    void acknowledgedFlushSendsRefresh() {
        when(flushAckTracker.register(playerId)).thenReturn(pendingFlush(42L, FlushAckTracker.FlushOutcome.ACKNOWLEDGED));

        command.execute(invocation);

        ArgumentCaptor<byte[]> messages = ArgumentCaptor.forClass(byte[].class);
        verify(connection, times(2)).sendPluginMessage(eq(PlayerServerSwitchListener.CHANNEL), messages.capture());
        List<byte[]> capturedMessages = messages.getAllValues();

        assertEquals("flush " + playerId + " 42", new String(capturedMessages.get(0), StandardCharsets.UTF_8));
        assertEquals("refresh " + playerId, new String(capturedMessages.get(1), StandardCharsets.UTF_8));
        verify(source, times(2)).sendMessage(any(Component.class));
        verify(logger).info("Manual sync for {} ({}) completed on {}", "Alice", playerId, "survival");
    }

    @Test
    void timedOutFlushDoesNotSendRefresh() {
        when(flushAckTracker.register(playerId)).thenReturn(pendingFlush(43L, FlushAckTracker.FlushOutcome.TIMED_OUT));

        command.execute(invocation);

        ArgumentCaptor<byte[]> messages = ArgumentCaptor.forClass(byte[].class);
        verify(connection).sendPluginMessage(eq(PlayerServerSwitchListener.CHANNEL), messages.capture());
        assertEquals("flush " + playerId + " 43", new String(messages.getValue(), StandardCharsets.UTF_8));
        verify(source, times(2)).sendMessage(any(Component.class));
        verify(logger).warn("Manual sync for {} ({}) timed out waiting for backend ack from {}",
                "Alice", playerId, "survival");
    }

    /**
     * A backend-reported failure was previously treated as success: the command refreshed and
     * reported "Synced" even though the balance never reached storage.
     */
    @Test
    void failedFlushDoesNotSendRefreshOrReportSuccess() {
        when(flushAckTracker.register(playerId)).thenReturn(pendingFlush(44L, FlushAckTracker.FlushOutcome.FLUSH_FAILED));

        command.execute(invocation);

        ArgumentCaptor<byte[]> messages = ArgumentCaptor.forClass(byte[].class);
        verify(connection).sendPluginMessage(eq(PlayerServerSwitchListener.CHANNEL), messages.capture());
        assertEquals("flush " + playerId + " 44", new String(messages.getValue(), StandardCharsets.UTF_8));
        verify(source, times(2)).sendMessage(any(Component.class));
        verify(logger).error("Manual sync for {} ({}) failed on {}: backend reported a failed flush",
                "Alice", playerId, "survival");
    }

    /** A superseded sync must not report success before the newer flush has completed. */
    @Test
    void supersededSyncDoesNotSendRefresh() {
        when(flushAckTracker.register(playerId)).thenReturn(pendingFlush(45L, FlushAckTracker.FlushOutcome.SUPERSEDED));

        command.execute(invocation);

        ArgumentCaptor<byte[]> messages = ArgumentCaptor.forClass(byte[].class);
        verify(connection).sendPluginMessage(eq(PlayerServerSwitchListener.CHANNEL), messages.capture());
        verify(source, times(2)).sendMessage(any(Component.class));
        verify(logger).warn("Manual sync for {} ({}) was superseded by a newer sync", "Alice", playerId);
    }

    @Test
    void unreachableBackendDoesNotSendRefresh() {
        CompletableFuture<FlushAckTracker.FlushOutcome> pending = new CompletableFuture<>();
        when(flushAckTracker.register(playerId)).thenReturn(new FlushAckTracker.PendingFlush(46L, pending));
        org.mockito.Mockito.doThrow(new IllegalStateException("Not connected to server!"))
                .when(connection).sendPluginMessage(eq(PlayerServerSwitchListener.CHANNEL), any(byte[].class));

        command.execute(invocation);

        verify(flushAckTracker).reportFailure(playerId, 46L);
        verify(logger).error("Manual sync for {} ({}) could not reach {}: {}",
                "Alice", playerId, "survival", "Not connected to server!");
        // The initial "Syncing..." line plus the honest failure — never a success or refresh.
        verify(source, times(2)).sendMessage(any(Component.class));
        verify(source).sendMessage(Component.text(
                "Could not reach survival to save Alice's balance. Nothing was refreshed.",
                NamedTextColor.RED));
    }
}
