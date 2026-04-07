package dev.alexisbinh.openeco.placeholder;

import dev.alexisbinh.openeco.model.AccountRecord;
import dev.alexisbinh.openeco.service.AccountService;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenEcoPlaceholderExpansionTest {

    @Mock
    private AccountService service;

    @Mock
    private OfflinePlayer player;

    private UUID accountId;
    private OpenEcoPlaceholderExpansion expansion;

    @BeforeEach
    void setUp() {
        accountId = UUID.randomUUID();
        expansion = new OpenEcoPlaceholderExpansion(service, "test");
    }

    @Test
    void balanceFormattedCurrencyPlaceholderUsesRequestedCurrency() {
        when(player.getUniqueId()).thenReturn(accountId);
        when(service.hasCurrency("gems")).thenReturn(true);
        when(service.getBalance(accountId, "gems")).thenReturn(new BigDecimal("12.00"));
        when(service.format(new BigDecimal("12.00"), "gems")).thenReturn("12 Gems");

        assertEquals("12 Gems", expansion.onRequest(player, "balance_formatted_gems"));
    }

    @Test
    void topPlaceholdersUseCurrencySpecificLeaderboard() {
        AccountRecord entry = new AccountRecord(
                accountId,
                "Alice",
                "gems",
                Map.of("gems", new BigDecimal("5.00")),
                1L,
                1L);

        when(service.hasCurrency("gems")).thenReturn(true);
        when(service.getBalTopSnapshot("gems")).thenReturn(List.of(entry));
        when(service.format(new BigDecimal("5.00"), "gems")).thenReturn("5 Gems");

        assertEquals("Alice", expansion.onRequest(null, "top_1_name_gems"));
        assertEquals("5.00", expansion.onRequest(null, "top_1_balance_gems"));
        assertEquals("5 Gems", expansion.onRequest(null, "top_1_balance_formatted_gems"));
    }
}