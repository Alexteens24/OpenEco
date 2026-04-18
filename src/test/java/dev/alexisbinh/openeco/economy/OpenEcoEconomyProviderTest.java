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

package dev.alexisbinh.openeco.economy;

import dev.alexisbinh.openeco.api.BalanceCheckResult;
import dev.alexisbinh.openeco.service.AccountService;
import dev.alexisbinh.openeco.service.EconomyOperationResponse;
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

    @Test
    void depositConvertsLegacyResponseToVaultUnlockedResponse() {
        UUID accountId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("5.00");
        when(service.deposit(accountId, amount)).thenReturn(new EconomyOperationResponse(
            new BigDecimal("5.00"),
            new BigDecimal("17.50"),
            EconomyOperationResponse.ResponseType.SUCCESS,
                ""));

        EconomyResponse response = provider.deposit("shop", accountId, amount);

        assertEquals(EconomyResponse.ResponseType.SUCCESS, response.type);
        assertEquals(0, amount.compareTo(response.amount));
        assertEquals(0, new BigDecimal("17.50").compareTo(response.balance));
    }

    @Test
    void depositPreservesNotImplementedResponseTypeFromLegacyProvider() {
        UUID accountId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("5.00");
        when(service.deposit(accountId, amount)).thenReturn(new EconomyOperationResponse(
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            EconomyOperationResponse.ResponseType.NOT_IMPLEMENTED,
                "Not supported"));

        EconomyResponse response = provider.deposit("shop", accountId, amount);

        assertEquals(EconomyResponse.ResponseType.NOT_IMPLEMENTED, response.type);
        assertEquals("Not supported", response.errorMessage);
    }
}
