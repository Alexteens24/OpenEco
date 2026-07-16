/*
 * Copyright 2026 alexisbinh
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package dev.alexisbinh.openeco.enhancements.exchange;

import dev.alexisbinh.openeco.api.CurrencyInfo;
import dev.alexisbinh.openeco.api.ExchangeResult;
import dev.alexisbinh.openeco.api.OpenEcoApi;
import dev.alexisbinh.openeco.api.OpenEcoAsyncApi;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
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
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExchangeCommandTest {

    @Mock private OpenEcoApi api;
    @Mock private JavaPlugin plugin;
    @Mock private Player player;
    @Mock private Command command;
    @Mock private Logger logger;
    @Mock private OpenEcoAsyncApi asyncApi;
    @Mock private EntityScheduler entityScheduler;

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
        when(player.getScheduler()).thenReturn(entityScheduler);
        doAnswer(invocation -> {
            invocation.<java.util.function.Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask>>getArgument(1)
                    .accept(null);
            return true;
        }).when(entityScheduler).run(eq(plugin), any(), org.mockito.ArgumentMatchers.isNull());
        subject = new ExchangeCommand(api, asyncApi, plugin);
    }

    @Test
    void findRate_matchingEntry_returnsRate() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("exchange.rates", List.of(Map.of("from", "a", "to", "b", "rate", 5.0)));
        assertEquals(new BigDecimal("5.0"), ExchangeCommand.findRate(cfg, "a", "b"));
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

    @Test
    void happyPath_executesOneAtomicExchange() {
        setUpBasicCurrencies();
        when(asyncApi.exchange(playerId, "openeco", "gems", new BigDecimal("10.00"), new BigDecimal("100")))
                .thenReturn(CompletableFuture.completedFuture(
                        success(new BigDecimal("10.00"), new BigDecimal("100"))));
        when(api.format(any(), any())).thenReturn("10.00");

        subject.onCommand(player, command, "exchange", new String[]{"10", "openeco", "gems"});

        verify(asyncApi).exchange(playerId, "openeco", "gems", new BigDecimal("10.00"), new BigDecimal("100"));
        verify(api, never()).withdraw(any(), any(), any());
        verify(api, never()).deposit(any(), any(), any());
        verify(player).sendMessage(any(Component.class));
    }

    @Test
    void feePercent_reducesToAmount() {
        config.set("exchange.fee-percent", 10.0);
        setUpBasicCurrencies();
        when(asyncApi.exchange(playerId, "openeco", "gems", new BigDecimal("10.00"), new BigDecimal("90")))
                .thenReturn(CompletableFuture.completedFuture(
                        success(new BigDecimal("10.00"), new BigDecimal("90"))));
        when(api.format(any(), any())).thenReturn("10.00");

        subject.onCommand(player, command, "exchange", new String[]{"10", "openeco", "gems"});

        verify(asyncApi).exchange(playerId, "openeco", "gems", new BigDecimal("10.00"), new BigDecimal("90"));
    }

    @Test
    void wrongArgCount_sendsUsageMessage_noMutation() {
        subject.onCommand(player, command, "exchange", new String[]{"10", "openeco"});
        verify(player).sendMessage(any(Component.class));
        verify(asyncApi, never()).exchange(any(), any(), any(), any(), any());
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
        verify(asyncApi, never()).exchange(any(), any(), any(), any(), any());
    }

    @Test
    void unknownToCurrency_sendsError() {
        when(api.hasCurrency("openeco")).thenReturn(true);
        when(api.hasCurrency("unknown")).thenReturn(false);
        subject.onCommand(player, command, "exchange", new String[]{"10", "openeco", "unknown"});
        verify(player).sendMessage(any(Component.class));
        verify(asyncApi, never()).exchange(any(), any(), any(), any(), any());
    }

    @Test
    void sameCurrency_sendsError() {
        when(api.hasCurrency("openeco")).thenReturn(true);
        subject.onCommand(player, command, "exchange", new String[]{"10", "openeco", "openeco"});
        verify(player).sendMessage(any(Component.class));
        verify(asyncApi, never()).exchange(any(), any(), any(), any(), any());
    }

    @Test
    void noRateConfigured_sendsError_noMutation() {
        when(api.hasCurrency("openeco")).thenReturn(true);
        when(api.hasCurrency("gems")).thenReturn(true);
        subject.onCommand(player, command, "exchange", new String[]{"10", "openeco", "gems"});
        verify(player).sendMessage(any(Component.class));
        verify(api, never()).exchange(any(), any(), any(), any(), any());
    }

    @Test
    void insufficientFunds_isReportedByAtomicOperation() {
        setUpBasicCurrencies();
        when(asyncApi.exchange(eq(playerId), eq("openeco"), eq("gems"), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(
                        failed(ExchangeResult.Status.INSUFFICIENT_FUNDS)));

        subject.onCommand(player, command, "exchange", new String[]{"10", "openeco", "gems"});

        verify(player).sendMessage(any(Component.class));
        verify(api, never()).withdraw(any(), any(), any());
        verify(api, never()).deposit(any(), any(), any());
    }

    @Test
    void balanceLimit_isReportedByAtomicOperation() {
        setUpBasicCurrencies();
        when(asyncApi.exchange(eq(playerId), eq("openeco"), eq("gems"), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(
                        failed(ExchangeResult.Status.BALANCE_LIMIT)));

        subject.onCommand(player, command, "exchange", new String[]{"10", "openeco", "gems"});

        verify(player).sendMessage(any(Component.class));
    }

    @Test
    void storageFailure_hasNoCompensatingMutation() {
        setUpBasicCurrencies();
        when(asyncApi.exchange(eq(playerId), eq("openeco"), eq("gems"), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(
                        failed(ExchangeResult.Status.STORAGE_ERROR)));

        subject.onCommand(player, command, "exchange", new String[]{"10", "openeco", "gems"});

        verify(api, never()).withdraw(any(), any(), any());
        verify(api, never()).deposit(any(), any(), any());
        verify(player).sendMessage(any(Component.class));
    }

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

    private static ExchangeResult success(BigDecimal from, BigDecimal to) {
        return new ExchangeResult(ExchangeResult.Status.SUCCESS, from, to, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private static ExchangeResult failed(ExchangeResult.Status status) {
        return new ExchangeResult(status, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
