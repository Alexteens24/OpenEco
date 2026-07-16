/*
 * Copyright 2026 alexisbinh
 * Licensed under the Apache License, Version 2.0.
 */
package dev.alexisbinh.openeco.api;

import dev.alexisbinh.openeco.event.EventExecutionContext;
import dev.alexisbinh.openeco.service.AccountService;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public final class OpenEcoAsyncApiImpl implements OpenEcoAsyncApi, AutoCloseable {
    private final OpenEcoApi sync;
    private final AccountService service;
    private final ExecutorService executor;

    public OpenEcoAsyncApiImpl(OpenEcoApi sync, AccountService service, String threadPrefix, int threads) {
        this.sync = sync;
        this.service = service;
        AtomicInteger index = new AtomicInteger();
        int size = Math.max(1, Math.min(4, threads));
        this.executor = new ThreadPoolExecutor(size, size, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1_024), runnable -> {
                    Thread thread = new Thread(runnable, threadPrefix + "-economy-io-" + index.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public CompletionStage<Optional<AccountSnapshot>> getFreshAccount(UUID accountId) {
        return submit(() -> {
            try {
                service.loadFreshAccount(accountId);
                return sync.getAccount(accountId);
            } catch (SQLException e) {
                throw new OpenEcoApiException("Failed to load fresh account", e);
            }
        });
    }

    @Override
    public CompletionStage<BigDecimal> getFreshBalance(UUID accountId, String currencyId) {
        return getFreshAccount(accountId).thenApply(account ->
                account.isPresent() ? sync.getBalance(accountId, currencyId) : BigDecimal.ZERO);
    }

    @Override public CompletionStage<AccountOperationResult> createAccount(UUID id, String name) {
        return submit(() -> sync.createAccount(id, name));
    }
    @Override public CompletionStage<AccountOperationResult> renameAccount(UUID id, String name) {
        return submit(() -> sync.renameAccount(id, name));
    }
    @Override public CompletionStage<AccountOperationResult> deleteAccount(UUID id) {
        return submit(() -> sync.deleteAccount(id));
    }
    @Override public CompletionStage<BalanceChangeResult> deposit(UUID id, String currency, BigDecimal amount) {
        return submit(() -> sync.deposit(id, currency, amount));
    }
    @Override public CompletionStage<BalanceChangeResult> withdraw(UUID id, String currency, BigDecimal amount) {
        return submit(() -> sync.withdraw(id, currency, amount));
    }
    @Override public CompletionStage<BalanceChangeResult> setBalance(UUID id, String currency, BigDecimal amount) {
        return submit(() -> sync.setBalance(id, currency, amount));
    }
    @Override public CompletionStage<BalanceChangeResult> reset(UUID id, String currency) {
        return submit(() -> sync.reset(id, currency));
    }
    @Override public CompletionStage<TransferResult> transfer(UUID from, UUID to, String currency, BigDecimal amount) {
        return submit(() -> sync.transfer(from, to, currency, amount));
    }
    @Override public CompletionStage<AccountTransferResult> directTransfer(
            UUID from, UUID to, String currency, BigDecimal amount) {
        return submit(() -> {
            var result = service.directTransfer(from, to, currency, amount);
            return new AccountTransferResult(
                    AccountTransferResult.Status.valueOf(result.status().name()),
                    result.amount(), result.fromBalance(), result.toBalance(), result.message());
        });
    }
    @Override public CompletionStage<ExchangeResult> exchange(UUID id, String fromCurrency, String toCurrency,
                                                               BigDecimal fromAmount, BigDecimal toAmount) {
        return submit(() -> sync.exchange(id, fromCurrency, toCurrency, fromAmount, toAmount));
    }

    private <T> CompletionStage<T> submit(Supplier<T> operation) {
        try {
            return CompletableFuture.supplyAsync(() -> EventExecutionContext.callAsync(operation), executor);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
