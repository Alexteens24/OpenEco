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

package dev.alexisbinh.openeco.enhancements.exchange;

import dev.alexisbinh.openeco.api.BalanceCheckResult;
import dev.alexisbinh.openeco.api.CurrencyInfo;
import dev.alexisbinh.openeco.api.ExchangeResult;
import dev.alexisbinh.openeco.api.OpenEcoApi;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExchangeCommandTest {

    @Mock private OpenEcoApi api;
    @Mock private JavaPlugin plugin;
    @Mock private Player player;
    @Mock private Command command;
    @Mock private Logger logger;

    private YamlConfiguration config;
    private ExchangeCommand subject;
    private UUID playerId;

    @BeforeEach
    void setUp() {
        config = new YamlConfiguration();
        playerId = UUID.randomUUID();
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(logger);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn("Alice");
        subject = new ExchangeCommand(api, plugin);
    }

    // ── findRate unit tests ───────────────────────────────────────────────────

    @Test
    void findRate_matchingEntry_returnsRate() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("exchange.rates", List.of(Map.of("from", "a", "to", "b", "rate", 5.0)));
        assertEquals(5.0, ExchangeCommand.findRate(cfg, "a", "b"));
    }

    @Test
    void findRate_noMatchingEntry_returnsNull() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("exchange.rates", List.of(Map.of("from", "a", "to", "b", "rate", 5.0)));
        assertNull(ExchangeCommand.findRate(cfg, "b", "a"));
    }

    @Test
    void findRate_emptyRates_returnsNull() {
        assertNull(ExchangeCommand.findRate(new YamlConfiguration(), "a", "b"));
    }

    // ── happy path ───────────────────────────────────────────────────────────

    @Test
    void happyPath_withdrawsAndDepositsAtConfiguredRate() {
        setUpBasicCurrencies();
        // 10 openeco (2dp) * rate 10 = 100 gems (0dp)
        when(api.canWithdraw(eq(playerId), eq("openeco"), eq(new BigDecimal("10.00"))))
                .thenReturn(allowed());
        when(api.canDeposit(eq(playerId), eq("gems"), eq(new BigDecimal("100"))))
                .thenReturn(allowed());
        when(api.format(any(), any())).thenReturn("10.00");

        when(api.convertCurrency(eq(playerId), eq("openeco"), eq("gems"),
                eq(new BigDecimal("10.00")), eq(new BigDecimal("100"))))
                .thenReturn(ExchangeResult.success(new BigDecimal("10.00"), new BigDecimal("100"),
                        new BigDecimal("90.00"), new BigDecimal("100")));

        subject.onCommand(player, command, "exchange", new String[]{"10", "openeco", "gems"});

        // One atomic call, not a withdraw followed by a deposit.
        verify(api).convertCurrency(playerId, "openeco", "gems",
                new BigDecimal("10.00"), new BigDecimal("100"));
        verify(api, never()).withdraw(any(UUID.class), any(String.class), any(BigDecimal.class));
        verify(api, never()).deposit(any(UUID.class), any(String.class), any(BigDecimal.class));
        verify(player).sendMessage(any(Component.class));
    }

    @Test
    void feePercent_reducesToAmount() {
        config.set("exchange.fee-percent", 10.0);
        setUpBasicCurrencies();
        // 10.00 * rate(10) * (1 - 0.10) = 90 gems
        when(api.canWithdraw(eq(playerId), eq("openeco"), eq(new BigDecimal("10.00"))))
                .thenReturn(allowed());
        when(api.canDeposit(eq(playerId), eq("gems"), eq(new BigDecimal("90"))))
                .thenReturn(allowed());
        when(api.format(any(), any())).thenReturn("10.00");

        when(api.convertCurrency(eq(playerId), eq("openeco"), eq("gems"),
                eq(new BigDecimal("10.00")), eq(new BigDecimal("90"))))
                .thenReturn(ExchangeResult.success(new BigDecimal("10.00"), new BigDecimal("90"),
                        new BigDecimal("90.00"), new BigDecimal("90")));

        subject.onCommand(player, command, "exchange", new String[]{"10", "openeco", "gems"});

        verify(api).convertCurrency(playerId, "openeco", "gems",
                new BigDecimal("10.00"), new BigDecimal("90"));
    }

    // ── argument / validation errors ─────────────────────────────────────────

    @Test
    void wrongArgCount_sendsUsageMessage_noMutation() {
        subject.onCommand(player, command, "exchange", new String[]{"10", "openeco"});
        verify(player).sendMessage(any(Component.class));
        verify(api, never()).withdraw(any(UUID.class), any(String.class), any(BigDecimal.class));
    }

    @Test
    void invalidAmount_nonNumeric_sendsError() {
        subject.onCommand(player, command, "exchange", new String[]{"abc", "openeco", "gems"});
        verify(player).sendMessage(any(Component.class));
        verify(api, never()).hasCurrency(any());
    }

    @Test
    void invalidAmount_zero_sendsError() {
        subject.onCommand(player, command, "exchange", new String[]{"0", "openeco", "gems"});
        verify(player).sendMessage(any(Component.class));
        verify(api, never()).hasCurrency(any());
    }

    @Test
    void invalidAmount_negative_sendsError() {
        subject.onCommand(player, command, "exchange", new String[]{"-5", "openeco", "gems"});
        verify(player).sendMessage(any(Component.class));
        verify(api, never()).hasCurrency(any());
    }

    @Test
    void unknownFromCurrency_sendsError() {
        when(api.hasCurrency("unknown")).thenReturn(false);
        subject.onCommand(player, command, "exchange", new String[]{"10", "unknown", "gems"});
        verify(player).sendMessage(any(Component.class));
        verify(api, never()).withdraw(any(UUID.class), any(String.class), any(BigDecimal.class));
    }

    @Test
    void unknownToCurrency_sendsError() {
        when(api.hasCurrency("openeco")).thenReturn(true);
        when(api.hasCurrency("unknown")).thenReturn(false);
        subject.onCommand(player, command, "exchange", new String[]{"10", "openeco", "unknown"});
        verify(player).sendMessage(any(Component.class));
        verify(api, never()).withdraw(any(UUID.class), any(String.class), any(BigDecimal.class));
    }

    @Test
    void sameCurrency_sendsError() {
        when(api.hasCurrency("openeco")).thenReturn(true);
        subject.onCommand(player, command, "exchange", new String[]{"10", "openeco", "openeco"});
        verify(player).sendMessage(any(Component.class));
        verify(api, never()).withdraw(any(UUID.class), any(String.class), any(BigDecimal.class));
    }

    @Test
    void noRateConfigured_sendsError_noMutation() {
        when(api.hasCurrency("openeco")).thenReturn(true);
        when(api.hasCurrency("gems")).thenReturn(true);
        // no rates in config
        subject.onCommand(player, command, "exchange", new String[]{"10", "openeco", "gems"});
        verify(player).sendMessage(any(Component.class));
        verify(api, never()).withdraw(any(UUID.class), any(String.class), any(BigDecimal.class));
    }

    // ── precheck failures (no mutation) ──────────────────────────────────────

    @Test
    void insufficientFunds_canWithdrawFails_noMutation() {
        setUpBasicCurrencies();
        when(api.canWithdraw(eq(playerId), eq("openeco"), any())).thenReturn(
                new BalanceCheckResult(BalanceCheckResult.Status.INSUFFICIENT_FUNDS,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));

        subject.onCommand(player, command, "exchange", new String[]{"10", "openeco", "gems"});

        verify(player).sendMessage(any(Component.class));
        verify(api, never()).withdraw(any(UUID.class), any(String.class), any(BigDecimal.class));
    }

    @Test
    void balanceLimit_canDepositFails_noMutation() {
        setUpBasicCurrencies();
        when(api.canWithdraw(eq(playerId), eq("openeco"), any())).thenReturn(allowed());
        when(api.canDeposit(eq(playerId), eq("gems"), any())).thenReturn(
                new BalanceCheckResult(BalanceCheckResult.Status.BALANCE_LIMIT,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));

        subject.onCommand(player, command, "exchange", new String[]{"10", "openeco", "gems"});

        verify(player).sendMessage(any(Component.class));
        verify(api, never()).withdraw(any(UUID.class), any(String.class), any(BigDecimal.class));
    }

    // ── runtime failures after precheck ──────────────────────────────────────

    @Test
    void conversionFailsAtRuntime_sendsError_andTouchesNothingElse() {
        setUpBasicCurrencies();
        when(api.canWithdraw(eq(playerId), eq("openeco"), any())).thenReturn(allowed());
        when(api.canDeposit(eq(playerId), eq("gems"), any())).thenReturn(allowed());
        when(api.convertCurrency(eq(playerId), eq("openeco"), eq("gems"), any(), any())).thenReturn(
                ExchangeResult.failed(ExchangeResult.Status.INSUFFICIENT_FUNDS,
                        BigDecimal.ZERO, BigDecimal.ZERO));

        subject.onCommand(player, command, "exchange", new String[]{"10", "openeco", "gems"});

        // The command has no compensation path any more: there is nothing to roll back
        // because the single conversion call either applied both legs or neither.
        verify(api, never()).withdraw(any(UUID.class), any(String.class), any(BigDecimal.class));
        verify(api, never()).deposit(any(UUID.class), any(String.class), any(BigDecimal.class));
        verify(player).sendMessage(any(Component.class));
    }

    /**
     * The regression this command used to have: when the credit leg was refused, the command
     * compensated with a deposit, which is itself a credit and could be refused as well. The
     * withdrawn amount was then destroyed and only a log line recorded it. There is now no
     * compensation to fail.
     */
    @Test
    void balanceLimitAtRuntime_needsNoRollback() {
        setUpBasicCurrencies();
        when(api.canWithdraw(eq(playerId), eq("openeco"), eq(new BigDecimal("10.00"))))
                .thenReturn(allowed());
        when(api.canDeposit(eq(playerId), eq("gems"), eq(new BigDecimal("100"))))
                .thenReturn(allowed());
        when(api.convertCurrency(eq(playerId), eq("openeco"), eq("gems"),
                eq(new BigDecimal("10.00")), eq(new BigDecimal("100"))))
                .thenReturn(ExchangeResult.failed(ExchangeResult.Status.BALANCE_LIMIT,
                        new BigDecimal("10.00"), BigDecimal.ZERO));

        subject.onCommand(player, command, "exchange", new String[]{"10", "openeco", "gems"});

        verify(api, never()).deposit(playerId, "openeco", new BigDecimal("10.00"));
        verify(api, never()).withdraw(any(UUID.class), any(String.class), any(BigDecimal.class));
        verify(player).sendMessage(any(Component.class));
    }

    @Test
    void vetoedConversionReportsCancelled() {
        setUpBasicCurrencies();
        when(api.canWithdraw(eq(playerId), eq("openeco"), any())).thenReturn(allowed());
        when(api.canDeposit(eq(playerId), eq("gems"), any())).thenReturn(allowed());
        when(api.convertCurrency(eq(playerId), eq("openeco"), eq("gems"), any(), any())).thenReturn(
                ExchangeResult.failed(ExchangeResult.Status.CANCELLED, BigDecimal.ZERO, BigDecimal.ZERO));

        subject.onCommand(player, command, "exchange", new String[]{"10", "openeco", "gems"});

        verify(api, never()).withdraw(any(UUID.class), any(String.class), any(BigDecimal.class));
        verify(player).sendMessage(any(Component.class));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void setUpBasicCurrencies() {
        when(api.hasCurrency("openeco")).thenReturn(true);
        when(api.hasCurrency("gems")).thenReturn(true);
        when(api.getCurrencyInfo("openeco")).thenReturn(
                new CurrencyInfo("openeco", "coin", "coins", 2, BigDecimal.ZERO, null));
        when(api.getCurrencyInfo("gems")).thenReturn(
                new CurrencyInfo("gems", "gem", "gems", 0, BigDecimal.ZERO, null));
        config.set("exchange.rates", List.of(
                Map.of("from", "openeco", "to", "gems", "rate", 10.0)));
    }

    private static BalanceCheckResult allowed() {
        return new BalanceCheckResult(BalanceCheckResult.Status.ALLOWED,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

}
