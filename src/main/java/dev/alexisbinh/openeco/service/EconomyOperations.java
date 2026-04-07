package dev.alexisbinh.openeco.service;

import dev.alexisbinh.openeco.api.BalanceCheckResult;
import dev.alexisbinh.openeco.api.TransferCheckResult;
import dev.alexisbinh.openeco.api.TransferPreviewResult;
import dev.alexisbinh.openeco.event.BalanceChangeEvent;
import dev.alexisbinh.openeco.event.BalanceChangedEvent;
import dev.alexisbinh.openeco.event.PayCompletedEvent;
import dev.alexisbinh.openeco.event.PayEvent;
import dev.alexisbinh.openeco.model.AccountRecord;
import dev.alexisbinh.openeco.model.PayResult;
import dev.alexisbinh.openeco.model.TransactionEntry;
import dev.alexisbinh.openeco.model.TransactionType;
import net.milkbowl.vault2.economy.EconomyResponse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class EconomyOperations {

    private final AccountRegistry accountRegistry;
    private final Supplier<EconomyConfigSnapshot> configSupplier;
    private final Map<UUID, Long> lastPayTime;
    private final Consumer<TransactionEntry> transactionLogger;
    private final EventDispatcher eventDispatcher;

    EconomyOperations(AccountRegistry accountRegistry,
                      Supplier<EconomyConfigSnapshot> configSupplier,
                      Map<UUID, Long> lastPayTime,
                      Consumer<TransactionEntry> transactionLogger,
                      EventDispatcher eventDispatcher) {
        this.accountRegistry = accountRegistry;
        this.configSupplier = configSupplier;
        this.lastPayTime = lastPayTime;
        this.transactionLogger = transactionLogger;
        this.eventDispatcher = eventDispatcher;
    }

    BalanceCheckResult canDeposit(UUID id, BigDecimal amount) {
        return canDeposit(id, defaultCurrencyId(), amount);
    }

    BalanceCheckResult canDeposit(UUID id, String currencyId, BigDecimal amount) {
        EconomyConfigSnapshot currentConfig = configSupplier.get();
        CurrencyDefinition currency = resolveCurrency(currentConfig, currencyId);
        if (currency == null) {
            return new BalanceCheckResult(BalanceCheckResult.Status.UNKNOWN_CURRENCY, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        BigDecimal scaled = scale(amount, currency);
        if (scaled.compareTo(BigDecimal.ZERO) <= 0) {
            return new BalanceCheckResult(BalanceCheckResult.Status.INVALID_AMOUNT, scaled, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        AccountRecord record = accountRegistry.getLiveRecord(id);
        if (record == null) {
            return new BalanceCheckResult(BalanceCheckResult.Status.ACCOUNT_NOT_FOUND, scaled, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        synchronized (record) {
            if (!accountRegistry.isLive(id, record)) {
                return new BalanceCheckResult(BalanceCheckResult.Status.ACCOUNT_NOT_FOUND, scaled, BigDecimal.ZERO, BigDecimal.ZERO);
            }
            BigDecimal before = record.getBalance(currency.id());
            if (record.isFrozen()) {
                return new BalanceCheckResult(BalanceCheckResult.Status.FROZEN, scaled, before, before);
            }

            BigDecimal newBalance = before.add(scaled);
            if (currency.maxBalance() != null && newBalance.compareTo(currency.maxBalance()) > 0) {
                return new BalanceCheckResult(BalanceCheckResult.Status.BALANCE_LIMIT, scaled, before, before);
            }
            return new BalanceCheckResult(BalanceCheckResult.Status.ALLOWED, scaled, before, newBalance);
        }
    }

    BalanceCheckResult canWithdraw(UUID id, BigDecimal amount) {
        return canWithdraw(id, defaultCurrencyId(), amount);
    }

    BalanceCheckResult canWithdraw(UUID id, String currencyId, BigDecimal amount) {
        EconomyConfigSnapshot currentConfig = configSupplier.get();
        CurrencyDefinition currency = resolveCurrency(currentConfig, currencyId);
        if (currency == null) {
            return new BalanceCheckResult(BalanceCheckResult.Status.UNKNOWN_CURRENCY, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        BigDecimal scaled = scale(amount, currency);
        if (scaled.compareTo(BigDecimal.ZERO) <= 0) {
            return new BalanceCheckResult(BalanceCheckResult.Status.INVALID_AMOUNT, scaled, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        AccountRecord record = accountRegistry.getLiveRecord(id);
        if (record == null) {
            return new BalanceCheckResult(BalanceCheckResult.Status.ACCOUNT_NOT_FOUND, scaled, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        synchronized (record) {
            if (!accountRegistry.isLive(id, record)) {
                return new BalanceCheckResult(BalanceCheckResult.Status.ACCOUNT_NOT_FOUND, scaled, BigDecimal.ZERO, BigDecimal.ZERO);
            }
            BigDecimal before = record.getBalance(currency.id());
            if (record.isFrozen()) {
                return new BalanceCheckResult(BalanceCheckResult.Status.FROZEN, scaled, before, before);
            }
            if (before.compareTo(scaled) < 0) {
                return new BalanceCheckResult(BalanceCheckResult.Status.INSUFFICIENT_FUNDS, scaled, before, before);
            }
            return new BalanceCheckResult(BalanceCheckResult.Status.ALLOWED, scaled, before, before.subtract(scaled));
        }
    }

    EconomyResponse deposit(UUID id, BigDecimal amount) {
        return deposit(id, defaultCurrencyId(), amount);
    }

    EconomyResponse deposit(UUID id, String currencyId, BigDecimal amount) {
        EconomyConfigSnapshot currentConfig = configSupplier.get();
        CurrencyDefinition currency = resolveCurrency(currentConfig, currencyId);
        if (currency == null) {
            return failure(BigDecimal.ZERO, BigDecimal.ZERO, "Unknown currency");
        }

        BigDecimal scaled = scale(amount, currency);
        if (scaled.compareTo(BigDecimal.ZERO) <= 0) {
            return failure(scaled, BigDecimal.ZERO, "Amount must be positive");
        }

        AccountRecord record = accountRegistry.getLiveRecord(id);
        if (record == null) {
            return failure(scaled, BigDecimal.ZERO, "Account not found");
        }

        BalanceChangeEvent event;
        BigDecimal previewBalance;

        synchronized (record) {
            if (!accountRegistry.isLive(id, record)) {
                return failure(scaled, BigDecimal.ZERO, "Account not found");
            }
            BigDecimal before = record.getBalance(currency.id());
            if (record.isFrozen()) {
                return failure(scaled, before, "Account is frozen");
            }

            previewBalance = before;
            BigDecimal newBalance = before.add(scaled);
            if (currency.maxBalance() != null && newBalance.compareTo(currency.maxBalance()) > 0) {
                return failure(scaled, before, "Balance limit reached");
            }

            event = new BalanceChangeEvent(id, before, newBalance, BalanceChangeEvent.Reason.GIVE, currency.id());
        }

        eventDispatcher.dispatch(event);
        if (event.isCancelled()) {
            return failure(scaled, previewBalance, "Cancelled by plugin");
        }

        BalanceChangedEvent completedEvent;
        synchronized (record) {
            if (!accountRegistry.isLive(id, record)) {
                return failure(scaled, BigDecimal.ZERO, "Account not found");
            }

            BigDecimal before = record.getBalance(currency.id());
            if (record.isFrozen()) {
                return failure(scaled, before, "Account is frozen");
            }
            BigDecimal newBalance = before.add(scaled);
            if (currency.maxBalance() != null && newBalance.compareTo(currency.maxBalance()) > 0) {
                return failure(scaled, before, "Balance limit reached");
            }

            record.setBalance(currency.id(), newBalance);
            transactionLogger.accept(new TransactionEntry(
                    TransactionType.GIVE, null, id, scaled, before, newBalance, System.currentTimeMillis(), null, null, currency.id()));
            completedEvent = new BalanceChangedEvent(id, before, newBalance, BalanceChangeEvent.Reason.GIVE, currency.id());
        }
        eventDispatcher.dispatch(completedEvent);
        return success(scaled, completedEvent.getNewBalance());
    }

    EconomyResponse withdraw(UUID id, BigDecimal amount) {
        return withdraw(id, defaultCurrencyId(), amount);
    }

    EconomyResponse withdraw(UUID id, String currencyId, BigDecimal amount) {
        EconomyConfigSnapshot currentConfig = configSupplier.get();
        CurrencyDefinition currency = resolveCurrency(currentConfig, currencyId);
        if (currency == null) {
            return failure(BigDecimal.ZERO, BigDecimal.ZERO, "Unknown currency");
        }

        BigDecimal scaled = scale(amount, currency);
        if (scaled.compareTo(BigDecimal.ZERO) <= 0) {
            return failure(scaled, BigDecimal.ZERO, "Amount must be positive");
        }

        AccountRecord record = accountRegistry.getLiveRecord(id);
        if (record == null) {
            return failure(scaled, BigDecimal.ZERO, "Account not found");
        }

        BalanceChangeEvent event;
        BigDecimal previewBalance;

        synchronized (record) {
            if (!accountRegistry.isLive(id, record)) {
                return failure(scaled, BigDecimal.ZERO, "Account not found");
            }
            BigDecimal before = record.getBalance(currency.id());
            if (record.isFrozen()) {
                return failure(scaled, before, "Account is frozen");
            }
            previewBalance = before;
            if (before.compareTo(scaled) < 0) {
                return failure(scaled, before, "Insufficient funds");
            }

            BigDecimal newBalance = before.subtract(scaled);
            event = new BalanceChangeEvent(id, before, newBalance, BalanceChangeEvent.Reason.TAKE, currency.id());
        }

        eventDispatcher.dispatch(event);
        if (event.isCancelled()) {
            return failure(scaled, previewBalance, "Cancelled by plugin");
        }

        BalanceChangedEvent completedEvent;
        synchronized (record) {
            if (!accountRegistry.isLive(id, record)) {
                return failure(scaled, BigDecimal.ZERO, "Account not found");
            }
            BigDecimal before = record.getBalance(currency.id());
            if (record.isFrozen()) {
                return failure(scaled, before, "Account is frozen");
            }
            if (before.compareTo(scaled) < 0) {
                return failure(scaled, before, "Insufficient funds");
            }

            BigDecimal newBalance = before.subtract(scaled);

            record.setBalance(currency.id(), newBalance);
            transactionLogger.accept(new TransactionEntry(
                    TransactionType.TAKE, null, id, scaled, before, newBalance, System.currentTimeMillis(), null, null, currency.id()));
            completedEvent = new BalanceChangedEvent(id, before, newBalance, BalanceChangeEvent.Reason.TAKE, currency.id());
        }
        eventDispatcher.dispatch(completedEvent);
        return success(scaled, completedEvent.getNewBalance());
    }

    EconomyResponse set(UUID id, BigDecimal amount) {
        return set(id, defaultCurrencyId(), amount);
    }

    EconomyResponse set(UUID id, String currencyId, BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            return failure(amount, BigDecimal.ZERO, "Amount cannot be negative");
        }

        EconomyConfigSnapshot currentConfig = configSupplier.get();
        CurrencyDefinition currency = resolveCurrency(currentConfig, currencyId);
        if (currency == null) {
            return failure(BigDecimal.ZERO, BigDecimal.ZERO, "Unknown currency");
        }

        AccountRecord record = accountRegistry.getLiveRecord(id);
        if (record == null) {
            return failure(amount, BigDecimal.ZERO, "Account not found");
        }

        BigDecimal scaled = scale(amount, currency);
        BalanceChangeEvent event;
        BigDecimal previewBalance;

        synchronized (record) {
            if (!accountRegistry.isLive(id, record)) {
                return failure(scaled, BigDecimal.ZERO, "Account not found");
            }

            BigDecimal before = record.getBalance(currency.id());
            previewBalance = before;
            if (currency.maxBalance() != null && scaled.compareTo(currency.maxBalance()) > 0) {
                return failure(scaled, before, "Balance limit reached");
            }

            event = new BalanceChangeEvent(id, before, scaled, BalanceChangeEvent.Reason.SET, currency.id());
        }

        eventDispatcher.dispatch(event);
        if (event.isCancelled()) {
            return failure(scaled, previewBalance, "Cancelled by plugin");
        }

        BalanceChangedEvent completedEvent;
        synchronized (record) {
            if (!accountRegistry.isLive(id, record)) {
                return failure(scaled, BigDecimal.ZERO, "Account not found");
            }

            BigDecimal before = record.getBalance(currency.id());
            if (currency.maxBalance() != null && scaled.compareTo(currency.maxBalance()) > 0) {
                return failure(scaled, before, "Balance limit reached");
            }

            record.setBalance(currency.id(), scaled);
            transactionLogger.accept(new TransactionEntry(
                    TransactionType.SET, null, id, scaled, before, scaled, System.currentTimeMillis(), null, null, currency.id()));
            completedEvent = new BalanceChangedEvent(id, before, scaled, BalanceChangeEvent.Reason.SET, currency.id());
        }
        eventDispatcher.dispatch(completedEvent);
        return success(scaled, completedEvent.getNewBalance());
    }

    EconomyResponse reset(UUID id) {
        return reset(id, defaultCurrencyId());
    }

    EconomyResponse reset(UUID id, String currencyId) {
        EconomyConfigSnapshot currentConfig = configSupplier.get();
        CurrencyDefinition currency = resolveCurrency(currentConfig, currencyId);
        if (currency == null) {
            return failure(BigDecimal.ZERO, BigDecimal.ZERO, "Unknown currency");
        }

        AccountRecord record = accountRegistry.getLiveRecord(id);
        if (record == null) {
            return failure(BigDecimal.ZERO, BigDecimal.ZERO, "Account not found");
        }

        BigDecimal startingBalance = currency.startingBalance();
        BalanceChangeEvent event;
        BigDecimal previewBalance;

        synchronized (record) {
            if (!accountRegistry.isLive(id, record)) {
                return failure(BigDecimal.ZERO, BigDecimal.ZERO, "Account not found");
            }

            BigDecimal before = record.getBalance(currency.id());
            previewBalance = before;
            event = new BalanceChangeEvent(id, before, startingBalance, BalanceChangeEvent.Reason.RESET, currency.id());
        }

        eventDispatcher.dispatch(event);
        if (event.isCancelled()) {
            return failure(BigDecimal.ZERO, previewBalance, "Cancelled by plugin");
        }

        BalanceChangedEvent completedEvent;
        synchronized (record) {
            if (!accountRegistry.isLive(id, record)) {
                return failure(BigDecimal.ZERO, BigDecimal.ZERO, "Account not found");
            }

            BigDecimal before = record.getBalance(currency.id());
            record.setBalance(currency.id(), startingBalance);
            transactionLogger.accept(new TransactionEntry(
                    TransactionType.RESET, null, id, startingBalance, before, startingBalance,
                    System.currentTimeMillis(), null, null, currency.id()));
            completedEvent = new BalanceChangedEvent(id, before, startingBalance, BalanceChangeEvent.Reason.RESET, currency.id());
        }
        eventDispatcher.dispatch(completedEvent);
        return success(startingBalance, completedEvent.getNewBalance());
    }

    TransferCheckResult canTransfer(UUID fromId, UUID toId, BigDecimal rawAmount) {
        return canTransfer(fromId, toId, defaultCurrencyId(), rawAmount);
    }

    TransferCheckResult canTransfer(UUID fromId, UUID toId, String currencyId, BigDecimal rawAmount) {
        EconomyConfigSnapshot currentConfig = configSupplier.get();
        CurrencyDefinition currency = resolveCurrency(currentConfig, currencyId);
        if (currency == null) {
            return new TransferCheckResult(TransferCheckResult.Status.UNKNOWN_CURRENCY, BigDecimal.ZERO);
        }

        BigDecimal scaled = scale(rawAmount, currency);
        if (scaled.compareTo(BigDecimal.ZERO) <= 0) {
            return new TransferCheckResult(TransferCheckResult.Status.INVALID_AMOUNT, scaled);
        }
        if (fromId.equals(toId)) {
            return new TransferCheckResult(TransferCheckResult.Status.SELF_TRANSFER, scaled);
        }

        AccountRecord fromRecord = accountRegistry.getLiveRecord(fromId);
        AccountRecord toRecord = accountRegistry.getLiveRecord(toId);
        if (fromRecord == null || toRecord == null) {
            return new TransferCheckResult(TransferCheckResult.Status.ACCOUNT_NOT_FOUND, scaled);
        }

        BigDecimal tax = computeTax(scaled, currentConfig, currency);
        BigDecimal received = scaled.subtract(tax);

        boolean fromFirst = fromId.compareTo(toId) < 0;
        AccountRecord first = fromFirst ? fromRecord : toRecord;
        AccountRecord second = fromFirst ? toRecord : fromRecord;

        synchronized (first) {
            synchronized (second) {
                if (!accountRegistry.isLive(fromId, fromRecord) || !accountRegistry.isLive(toId, toRecord)) {
                    return new TransferCheckResult(TransferCheckResult.Status.ACCOUNT_NOT_FOUND, scaled);
                }
                if (fromRecord.getBalance(currency.id()).compareTo(scaled) < 0) {
                    return new TransferCheckResult(TransferCheckResult.Status.INSUFFICIENT_FUNDS, scaled);
                }
                BigDecimal toAfter = toRecord.getBalance(currency.id()).add(received);
                if (currency.maxBalance() != null && toAfter.compareTo(currency.maxBalance()) > 0) {
                    return new TransferCheckResult(TransferCheckResult.Status.BALANCE_LIMIT, scaled);
                }
                return new TransferCheckResult(TransferCheckResult.Status.ALLOWED, scaled);
            }
        }
    }

    TransferPreviewResult previewTransfer(UUID fromId, UUID toId, BigDecimal rawAmount) {
        return previewTransfer(fromId, toId, defaultCurrencyId(), rawAmount);
    }

    TransferPreviewResult previewTransfer(UUID fromId, UUID toId, String currencyId, BigDecimal rawAmount) {
        EconomyConfigSnapshot currentConfig = configSupplier.get();
        CurrencyDefinition currency = resolveCurrency(currentConfig, currencyId);
        if (currency == null) {
            return new TransferPreviewResult(
                    TransferPreviewResult.Status.UNKNOWN_CURRENCY,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    0,
                    null);
        }

        BigDecimal scaled = scale(rawAmount, currency);
        if (scaled.compareTo(BigDecimal.ZERO) <= 0) {
            return new TransferPreviewResult(
                    TransferPreviewResult.Status.INVALID_AMOUNT,
                    scaled,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    0,
                    null);
        }

        if (fromId.equals(toId)) {
            return new TransferPreviewResult(
                    TransferPreviewResult.Status.SELF_TRANSFER,
                    scaled,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    0,
                    null);
        }

        BigDecimal tax = computeTax(scaled, currentConfig, currency);
        BigDecimal received = scaled.subtract(tax);

        if (currentConfig.payCooldownMs() > 0) {
            long last = lastPayTime.getOrDefault(fromId, 0L);
            long remaining = currentConfig.payCooldownMs() - (System.currentTimeMillis() - last);
            if (remaining > 0) {
                return new TransferPreviewResult(
                        TransferPreviewResult.Status.COOLDOWN,
                        scaled,
                        received,
                        tax,
                        remaining,
                        null);
            }
        }

        BigDecimal minimumAmount = scaledMinimumAmount(currentConfig, currency);
        if (minimumAmount != null && scaled.compareTo(minimumAmount) < 0) {
            return new TransferPreviewResult(
                    TransferPreviewResult.Status.TOO_LOW,
                    scaled,
                    received,
                    tax,
                    0,
                    minimumAmount);
        }

        AccountRecord fromRecord = accountRegistry.getLiveRecord(fromId);
        AccountRecord toRecord = accountRegistry.getLiveRecord(toId);
        if (fromRecord == null || toRecord == null) {
            return new TransferPreviewResult(
                    TransferPreviewResult.Status.ACCOUNT_NOT_FOUND,
                    scaled,
                    received,
                    tax,
                    0,
                    null);
        }

        boolean fromFirst = fromId.compareTo(toId) < 0;
        AccountRecord first = fromFirst ? fromRecord : toRecord;
        AccountRecord second = fromFirst ? toRecord : fromRecord;

        synchronized (first) {
            synchronized (second) {
                if (!accountRegistry.isLive(fromId, fromRecord) || !accountRegistry.isLive(toId, toRecord)) {
                    return new TransferPreviewResult(
                            TransferPreviewResult.Status.ACCOUNT_NOT_FOUND,
                            scaled,
                            received,
                            tax,
                            0,
                            null);
                }

                if (fromRecord.getBalance(currency.id()).compareTo(scaled) < 0) {
                    return new TransferPreviewResult(
                            TransferPreviewResult.Status.INSUFFICIENT_FUNDS,
                            scaled,
                            received,
                            tax,
                            0,
                            null);
                }

                BigDecimal toAfter = toRecord.getBalance(currency.id()).add(received);
                if (currency.maxBalance() != null && toAfter.compareTo(currency.maxBalance()) > 0) {
                    return new TransferPreviewResult(
                            TransferPreviewResult.Status.BALANCE_LIMIT,
                            scaled,
                            received,
                            tax,
                            0,
                            null);
                }

                return new TransferPreviewResult(
                        TransferPreviewResult.Status.ALLOWED,
                        scaled,
                        received,
                        tax,
                        0,
                        null);
            }
        }
    }

    PayResult pay(UUID fromId, UUID toId, BigDecimal rawAmount) {
        return pay(fromId, toId, defaultCurrencyId(), rawAmount);
    }

    PayResult pay(UUID fromId, UUID toId, String currencyId, BigDecimal rawAmount) {
        EconomyConfigSnapshot currentConfig = configSupplier.get();
        CurrencyDefinition currency = resolveCurrency(currentConfig, currencyId);
        if (currency == null) {
            return PayResult.unknownCurrency();
        }

        BigDecimal scaled = scale(rawAmount, currency);
        if (scaled.compareTo(BigDecimal.ZERO) <= 0) {
            return PayResult.invalidAmount();
        }

        if (fromId.equals(toId)) {
            return PayResult.selfTransfer();
        }

        if (currentConfig.payCooldownMs() > 0) {
            long last = lastPayTime.getOrDefault(fromId, 0L);
            long remaining = currentConfig.payCooldownMs() - (System.currentTimeMillis() - last);
            if (remaining > 0) {
                return PayResult.onCooldown(remaining);
            }
            if (last != 0L) {
                lastPayTime.remove(fromId, last);
            }
        }

        BigDecimal minimumAmount = scaledMinimumAmount(currentConfig, currency);
        if (minimumAmount != null && scaled.compareTo(minimumAmount) < 0) {
            return PayResult.tooLow(minimumAmount);
        }

        AccountRecord fromRecord = accountRegistry.getLiveRecord(fromId);
        AccountRecord toRecord = accountRegistry.getLiveRecord(toId);
        if (fromRecord == null || toRecord == null) {
            return PayResult.accountNotFound();
        }
        if (fromRecord.isFrozen() || toRecord.isFrozen()) {
            return PayResult.frozen();
        }

        BigDecimal tax = computeTax(scaled, currentConfig, currency);
        BigDecimal received = scaled.subtract(tax);

        PayEvent event = new PayEvent(fromId, toId, scaled, tax, received, currency.id());
        eventDispatcher.dispatch(event);
        if (event.isCancelled()) {
            return PayResult.cancelled();
        }

        boolean fromFirst = fromId.compareTo(toId) < 0;
        AccountRecord first = fromFirst ? fromRecord : toRecord;
        AccountRecord second = fromFirst ? toRecord : fromRecord;
        PayCompletedEvent completedEvent;

        synchronized (first) {
            synchronized (second) {
                if (!accountRegistry.isLive(fromId, fromRecord) || !accountRegistry.isLive(toId, toRecord)) {
                    return PayResult.accountNotFound();
                }

                if (fromRecord.isFrozen() || toRecord.isFrozen()) {
                    return PayResult.frozen();
                }

                if (currentConfig.payCooldownMs() > 0) {
                    long last = lastPayTime.getOrDefault(fromId, 0L);
                    long remaining = currentConfig.payCooldownMs() - (System.currentTimeMillis() - last);
                    if (remaining > 0) {
                        return PayResult.onCooldown(remaining);
                    }
                    if (last != 0L) {
                        lastPayTime.remove(fromId, last);
                    }
                }

                BigDecimal fromBefore = fromRecord.getBalance(currency.id());
                if (fromBefore.compareTo(scaled) < 0) {
                    return PayResult.insufficientFunds();
                }

                BigDecimal fromAfter = fromBefore.subtract(scaled);
                BigDecimal toBefore = toRecord.getBalance(currency.id());
                BigDecimal toAfter = toBefore.add(received);

                if (currency.maxBalance() != null && toAfter.compareTo(currency.maxBalance()) > 0) {
                    return PayResult.balanceLimit();
                }

                fromRecord.setBalance(currency.id(), fromAfter);
                toRecord.setBalance(currency.id(), toAfter);
                lastPayTime.put(fromId, System.currentTimeMillis());

                long now = System.currentTimeMillis();
                transactionLogger.accept(new TransactionEntry(TransactionType.PAY_SENT, toId, fromId, scaled, fromBefore, fromAfter, now, null, null, currency.id()));
                transactionLogger.accept(new TransactionEntry(TransactionType.PAY_RECEIVED, fromId, toId, received, toBefore, toAfter, now, null, null, currency.id()));
                completedEvent = new PayCompletedEvent(
                        fromId,
                        toId,
                        scaled,
                        received,
                        tax,
                        fromBefore,
                        fromAfter,
                        toBefore,
                        toAfter,
                        currency.id());
            }
        }
        eventDispatcher.dispatch(completedEvent);
        eventDispatcher.dispatch(new BalanceChangedEvent(fromId, completedEvent.getFromBalanceBefore(),
                    completedEvent.getFromBalanceAfter(), BalanceChangeEvent.Reason.PAY_SENT, currency.id()));
        eventDispatcher.dispatch(new BalanceChangedEvent(toId, completedEvent.getToBalanceBefore(),
                    completedEvent.getToBalanceAfter(), BalanceChangeEvent.Reason.PAY_RECEIVED, currency.id()));
        return PayResult.success(scaled, received, tax);
    }

    private static CurrencyDefinition resolveCurrency(EconomyConfigSnapshot config, String currencyId) {
        return config.currencies().find(currencyId).orElse(null);
    }

    private String defaultCurrencyId() {
        return configSupplier.get().currencyId();
    }

    private static BigDecimal scaledMinimumAmount(EconomyConfigSnapshot config, CurrencyDefinition currency) {
        if (config.payMinAmount() == null) {
            return null;
        }
        return config.payMinAmount().setScale(currency.fractionalDigits(), RoundingMode.HALF_UP);
    }

    private static BigDecimal computeTax(BigDecimal scaledAmount, EconomyConfigSnapshot config, CurrencyDefinition currency) {
        return scaledAmount
                .multiply(config.payTaxRate())
                .divide(BigDecimal.valueOf(100), currency.fractionalDigits(), RoundingMode.HALF_UP);
    }

    private static BigDecimal scale(BigDecimal amount, CurrencyDefinition currency) {
        return amount.setScale(currency.fractionalDigits(), RoundingMode.HALF_UP);
    }

    private static EconomyResponse success(BigDecimal amount, BigDecimal balance) {
        return new EconomyResponse(amount, balance, EconomyResponse.ResponseType.SUCCESS, "");
    }

    private static EconomyResponse failure(BigDecimal amount, BigDecimal balance, String message) {
        return new EconomyResponse(amount, balance, EconomyResponse.ResponseType.FAILURE, message);
    }
}