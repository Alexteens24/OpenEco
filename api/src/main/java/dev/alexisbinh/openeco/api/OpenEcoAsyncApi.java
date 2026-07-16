/*
 * Copyright 2026 alexisbinh
 * Licensed under the Apache License, Version 2.0.
 */
package dev.alexisbinh.openeco.api;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** Non-blocking facade for operations that can perform remote storage I/O. */
public interface OpenEcoAsyncApi {

    CompletionStage<Optional<AccountSnapshot>> getFreshAccount(UUID accountId);

    CompletionStage<BigDecimal> getFreshBalance(UUID accountId, String currencyId);

    CompletionStage<AccountOperationResult> createAccount(UUID accountId, String name);

    CompletionStage<AccountOperationResult> renameAccount(UUID accountId, String newName);

    CompletionStage<AccountOperationResult> deleteAccount(UUID accountId);

    CompletionStage<BalanceChangeResult> deposit(UUID accountId, String currencyId, BigDecimal amount);

    CompletionStage<BalanceChangeResult> withdraw(UUID accountId, String currencyId, BigDecimal amount);

    CompletionStage<BalanceChangeResult> setBalance(UUID accountId, String currencyId, BigDecimal amount);

    CompletionStage<BalanceChangeResult> reset(UUID accountId, String currencyId);

    CompletionStage<TransferResult> transfer(UUID fromId, UUID toId, String currencyId, BigDecimal amount);

    CompletionStage<AccountTransferResult> directTransfer(
            UUID fromId, UUID toId, String currencyId, BigDecimal amount);

    CompletionStage<ExchangeResult> exchange(UUID accountId, String fromCurrencyId, String toCurrencyId,
                                             BigDecimal fromAmount, BigDecimal toAmount);
}
