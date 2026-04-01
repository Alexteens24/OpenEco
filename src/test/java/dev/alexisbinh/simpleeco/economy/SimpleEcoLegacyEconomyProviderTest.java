package dev.alexisbinh.simpleeco.economy;

import dev.alexisbinh.simpleeco.model.AccountRecord;
import dev.alexisbinh.simpleeco.service.AccountService;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("deprecation")
@ExtendWith(MockitoExtension.class)
class SimpleEcoLegacyEconomyProviderTest {

    @Mock
    private AccountService service;

    @Mock
    private OfflinePlayer player;

    private SimpleEcoLegacyEconomyProvider provider;

    @BeforeEach
    void setUp() {
        provider = new SimpleEcoLegacyEconomyProvider(service);
    }

    @Test
    void getBalanceByNameUsesStoredAccountNameLookup() {
        UUID accountId = UUID.randomUUID();
        AccountRecord account = new AccountRecord(accountId, "Alice", new BigDecimal("12.50"), 1L, 1L);

        when(service.findByName("Alice")).thenReturn(Optional.of(account));

        double balance = provider.getBalance("Alice");

        assertEquals(0, new BigDecimal("12.50").compareTo(BigDecimal.valueOf(balance)));
        verify(service).findByName("Alice");
        verify(service, never()).getBalance(accountId);
    }

    @Test
    void hasByNameRejectsNegativeAmounts() {
        assertFalse(provider.has("Alice", -1.0));
        verify(service, never()).findByName("Alice");
    }

    @Test
    void depositByNameDelegatesUsingResolvedAccountId() {
        UUID accountId = UUID.randomUUID();
        AccountRecord account = new AccountRecord(accountId, "Alice", new BigDecimal("12.50"), 1L, 1L);
        net.milkbowl.vault2.economy.EconomyResponse response = new net.milkbowl.vault2.economy.EconomyResponse(
                new BigDecimal("5.00"),
                new BigDecimal("17.50"),
                net.milkbowl.vault2.economy.EconomyResponse.ResponseType.SUCCESS,
                "");

        when(service.findByName("Alice")).thenReturn(Optional.of(account));
        when(service.deposit(accountId, BigDecimal.valueOf(5.0))).thenReturn(response);

        EconomyResponse result = provider.depositPlayer("Alice", 5.0);

        assertEquals(EconomyResponse.ResponseType.SUCCESS, result.type);
        assertEquals(17.5, result.balance);
        verify(service).deposit(accountId, BigDecimal.valueOf(5.0));
    }

    @Test
    void withdrawByNameFailsWhenNoAccountMatchesName() {
        when(service.findByName("Alice")).thenReturn(Optional.empty());

        EconomyResponse result = provider.withdrawPlayer("Alice", 5.0);

        assertEquals(EconomyResponse.ResponseType.FAILURE, result.type);
        assertEquals("Account not found", result.errorMessage);
    }

    @Test
    void createPlayerAccountUsesOfflinePlayerNameWhenAvailable() {
        UUID playerId = UUID.randomUUID();

        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn("Alice");
        when(service.createAccount(playerId, "Alice")).thenReturn(true);

        assertTrue(provider.createPlayerAccount(player));
        verify(service).createAccount(playerId, "Alice");
    }

    @Test
    void createPlayerAccountRejectsOfflinePlayersWithoutName() {
        when(player.getName()).thenReturn(null);

        assertFalse(provider.createPlayerAccount(player));
        verify(service, never()).createAccount(any(UUID.class), anyString());
    }
}