package dev.alexisbinh.openeco.economy;

import dev.alexisbinh.openeco.model.AccountRecord;
import dev.alexisbinh.openeco.service.AccountService;
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
class OpenEcoLegacyEconomyProviderTest {

    @Mock
    private AccountService service;

    @Mock
    private OfflinePlayer player;

    private OpenEcoLegacyEconomyProvider provider;

    @BeforeEach
    void setUp() {
        provider = new OpenEcoLegacyEconomyProvider(service, name -> Optional.empty());
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
    void hasByNameDelegatesToServiceRulesAfterResolvingAccount() {
        UUID accountId = UUID.randomUUID();
        AccountRecord account = new AccountRecord(accountId, "Alice", new BigDecimal("1.00"), 1L, 1L);

        when(service.findByName("Alice")).thenReturn(Optional.of(account));
        when(service.has(accountId, BigDecimal.valueOf(0.001))).thenReturn(false);

        assertFalse(provider.has("Alice", 0.001));

        verify(service).has(accountId, BigDecimal.valueOf(0.001));
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
    void depositByNameDoesNotFallbackToKnownPlayerLookupWhenNameIsMissing() {
        provider = new OpenEcoLegacyEconomyProvider(service, name -> Optional.of(player));

        when(service.findByName("Alice")).thenReturn(Optional.empty());

        EconomyResponse result = provider.depositPlayer("Alice", 5.0);

        assertEquals(EconomyResponse.ResponseType.FAILURE, result.type);
        assertEquals("Account not found", result.errorMessage);
        verify(service, never()).deposit(any(UUID.class), any(BigDecimal.class));
    }

    @Test
    void createPlayerAccountByNameUsesKnownPlayerWhenAvailable() {
        UUID playerId = UUID.randomUUID();

        provider = new OpenEcoLegacyEconomyProvider(service, name -> Optional.of(player));

        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn("Alice");
        when(service.findByName("Alice")).thenReturn(Optional.empty());
        when(service.createAccount(playerId, "Alice")).thenReturn(true);

        assertTrue(provider.createPlayerAccount("Alice"));
        verify(service).createAccount(playerId, "Alice");
    }

    @Test
    void createPlayerAccountByNameReturnsTrueForExistingStoredAccount() {
        UUID playerId = UUID.randomUUID();
        when(service.findByName("Alice")).thenReturn(Optional.of(
                new AccountRecord(playerId, "Alice", new BigDecimal("12.50"), 1L, 1L)));

        assertTrue(provider.createPlayerAccount("Alice"));

        verify(service, never()).createAccount(any(UUID.class), anyString());
    }

    @Test
    void createPlayerAccountByNameRejectsUnknownPlayersWithoutInventingUuid() {
        when(service.findByName("Alice")).thenReturn(Optional.empty());

        assertFalse(provider.createPlayerAccount("Alice"));

        verify(service, never()).createAccount(any(UUID.class), anyString());
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