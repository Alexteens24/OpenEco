package dev.alexisbinh.openeco.economy;

import dev.alexisbinh.openeco.api.BalanceCheckResult;
import dev.alexisbinh.openeco.service.AccountService;
import net.milkbowl.vault2.economy.EconomyResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenEcoEconomyProviderTest {

    @Mock
    private AccountService service;

    private OpenEcoEconomyProvider provider;

    @BeforeEach
    void setUp() {
        provider = new OpenEcoEconomyProvider(service);
    }

    @Test
    void reportsMultiCurrencySupportAndConfiguredCurrencies() {
        when(service.getCurrencyIds()).thenReturn(List.of("coins", "gems"));

        assertTrue(provider.hasMultiCurrencySupport());
        assertEquals(List.of("coins", "gems"), List.copyOf(provider.currencies()));
    }

    @Test
    void getBalanceUsesRequestedCurrency() {
        UUID accountId = UUID.randomUUID();
        when(service.hasCurrency("gems")).thenReturn(true);
        when(service.getBalance(accountId, "gems")).thenReturn(new BigDecimal("7.50"));

        BigDecimal balance = provider.getBalance("shop", accountId, "world", "gems");

        assertEquals(0, new BigDecimal("7.50").compareTo(balance));
        verify(service).getBalance(accountId, "gems");
    }

    @Test
    void canDepositReturnsCurrentBalanceOnSuccess() {
        UUID accountId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("5.00");
        when(service.canDeposit(accountId, "gems", amount)).thenReturn(new BalanceCheckResult(
                BalanceCheckResult.Status.ALLOWED,
                amount,
                new BigDecimal("12.00"),
                new BigDecimal("17.00")));

        EconomyResponse response = provider.canDeposit("shop", accountId, "world", "gems", amount);

        assertEquals(EconomyResponse.ResponseType.SUCCESS, response.type);
        assertEquals(0, amount.compareTo(response.amount));
        assertEquals(0, new BigDecimal("12.00").compareTo(response.balance));
    }
}