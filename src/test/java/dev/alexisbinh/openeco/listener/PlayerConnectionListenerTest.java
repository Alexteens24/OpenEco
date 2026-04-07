package dev.alexisbinh.openeco.listener;

import dev.alexisbinh.openeco.Messages;
import dev.alexisbinh.openeco.model.AccountRecord;
import dev.alexisbinh.openeco.service.AccountService;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlayerConnectionListenerTest {

    @Mock
    private AccountService service;

    @Mock
    private PlayerJoinEvent event;

    @Mock
    private Player player;

    private PlayerConnectionListener listener;

    @BeforeEach
    void setUp() {
        listener = new PlayerConnectionListener(service, new Messages(testConfig()), Logger.getLogger("listener-test"), null);
        when(event.getPlayer()).thenReturn(player);
    }

    @Test
    void joinWarnsPlayerWhenAccountCreationFailsBecauseNameIsInUse() {
        UUID accountId = UUID.randomUUID();

        when(player.getUniqueId()).thenReturn(accountId);
        when(player.getName()).thenReturn("Alice");
        when(service.hasAccount(accountId)).thenReturn(false);
        when(service.createAccountDetailed(accountId, "Alice"))
                .thenReturn(AccountService.CreateAccountStatus.NAME_IN_USE);

        listener.onJoin(event);

        verify(player).sendMessage(any(Component.class));
    }

    @Test
    void joinWarnsPlayerWhenRenameFailsBecauseNameIsInUse() {
        UUID accountId = UUID.randomUUID();
        AccountRecord record = new AccountRecord(accountId, "Bob", new BigDecimal("10.00"), 1L, 2L);

        when(player.getUniqueId()).thenReturn(accountId);
        when(player.getName()).thenReturn("Alice");
        when(service.hasAccount(accountId)).thenReturn(true);
        when(service.getAccount(accountId)).thenReturn(Optional.of(record));
        when(service.renameAccountDetailed(accountId, "Alice"))
                .thenReturn(AccountService.RenameAccountStatus.NAME_IN_USE);

        listener.onJoin(event);

        verify(player).sendMessage(any(Component.class));
    }

    @Test
    void joinDoesNotMessagePlayerWhenNameIsAlreadyInSync() {
        UUID accountId = UUID.randomUUID();
        AccountRecord record = new AccountRecord(accountId, "Alice", new BigDecimal("10.00"), 1L, 2L);

        when(player.getUniqueId()).thenReturn(accountId);
        when(player.getName()).thenReturn("Alice");
        when(service.hasAccount(accountId)).thenReturn(true);
        when(service.getAccount(accountId)).thenReturn(Optional.of(record));

        listener.onJoin(event);

        verify(player, never()).sendMessage(any(Component.class));
    }

    private static YamlConfiguration testConfig() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("messages.account-sync-failed", "<red>sync failed");
        config.set("messages.account-name-conflict", "<red>name conflict for <player>");
        return config;
    }
}