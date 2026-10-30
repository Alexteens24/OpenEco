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
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlayerServerSwitchListenerTest {

    @Mock
    private FlushAckTracker flushAckTracker;

    @Mock
    private Logger logger;

    @Mock
    private Player player;

    @Mock
    private RegisteredServer originalServer;

    @Mock
    private ServerConnection currentConnection;

    @Mock
    private ServerInfo currentServerInfo;

    private PlayerServerSwitchListener listener;
    private UUID playerId;

    @BeforeEach
    void setUp() {
        listener = new PlayerServerSwitchListener(flushAckTracker, logger);
        playerId = UUID.randomUUID();

        lenient().when(player.getUniqueId()).thenReturn(playerId);
        lenient().when(player.getCurrentServer()).thenReturn(Optional.of(currentConnection));
        lenient().when(currentConnection.getServerInfo()).thenReturn(currentServerInfo);
        lenient().when(currentServerInfo.getName()).thenReturn("survival");
    }

    private static FlushAckTracker.PendingFlush pendingFlush(long token, FlushAckTracker.FlushOutcome outcome) {
        return new FlushAckTracker.PendingFlush(token, CompletableFuture.completedFuture(outcome));
    }

    @Test
    void preConnectAllowedWithCurrentServerRegistersFlushAndSuspendsEvent() {
        when(flushAckTracker.register(playerId)).thenReturn(pendingFlush(7L, FlushAckTracker.FlushOutcome.ACKNOWLEDGED));

        ServerPreConnectEvent event = new ServerPreConnectEvent(player, originalServer);

        EventTask task = listener.onServerPreConnect(event);

        assertNotNull(task);
        verify(flushAckTracker).register(playerId);

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(currentConnection).sendPluginMessage(eq(PlayerServerSwitchListener.CHANNEL), payload.capture());
        assertEquals("flush " + playerId + " 7", new String(payload.getValue(), StandardCharsets.UTF_8));
        verify(logger).debug("Sent flush to {} for player {} — waiting for ack", "survival", playerId);
    }

    @Test
    void preConnectTimeoutCancelsSwitchAndSendsErrorMessage() {
        when(flushAckTracker.register(playerId)).thenReturn(pendingFlush(8L, FlushAckTracker.FlushOutcome.TIMED_OUT));

        ServerPreConnectEvent event = new ServerPreConnectEvent(player, originalServer);

        EventTask task = listener.onServerPreConnect(event);

        assertNotNull(task);
        verify(logger).warn("Timed out waiting for flush ack from {} for player {}. Cancelling server switch to prevent stale data.",
                "survival", playerId);
        verify(currentConnection).sendPluginMessage(eq(PlayerServerSwitchListener.CHANNEL), org.mockito.ArgumentMatchers.any(byte[].class));
        assertEquals(ServerPreConnectEvent.ServerResult.denied(), event.getResult());
        verify(player).sendMessage(Component.text(
                "Failed to save your economy data in time. Please try switching servers again.",
                NamedTextColor.RED));
    }

    /**
     * If the origin connection is already dead the flush can never happen. The switch must be
     * denied instead of proceeding and letting the destination load a stale balance.
     */
    @Test
    void preConnectDeniesSwitchWhenFlushCannotBeSent() {
        CompletableFuture<FlushAckTracker.FlushOutcome> pending = new CompletableFuture<>();
        when(flushAckTracker.register(playerId)).thenReturn(new FlushAckTracker.PendingFlush(9L, pending));
        doAnswer(invocation -> {
            pending.complete(FlushAckTracker.FlushOutcome.FLUSH_FAILED);
            return null;
        }).when(flushAckTracker).reportFailure(playerId, 9L);
        doThrow(new IllegalStateException("Not connected to server!"))
                .when(currentConnection).sendPluginMessage(eq(PlayerServerSwitchListener.CHANNEL), any(byte[].class));

        ServerPreConnectEvent event = new ServerPreConnectEvent(player, originalServer);

        EventTask task = listener.onServerPreConnect(event);

        assertNull(task, "the event must not be suspended once the flush failed to send");
        verify(flushAckTracker).reportFailure(playerId, 9L);
        assertEquals(ServerPreConnectEvent.ServerResult.denied(), event.getResult());
        verify(player).sendMessage(Component.text(
                "Your balance could not be saved right now, so the server switch was cancelled. Please try again.",
                NamedTextColor.RED));
    }

    @Test
    void preConnectDeniedDoesNothing() {
        ServerPreConnectEvent event = new ServerPreConnectEvent(player, originalServer);
        event.setResult(ServerPreConnectEvent.ServerResult.denied());

        EventTask task = listener.onServerPreConnect(event);

        assertNull(task);
        verify(flushAckTracker, never()).register(playerId);
        verify(currentConnection, never()).sendPluginMessage(eq(PlayerServerSwitchListener.CHANNEL), org.mockito.ArgumentMatchers.any(byte[].class));
    }

    @Test
    void preConnectWithoutCurrentServerDoesNothing() {
        when(player.getCurrentServer()).thenReturn(Optional.empty());
        ServerPreConnectEvent event = new ServerPreConnectEvent(player, originalServer, null);

        EventTask task = listener.onServerPreConnect(event);

        assertNull(task);
        verify(flushAckTracker, never()).register(playerId);
        verify(currentConnection, never()).sendPluginMessage(eq(PlayerServerSwitchListener.CHANNEL), any(byte[].class));
    }

    // --- onPluginMessage tests ---

    @Test
    void tokenizedFlushAckIsForwardedToTracker() {
        PluginMessageEvent event = mock(PluginMessageEvent.class);
        when(event.getIdentifier()).thenReturn(PlayerServerSwitchListener.CHANNEL);
        when(event.getSource()).thenReturn(currentConnection);
        when(event.getData()).thenReturn(("flushed " + playerId + " 42").getBytes(StandardCharsets.UTF_8));

        listener.onPluginMessage(event);

        verify(event).setResult(PluginMessageEvent.ForwardResult.handled());
        verify(flushAckTracker).acknowledge(playerId, 42L);
    }

    @Test
    void tokenizedFlushFailureIsForwardedToTracker() {
        PluginMessageEvent event = mock(PluginMessageEvent.class);
        when(event.getIdentifier()).thenReturn(PlayerServerSwitchListener.CHANNEL);
        when(event.getSource()).thenReturn(currentConnection);
        when(event.getData()).thenReturn(("flushfailed " + playerId + " 43").getBytes(StandardCharsets.UTF_8));

        listener.onPluginMessage(event);

        verify(flushAckTracker).reportFailure(playerId, 43L);
    }

    @Test
    void tokenlessAckFromAnOlderBackendIsStillAccepted() {
        PluginMessageEvent event = mock(PluginMessageEvent.class);
        when(event.getIdentifier()).thenReturn(PlayerServerSwitchListener.CHANNEL);
        when(event.getSource()).thenReturn(currentConnection);
        when(event.getData()).thenReturn(("flushed " + playerId).getBytes(StandardCharsets.UTF_8));

        listener.onPluginMessage(event);

        verify(flushAckTracker).acknowledge(playerId);
    }

    @Test
    void malformedAckIsIgnored() {
        PluginMessageEvent event = mock(PluginMessageEvent.class);
        when(event.getIdentifier()).thenReturn(PlayerServerSwitchListener.CHANNEL);
        when(event.getSource()).thenReturn(currentConnection);
        when(event.getData()).thenReturn("flushed not-a-uuid 42".getBytes(StandardCharsets.UTF_8));

        listener.onPluginMessage(event);

        verify(flushAckTracker, never()).acknowledge(any(UUID.class), org.mockito.ArgumentMatchers.anyLong());
        verify(flushAckTracker, never()).acknowledge(any(UUID.class));
    }

    // --- onDisconnect tests ---

    @Test
    void disconnectSendsFlushToCurrentServer() {
        DisconnectEvent event = new DisconnectEvent(player, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN);

        listener.onDisconnect(event);

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(currentConnection).sendPluginMessage(eq(PlayerServerSwitchListener.CHANNEL), payload.capture());
        assertEquals("flush " + playerId, new String(payload.getValue(), StandardCharsets.UTF_8));
        verify(logger).debug("Sent flush-on-disconnect to {} for player {}", "survival", playerId);
    }

    @Test
    void disconnectDuringProxyShutdownDoesNotThrow() {
        // Simulate the race condition from issue #37: proxy shutdown closes the backend
        // connection before DisconnectEvent fires, causing sendPluginMessage to throw.
        doThrow(new IllegalStateException("Not connected to server!"))
                .when(currentConnection).sendPluginMessage(eq(PlayerServerSwitchListener.CHANNEL), any(byte[].class));

        DisconnectEvent event = new DisconnectEvent(player, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN);

        // Must not propagate — the exception is swallowed and logged at DEBUG.
        listener.onDisconnect(event);

        verify(logger).debug(
                "Skipped flush-on-disconnect for player {} — connection to {} already closed: {}",
                playerId, "survival", "Not connected to server!");
    }

    @Test
    void disconnectWithNoCurrentServerDoesNothing() {
        when(player.getCurrentServer()).thenReturn(Optional.empty());
        DisconnectEvent event = new DisconnectEvent(player, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN);

        listener.onDisconnect(event);

        verify(currentConnection, never()).sendPluginMessage(eq(PlayerServerSwitchListener.CHANNEL), any(byte[].class));
    }
}
