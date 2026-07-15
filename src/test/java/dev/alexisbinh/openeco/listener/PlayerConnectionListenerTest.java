/*
 * Copyright 2026 alexisbinh
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package dev.alexisbinh.openeco.listener;

import dev.alexisbinh.openeco.Messages;
import dev.alexisbinh.openeco.api.OpenEcoApiException;
import dev.alexisbinh.openeco.service.AccountService;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlayerConnectionListenerTest {

    @Mock private AccountService service;
    @Mock private AsyncPlayerPreLoginEvent preLoginEvent;
    @Mock private PlayerJoinEvent joinEvent;
    @Mock private Player player;
    @Mock private AccountService.PreparedLoginAccount prepared;

    private PlayerConnectionListener listener;

    @BeforeEach
    void setUp() {
        listener = new PlayerConnectionListener(service, new Messages(testConfig()),
                Logger.getLogger("listener-test"), null);
    }

    @Test
    void preLoginDeniesPlayerWhenStoragePreparationFails() {
        UUID accountId = UUID.randomUUID();
        when(preLoginEvent.getUniqueId()).thenReturn(accountId);
        when(preLoginEvent.getName()).thenReturn("Alice");
        when(service.prepareLoginAccount(accountId, "Alice"))
                .thenThrow(new OpenEcoApiException("database unavailable"));

        listener.onPreLogin(preLoginEvent);

        verify(preLoginEvent).disallow(
                org.mockito.ArgumentMatchers.eq(AsyncPlayerPreLoginEvent.Result.KICK_OTHER), any(Component.class));
    }

    @Test
    void joinMarksPreparedAccountOnlineAndReleasesPin() {
        UUID accountId = UUID.randomUUID();
        when(preLoginEvent.getUniqueId()).thenReturn(accountId);
        when(preLoginEvent.getName()).thenReturn("Alice");
        when(service.prepareLoginAccount(accountId, "Alice")).thenReturn(prepared);
        when(prepared.status()).thenReturn(AccountService.LoginAccountStatus.READY);
        when(joinEvent.getPlayer()).thenReturn(player);
        when(player.getUniqueId()).thenReturn(accountId);

        listener.onPreLogin(preLoginEvent);
        listener.onJoin(joinEvent);

        verify(service).markAccountOnline(accountId);
        verify(prepared).close();
        verify(player, never()).sendMessage(any(Component.class));
    }

    @Test
    void joinWarnsWhenLoginKeptThePreviousAccountName() {
        UUID accountId = UUID.randomUUID();
        when(preLoginEvent.getUniqueId()).thenReturn(accountId);
        when(preLoginEvent.getName()).thenReturn("Alice");
        when(service.prepareLoginAccount(accountId, "Alice")).thenReturn(prepared);
        when(prepared.status()).thenReturn(AccountService.LoginAccountStatus.READY_WITH_STALE_NAME);
        when(joinEvent.getPlayer()).thenReturn(player);
        when(player.getUniqueId()).thenReturn(accountId);

        listener.onPreLogin(preLoginEvent);
        listener.onJoin(joinEvent);

        verify(player).sendMessage(any(Component.class));
        verify(prepared).close();
    }

    private static YamlConfiguration testConfig() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("messages.account-sync-failed", "<red>sync failed");
        config.set("messages.login-storage-error", "<red>storage unavailable");
        return config;
    }
}
