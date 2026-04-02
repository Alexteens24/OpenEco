package dev.alexisbinh.simpleeco.service;

import dev.alexisbinh.simpleeco.api.TransferPreviewResult;
import dev.alexisbinh.simpleeco.api.TransferCheckResult;
import dev.alexisbinh.simpleeco.event.BalanceChangeEvent;
import dev.alexisbinh.simpleeco.event.BalanceChangedEvent;
import dev.alexisbinh.simpleeco.event.PayEvent;
import dev.alexisbinh.simpleeco.event.PayCompletedEvent;
import dev.alexisbinh.simpleeco.model.AccountRecord;
import dev.alexisbinh.simpleeco.model.PayResult;
import dev.alexisbinh.simpleeco.model.TransactionEntry;
import dev.alexisbinh.simpleeco.model.TransactionType;
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
    private final Runnable leaderboardInvalidator;
    private final EventDispatcher eventDispatcher;

    EconomyOperations(AccountRegistry accountRegistry,
                      Supplier<EconomyConfigSnapshot> configSupplier,
                      Map<UUID, Long> lastPayTime,
                      Consumer<TransactionEntry> transactionLogger,
                      Runnable leaderboardInvalidator,
                      EventDispatcher eventDispatcher) {
        this.accountRegistry = accountRegistry;
        this.configSupplier = configSupplier;
        this.lastPayTime = lastPayTime;
        this.transactionLogger = transactionLogger;
        this.leaderboardInvalidator = leaderboardInvalidator;
        this.eventDispatcher = eventDispatcher;
    }

    EconomyResponse canDeposit(UUID id, BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return failure(amount, BigDecimal.ZERO, "Amount must be positive");
        }

        EconomyConfigSnapshot currentConfig = configSupplier.get();
        AccountRecord record = accountRegistry.getLiveRecord(id);
        if (record == null) {
            return failure(amount, BigDecimal.ZERO, "Account not found");
        }

        BigDecimal scaled = scale(amount, currentConfig);
        synchronized (record) {
            if (!accountRegistry.isLive(id, record)) {
                return failure(scaled, BigDecimal.ZERO, "Account not found");
            }

            BigDecimal before = record.getBalance();
            BigDecimal newBalance = before.add(scaled);
            if (currentConfig.maxBalance() != null && newBalance.compareTo(currentConfig.maxBalance()) > 0) {
                return failure(scaled, before, "Balance limit reached");
            }
            return success(scaled, newBalance);
        }
    }

    EconomyResponse canWithdraw(UUID id, BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return failure(amount, BigDecimal.ZERO, "Amount must be positive");
        }

        EconomyConfigSnapshot currentConfig = configSupplier.get();
        AccountRecord record = accountRegistry.getLiveRecord(id);
        if (record == null) {
            return failure(amount, BigDecimal.ZERO, "Account not found");
        }

        BigDecimal scaled = scale(amount, currentConfig);
        synchronized (record) {
            if (!accountRegistry.isLive(id, record)) {
                return failure(scaled, BigDecimal.ZERO, "Account not found");
            }

            BigDecimal before = record.getBalance();
            if (before.compareTo(scaled) < 0) {
                return failure(scaled, before, "Insufficient funds");
            }
            return success(scaled, before.subtract(scaled));
        }
    }

    EconomyResponse deposit(UUID id, BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return failure(amount, BigDecimal.ZERO, "Amount must be positive");
        }

        EconomyConfigSnapshot currentConfig = configSupplier.get();
        AccountRecord record = accountRegistry.getLiveRecord(id);
        if (record == null) {
            return failure(amount, BigDecimal.ZERO, "Account not found");
        }

        BigDecimal scaled = scale(amount, currentConfig);
        BalanceChangedEvent completedEvent;
        synchronized (record) {
            if (!accountRegistry.isLive(id, record)) {
                return failure(scaled, BigDecimal.ZERO, "Account not found");
            }

            BigDecimal before = record.getBalance();
            BigDecimal newBalance = before.add(scaled);
            if (currentConfig.maxBalance() != null && newBalance.compareTo(currentConfig.maxBalance()) > 0) {
                return failure(scaled, before, "Balance limit reached");
            }

            BalanceChangeEvent event = new BalanceChangeEvent(id, before, newBalance, BalanceChangeEvent.Reason.GIVE);
            eventDispatcher.dispatch(event);
            if (event.isCancelled()) {
                return failure(scaled, before, "Cancelled by plugin");
            }

            record.setBalance(newBalance);
            leaderboardInvalidator.run();
            transactionLogger.accept(new TransactionEntry(TransactionType.GIVE, null, id, scaled, before, newBalance,
                    System.currentTimeMillis()));
            completedEvent = new BalanceChangedEvent(id, before, newBalance, BalanceChangeEvent.Reason.GIVE);
        }
        eventDispatcher.dispatch(completedEvent);
        return success(scaled, completedEvent.getNewBalance());
    }

    EconomyResponse withdraw(UUID id, BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return failure(amount, BigDecimal.ZERO, "Amount must be positive");
        }

        EconomyConfigSnapshot currentConfig = configSupplier.get();
        AccountRecord record = accountRegistry.getLiveRecord(id);
        if (record == null) {
            return failure(amount, BigDecimal.ZERO, "Account not found");
        }

        BigDecimal scaled = scale(amount, currentConfig);
        BalanceChangedEvent completedEvent;
        synchronized (record) {
            if (!accountRegistry.isLive(id, record)) {
                return failure(scaled, BigDecimal.ZERO, "Account not found");
            }
            if (record.getBalance().compareTo(scaled) < 0) {
                return failure(scaled, record.getBalance(), "Insufficient funds");
            }

            BigDecimal before = record.getBalance();
            BigDecimal newBalance = before.subtract(scaled);
            BalanceChangeEvent event = new BalanceChangeEvent(id, before, newBalance, BalanceChangeEvent.Reason.TAKE);
            eventDispatcher.dispatch(event);
            if (event.isCancelled()) {
                return failure(scaled, before, "Cancelled by plugin");
            }

            record.setBalance(newBalance);
            leaderboardInvalidator.run();
            transactionLogger.accept(new TransactionEntry(TransactionType.TAKE, null, id, scaled, before, newBalance,
                    System.currentTimeMillis()));
            completedEvent = new BalanceChangedEvent(id, before, newBalance, BalanceChangeEvent.Reason.TAKE);
        }
        eventDispatcher.dispatch(completedEvent);
        return success(scaled, completedEvent.getNewBalance());
    }

    EconomyResponse set(UUID id, BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            return failure(amount, BigDecimal.ZERO, "Amount cannot be negative");
        }

        EconomyConfigSnapshot currentConfig = configSupplier.get();
        AccountRecord record = accountRegistry.getLiveRecord(id);
        if (record == null) {
            return failure(amount, BigDecimal.ZERO, "Account not found");
        }

        BigDecimal scaled = scale(amount, currentConfig);
        BalanceChangedEvent completedEvent;
        synchronized (record) {
            if (!accountRegistry.isLive(id, record)) {
                return failure(scaled, BigDecimal.ZERO, "Account not found");
            }

            BigDecimal before = record.getBalance();
            if (currentConfig.maxBalance() != null && scaled.compareTo(currentConfig.maxBalance()) > 0) {
                return failure(scaled, before, "Balance limit reached");
            }

            BalanceChangeEvent event = new BalanceChangeEvent(id, before, scaled, BalanceChangeEvent.Reason.SET);
            eventDispatcher.dispatch(event);
            if (event.isCancelled()) {
                return failure(scaled, before, "Cancelled by plugin");
            }

            record.setBalance(scaled);
            leaderboardInvalidator.run();
            transactionLogger.accept(new TransactionEntry(TransactionType.SET, null, id, scaled, before, scaled,
                    System.currentTimeMillis()));
            completedEvent = new BalanceChangedEvent(id, before, scaled, BalanceChangeEvent.Reason.SET);
        }
        eventDispatcher.dispatch(completedEvent);
        return success(scaled, completedEvent.getNewBalance());
    }

    EconomyResponse reset(UUID id) {
        EconomyConfigSnapshot currentConfig = configSupplier.get();
        AccountRecord record = accountRegistry.getLiveRecord(id);
        if (record == null) {
            return failure(BigDecimal.ZERO, BigDecimal.ZERO, "Account not found");
        }

        BigDecimal startingBalance = currentConfig.startingBalance();
        BalanceChangedEvent completedEvent;
        synchronized (record) {
            if (!accountRegistry.isLive(id, record)) {
                return failure(BigDecimal.ZERO, BigDecimal.ZERO, "Account not found");
            }

            BigDecimal before = record.getBalance();
            BalanceChangeEvent event = new BalanceChangeEvent(id, before, startingBalance, BalanceChangeEvent.Reason.RESET);
            eventDispatcher.dispatch(event);
            if (event.isCancelled()) {
                return failure(BigDecimal.ZERO, before, "Cancelled by plugin");
            }

            record.setBalance(startingBalance);
            leaderboardInvalidator.run();
            transactionLogger.accept(new TransactionEntry(TransactionType.RESET, null, id, startingBalance, before,
                    startingBalance, System.currentTimeMillis()));
            completedEvent = new BalanceChangedEvent(id, before, startingBalance, BalanceChangeEvent.Reason.RESET);
        }
        eventDispatcher.dispatch(completedEvent);
        return success(startingBalance, completedEvent.getNewBalance());
    }

    TransferCheckResult canTransfer(UUID fromId, UUID toId, BigDecimal rawAmount) {
        if (rawAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return new TransferCheckResult(TransferCheckResult.Status.INVALID_AMOUNT, rawAmount);
        }
        if (fromId.equals(toId)) {
            return new TransferCheckResult(TransferCheckResult.Status.SELF_TRANSFER, rawAmount);
        }

        EconomyConfigSnapshot currentConfig = configSupplier.get();
        BigDecimal scaled = scale(rawAmount, currentConfig);

        AccountRecord fromRecord = accountRegistry.getLiveRecord(fromId);
        AccountRecord toRecord = accountRegistry.getLiveRecord(toId);
        if (fromRecord == null || toRecord == null) {
            return new TransferCheckResult(TransferCheckResult.Status.ACCOUNT_NOT_FOUND, scaled);
        }

        BigDecimal tax = scaled
                .multiply(currentConfig.payTaxRate())
                .divide(BigDecimal.valueOf(100), currentConfig.fractionalDigits(), RoundingMode.HALF_UP);
        BigDecimal received = scaled.subtract(tax);

        boolean fromFirst = fromId.compareTo(toId) < 0;
        AccountRecord first = fromFirst ? fromRecord : toRecord;
        AccountRecord second = fromFirst ? toRecord : fromRecord;

        synchronized (first) {
            synchronized (second) {
                if (!accountRegistry.isLive(fromId, fromRecord) || !accountRegistry.isLive(toId, toRecord)) {
                    return new TransferCheckResult(TransferCheckResult.Status.ACCOUNT_NOT_FOUND, scaled);
                }
                if (fromRecord.getBalance().compareTo(scaled) < 0) {
                    return new TransferCheckResult(TransferCheckResult.Status.INSUFFICIENT_FUNDS, scaled);
                }
                BigDecimal toAfter = toRecord.getBalance().add(received);
                if (currentConfig.maxBalance() != null && toAfter.compareTo(currentConfig.maxBalance()) > 0) {
                    return new TransferCheckResult(TransferCheckResult.Status.BALANCE_LIMIT, scaled);
                }
                return new TransferCheckResult(TransferCheckResult.Status.ALLOWED, scaled);
            }
        }
    }

    TransferPreviewResult previewTransfer(UUID fromId, UUID toId, BigDecimal rawAmount) {
        EconomyConfigSnapshot currentConfig = configSupplier.get();
        if (rawAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return new TransferPreviewResult(
                    TransferPreviewResult.Status.INVALID_AMOUNT,
                    rawAmount,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    0,
                    null);
        }

        BigDecimal scaled = scale(rawAmount, currentConfig);
        if (fromId.equals(toId)) {
            return new TransferPreviewResult(
                    TransferPreviewResult.Status.SELF_TRANSFER,
                    scaled,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    0,
                    null);
        }

        BigDecimal tax = scaled
                .multiply(currentConfig.payTaxRate())
                .divide(BigDecimal.valueOf(100), currentConfig.fractionalDigits(), RoundingMode.HALF_UP);
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

        if (currentConfig.payMinAmount() != null && scaled.compareTo(currentConfig.payMinAmount()) < 0) {
            return new TransferPreviewResult(
                    TransferPreviewResult.Status.TOO_LOW,
                    scaled,
                    received,
                    tax,
                    0,
                    currentConfig.payMinAmount());
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

                if (fromRecord.getBalance().compareTo(scaled) < 0) {
                    return new TransferPreviewResult(
                            TransferPreviewResult.Status.INSUFFICIENT_FUNDS,
                            scaled,
                            received,
                            tax,
                            0,
                            null);
                }

                BigDecimal toAfter = toRecord.getBalance().add(received);
                if (currentConfig.maxBalance() != null && toAfter.compareTo(currentConfig.maxBalance()) > 0) {
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
        if (rawAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return PayResult.invalidAmount();
        }

        if (fromId.equals(toId)) {
            return PayResult.selfTransfer();
        }

        EconomyConfigSnapshot currentConfig = configSupplier.get();
        if (currentConfig.payCooldownMs() > 0) {
            long last = lastPayTime.getOrDefault(fromId, 0L);
            long remaining = currentConfig.payCooldownMs() - (System.currentTimeMillis() - last);
            if (remaining > 0) {
                return PayResult.onCooldown(remaining);
            }
        }

        BigDecimal scaled = scale(rawAmount, currentConfig);
        if (currentConfig.payMinAmount() != null && scaled.compareTo(currentConfig.payMinAmount()) < 0) {
            return PayResult.tooLow(currentConfig.payMinAmount());
        }

        AccountRecord fromRecord = accountRegistry.getLiveRecord(fromId);
        AccountRecord toRecord = accountRegistry.getLiveRecord(toId);
        if (fromRecord == null || toRecord == null) {
            return PayResult.accountNotFound();
        }

        BigDecimal tax = scaled
                .multiply(currentConfig.payTaxRate())
                .divide(BigDecimal.valueOf(100), currentConfig.fractionalDigits(), RoundingMode.HALF_UP);
        BigDecimal received = scaled.subtract(tax);

        boolean fromFirst = fromId.compareTo(toId) < 0;
        AccountRecord first = fromFirst ? fromRecord : toRecord;
        AccountRecord second = fromFirst ? toRecord : fromRecord;
        PayCompletedEvent completedEvent;

        synchronized (first) {
            synchronized (second) {
                if (!accountRegistry.isLive(fromId, fromRecord) || !accountRegistry.isLive(toId, toRecord)) {
                    return PayResult.accountNotFound();
                }

                BigDecimal fromBefore = fromRecord.getBalance();
                if (fromBefore.compareTo(scaled) < 0) {
                    return PayResult.insufficientFunds();
                }

                BigDecimal fromAfter = fromBefore.subtract(scaled);
                BigDecimal toBefore = toRecord.getBalance();
                BigDecimal toAfter = toBefore.add(received);

                if (currentConfig.maxBalance() != null && toAfter.compareTo(currentConfig.maxBalance()) > 0) {
                    return PayResult.balanceLimit();
                }

                PayEvent event = new PayEvent(fromId, toId, scaled, tax, received);
                eventDispatcher.dispatch(event);
                if (event.isCancelled()) {
                    return PayResult.cancelled();
                }

                fromRecord.setBalance(fromAfter);
                toRecord.setBalance(toAfter);
                lastPayTime.put(fromId, System.currentTimeMillis());
                leaderboardInvalidator.run();

                long now = System.currentTimeMillis();
                transactionLogger.accept(new TransactionEntry(TransactionType.PAY_SENT, toId, fromId, scaled, fromBefore, fromAfter, now));
                transactionLogger.accept(new TransactionEntry(TransactionType.PAY_RECEIVED, fromId, toId, received, toBefore, toAfter, now));
        completedEvent = new PayCompletedEvent(
                        fromId,
                        toId,
                        scaled,
                        received,
                        tax,
                        fromBefore,
                        fromAfter,
                        toBefore,
                        toAfter);
            }
        }
        eventDispatcher.dispatch(completedEvent);
        return PayResult.success(scaled, received, tax);
    }

    private static BigDecimal scale(BigDecimal amount, EconomyConfigSnapshot config) {
        return amount.setScale(config.fractionalDigits(), RoundingMode.HALF_UP);
    }

    private static EconomyResponse success(BigDecimal amount, BigDecimal balance) {
        return new EconomyResponse(amount, balance, EconomyResponse.ResponseType.SUCCESS, "");
    }

    private static EconomyResponse failure(BigDecimal amount, BigDecimal balance, String message) {
        return new EconomyResponse(amount, balance, EconomyResponse.ResponseType.FAILURE, message);
    }
}