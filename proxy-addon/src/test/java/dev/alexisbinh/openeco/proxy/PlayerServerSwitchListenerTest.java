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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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

    @Test
    void preConnectAllowedWithCurrentServerRegistersFlushAndSuspendsEvent() {
        when(flushAckTracker.register(playerId)).thenReturn(
                CompletableFuture.completedFuture(FlushAckTracker.FlushOutcome.ACKNOWLEDGED));

        ServerPreConnectEvent event = new ServerPreConnectEvent(player, originalServer);

        EventTask task = listener.onServerPreConnect(event);

        assertNotNull(task);
        verify(flushAckTracker).register(playerId);

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(currentConnection).sendPluginMessage(eq(PlayerServerSwitchListener.CHANNEL), payload.capture());
        assertEquals("flush " + playerId, new String(payload.getValue(), StandardCharsets.UTF_8));
        verify(logger).debug("Sent flush to {} for player {} — waiting for ack", "survival", playerId);
    }

    @Test
    void preConnectTimeoutLogsBestEffortWarning() {
        when(flushAckTracker.register(playerId)).thenReturn(
                CompletableFuture.completedFuture(FlushAckTracker.FlushOutcome.TIMED_OUT));

        ServerPreConnectEvent event = new ServerPreConnectEvent(player, originalServer);

        EventTask task = listener.onServerPreConnect(event);

        assertNotNull(task);
        verify(logger).warn("Timed out waiting for flush ack from {} for player {}. Proceeding with best-effort sync.",
                "survival", playerId);
        verify(currentConnection).sendPluginMessage(eq(PlayerServerSwitchListener.CHANNEL), org.mockito.ArgumentMatchers.any(byte[].class));
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
        verify(currentConnection, never()).sendPluginMessage(eq(PlayerServerSwitchListener.CHANNEL), org.mockito.ArgumentMatchers.any(byte[].class));
    }
}