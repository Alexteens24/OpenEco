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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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

    /**
     * Two concurrent pre-logins for the same UUID (double client, rejoin spam) must not leak a
     * pin. The displaced pin used to be overwritten without being closed, keeping a lease
     * forever and permanently failing cross-server refreshes for the account.
     */
    @Test
    void concurrentPreLoginsCloseEveryPinExactlyOnce() throws Exception {
        UUID accountId = UUID.randomUUID();
        AccountService.PreparedLoginAccount firstPin = mock(AccountService.PreparedLoginAccount.class);
        AccountService.PreparedLoginAccount secondPin = mock(AccountService.PreparedLoginAccount.class);
        AsyncPlayerPreLoginEvent firstEvent = mock(AsyncPlayerPreLoginEvent.class);
        AsyncPlayerPreLoginEvent secondEvent = mock(AsyncPlayerPreLoginEvent.class);
        when(firstEvent.getUniqueId()).thenReturn(accountId);
        when(firstEvent.getName()).thenReturn("Alice");
        when(secondEvent.getUniqueId()).thenReturn(accountId);
        when(secondEvent.getName()).thenReturn("Alice");

        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(service.prepareLoginAccount(accountId, "Alice")).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                firstEntered.countDown();
                releaseFirst.await(5, TimeUnit.SECONDS);
                return firstPin;
            }
            return secondPin;
        });
        when(joinEvent.getPlayer()).thenReturn(player);
        when(player.getUniqueId()).thenReturn(accountId);
        when(firstPin.status()).thenReturn(AccountService.LoginAccountStatus.READY);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> blockedLogin = executor.submit(() -> listener.onPreLogin(firstEvent));
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS), "first pre-login must be blocked in prepare");

            // Second pre-login wins the map; the first one is still inside prepareLoginAccount.
            listener.onPreLogin(secondEvent);
            releaseFirst.countDown();
            blockedLogin.get(5, TimeUnit.SECONDS);

            // Whichever pin ends up in the map, the joined player releases it...
            listener.onJoin(joinEvent);

            // ...and the displaced pin must have been closed by the overwrite.
            verify(firstPin).close();
            verify(secondPin).close();
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    private static YamlConfiguration testConfig() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("messages.account-sync-failed", "<red>sync failed");
        config.set("messages.login-storage-error", "<red>storage unavailable");
        return config;
    }
}
