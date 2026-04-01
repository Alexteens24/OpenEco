package dev.alexisbinh.simpleeco.api;

import dev.alexisbinh.simpleeco.model.AccountRecord;
import dev.alexisbinh.simpleeco.model.PayResult;
import dev.alexisbinh.simpleeco.model.TransactionEntry;
import dev.alexisbinh.simpleeco.model.TransactionType;
import dev.alexisbinh.simpleeco.service.AccountService;
import net.milkbowl.vault2.economy.EconomyResponse;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class SimpleEcoApiImpl implements SimpleEcoApi {

    private final AccountService service;

    public SimpleEcoApiImpl(AccountService service) {
        this.service = service;
    }

    @Override
    public boolean hasAccount(UUID accountId) {
        return service.hasAccount(requireAccountId(accountId));
    }

    @Override
    public Optional<AccountSnapshot> getAccount(UUID accountId) {
        return service.getAccount(requireAccountId(accountId)).map(SimpleEcoApiImpl::toAccountSnapshot);
    }

    @Override
    public Optional<AccountSnapshot> findByName(String name) {
        return service.findByName(requireName(name)).map(SimpleEcoApiImpl::toAccountSnapshot);
    }

    @Override
    public AccountOperationResult createAccount(UUID accountId, String name) {
        UUID validatedId = requireAccountId(accountId);
        String validatedName = requireName(name);

        return switch (service.createAccountDetailed(validatedId, validatedName)) {
            case CREATED -> getAccount(validatedId)
                    .map(AccountOperationResult::created)
                    .orElseGet(() -> AccountOperationResult.failed(null, "Account was created but could not be reloaded"));
            case ALREADY_EXISTS -> getAccount(validatedId)
                    .map(AccountOperationResult::alreadyExists)
                    .orElseGet(() -> AccountOperationResult.failed(null, "Account already exists but could not be reloaded"));
            case NAME_IN_USE -> AccountOperationResult.nameInUse(null);
            case INVALID_NAME -> AccountOperationResult.failed(null, "Invalid account name");
        };
    }

    @Override
    public AccountOperationResult renameAccount(UUID accountId, String newName) {
        UUID validatedId = requireAccountId(accountId);
        String validatedName = requireName(newName);

        Optional<AccountSnapshot> currentOpt = getAccount(validatedId);
        if (currentOpt.isEmpty()) {
            return AccountOperationResult.notFound();
        }

        AccountSnapshot current = currentOpt.get();
        if (current.lastKnownName().equals(validatedName)) {
            return AccountOperationResult.unchanged(current);
        }

        return switch (service.renameAccountDetailed(validatedId, validatedName)) {
            case RENAMED -> getAccount(validatedId)
                    .map(AccountOperationResult::renamed)
                    .orElseGet(() -> AccountOperationResult.failed(current, "Account was renamed but could not be reloaded"));
            case NOT_FOUND -> AccountOperationResult.notFound();
            case UNCHANGED -> AccountOperationResult.unchanged(current);
            case NAME_IN_USE -> AccountOperationResult.nameInUse(current);
            case INVALID_NAME -> AccountOperationResult.failed(current, "Invalid account name");
            case CANCELLED -> AccountOperationResult.failed(current, "Account rename was cancelled or could not be applied");
        };
    }

    @Override
    public AccountOperationResult deleteAccount(UUID accountId) {
        UUID validatedId = requireAccountId(accountId);

        Optional<AccountSnapshot> currentOpt = getAccount(validatedId);
        if (currentOpt.isEmpty()) {
            return AccountOperationResult.notFound();
        }

        AccountSnapshot current = currentOpt.get();
        return switch (service.deleteAccountDetailed(validatedId)) {
            case DELETED -> AccountOperationResult.deleted(current);
            case NOT_FOUND -> AccountOperationResult.notFound();
            case FAILED -> AccountOperationResult.failed(current, "Account delete failed");
        };
    }

    @Override
    public BigDecimal getBalance(UUID accountId) {
        return service.getBalance(requireAccountId(accountId));
    }

    @Override
    public boolean has(UUID accountId, BigDecimal amount) {
        return service.has(requireAccountId(accountId), requireNonNegativeAmount(amount));
    }

    @Override
    public BalanceCheckResult canDeposit(UUID accountId, BigDecimal amount) {
        UUID validatedId = requireAccountId(accountId);
        BigDecimal validatedAmount = requireAmount(amount);
        BigDecimal currentBalance = getAccount(validatedId).map(AccountSnapshot::balance).orElse(BigDecimal.ZERO);
        EconomyResponse response = service.canDeposit(validatedId, validatedAmount);
        return new BalanceCheckResult(
                mapCheckStatus(response),
                response.amount,
                currentBalance,
                response.transactionSuccess() ? response.balance : currentBalance);
    }

    @Override
    public BalanceCheckResult canWithdraw(UUID accountId, BigDecimal amount) {
        UUID validatedId = requireAccountId(accountId);
        BigDecimal validatedAmount = requireAmount(amount);
        BigDecimal currentBalance = getAccount(validatedId).map(AccountSnapshot::balance).orElse(BigDecimal.ZERO);
        EconomyResponse response = service.canWithdraw(validatedId, validatedAmount);
        return new BalanceCheckResult(
                mapCheckStatus(response),
                response.amount,
                currentBalance,
                response.transactionSuccess() ? response.balance : currentBalance);
    }

    @Override
    public BalanceChangeResult deposit(UUID accountId, BigDecimal amount) {
        return applyBalanceChange(requireAccountId(accountId), requireAmount(amount), service::deposit);
    }

    @Override
    public BalanceChangeResult withdraw(UUID accountId, BigDecimal amount) {
        return applyBalanceChange(requireAccountId(accountId), requireAmount(amount), service::withdraw);
    }

    @Override
    public BalanceChangeResult setBalance(UUID accountId, BigDecimal amount) {
        return applyBalanceChange(requireAccountId(accountId), requireAmount(amount), service::set);
    }

    @Override
    public BalanceChangeResult reset(UUID accountId) {
        UUID validatedId = requireAccountId(accountId);
        BigDecimal previousBalance = getAccount(validatedId).map(AccountSnapshot::balance).orElse(BigDecimal.ZERO);
        EconomyResponse response = service.reset(validatedId);
        return new BalanceChangeResult(
                mapChangeStatus(response),
                response.amount,
                previousBalance,
                response.transactionSuccess() ? response.balance : previousBalance);
    }

    @Override
    public TransferResult transfer(UUID fromId, UUID toId, BigDecimal amount) {
        PayResult result = service.pay(requireAccountId(fromId), requireAccountId(toId), requireAmount(amount));
        return new TransferResult(
                mapTransferStatus(result.getStatus()),
                result.getSent(),
                result.getReceived(),
                result.getTax(),
                result.getCooldownRemainingMs());
    }

    @Override
    public HistoryPage getHistory(UUID accountId, int page, int pageSize) {
        UUID validatedId = requireAccountId(accountId);
        int validatedPage = requirePositive(page, "page");
        int validatedPageSize = requirePositive(pageSize, "pageSize");
        try {
            int totalEntries = service.countTransactions(validatedId);
            List<TransactionSnapshot> entries = service.getTransactions(validatedId, validatedPage, validatedPageSize)
                    .stream()
                    .map(SimpleEcoApiImpl::toTransactionSnapshot)
                    .toList();
            int totalPages = totalEntries == 0 ? 0 : (int) Math.ceil(totalEntries / (double) validatedPageSize);
            return new HistoryPage(validatedPage, validatedPageSize, totalEntries, totalPages, entries);
        } catch (SQLException e) {
            throw new SimpleEcoApiException("Failed to load account history", e);
        }
    }

    @Override
    public HistoryPage getHistory(UUID accountId, int page, int pageSize, HistoryFilter filter) {
        if (filter == null || filter.equals(HistoryFilter.NONE)) {
            return getHistory(accountId, page, pageSize);
        }
        UUID validatedId = requireAccountId(accountId);
        int validatedPage = requirePositive(page, "page");
        int validatedPageSize = requirePositive(pageSize, "pageSize");
        TransactionType type = filter.kind() != null ? mapTransactionType(filter.kind()) : null;
        try {
            int totalEntries = service.countTransactions(validatedId, type, filter.fromMs(), filter.toMs());
            List<TransactionSnapshot> entries = service.getTransactions(
                    validatedId, validatedPage, validatedPageSize, type, filter.fromMs(), filter.toMs())
                    .stream()
                    .map(SimpleEcoApiImpl::toTransactionSnapshot)
                    .toList();
            int totalPages = totalEntries == 0 ? 0 : (int) Math.ceil(totalEntries / (double) validatedPageSize);
            return new HistoryPage(validatedPage, validatedPageSize, totalEntries, totalPages, entries);
        } catch (SQLException e) {
            throw new SimpleEcoApiException("Failed to load filtered account history", e);
        }
    }

    @Override
    public int getRankOf(UUID accountId) {
        return service.getRankOf(requireAccountId(accountId));
    }

    @Override
    public Map<UUID, String> getUUIDNameMap() {
        return service.getUUIDNameMap();
    }

    @Override
    public TransferCheckResult canTransfer(UUID fromId, UUID toId, BigDecimal amount) {
        return service.canTransfer(requireAccountId(fromId), requireAccountId(toId), requireAmount(amount));
    }

    @Override
    public void logCustomTransaction(UUID accountId, BigDecimal amount, TransactionKind kind) {
        UUID validatedId = requireAccountId(accountId);
        BigDecimal validatedAmount = requirePositiveAmount(amount);
        Objects.requireNonNull(kind, "kind");

        Optional<AccountSnapshot> accountOpt = getAccount(validatedId);
        if (accountOpt.isEmpty()) {
            throw new SimpleEcoApiException("Account not found: " + validatedId);
        }
        BigDecimal currentBalance = accountOpt.get().balance();

        TransactionEntry entry = new TransactionEntry(
                mapTransactionType(kind),
                null,
                validatedId,
                validatedAmount,
                currentBalance,
                currentBalance,
                System.currentTimeMillis());
        service.logCustomTransaction(validatedId, entry);
    }

    @Override
    public List<AccountSnapshot> getTopAccounts(int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must be >= 0");
        }
        if (limit == 0) {
            return List.of();
        }
        return service.getBalTopSnapshot().stream()
                .limit(limit)
                .map(SimpleEcoApiImpl::toAccountSnapshot)
                .toList();
    }

    @Override
    public CurrencyInfo getCurrencyInfo() {
        return new CurrencyInfo(
                service.getCurrencyId(),
                service.getCurrencySingular(),
                service.getCurrencyPlural(),
                service.getFractionalDigits(),
                service.getStartingBalance(),
                service.getMaxBalance());
    }

    @Override
    public String format(BigDecimal amount) {
        return service.format(requireAmount(amount));
    }

    private BalanceChangeResult applyBalanceChange(UUID accountId, BigDecimal amount,
                                                   BalanceMutation mutation) {
        BigDecimal previousBalance = getAccount(accountId).map(AccountSnapshot::balance).orElse(BigDecimal.ZERO);
        EconomyResponse response = mutation.apply(accountId, amount);
        return new BalanceChangeResult(
                mapChangeStatus(response),
                response.amount,
                previousBalance,
                response.transactionSuccess() ? response.balance : previousBalance);
    }

    private static AccountSnapshot toAccountSnapshot(AccountRecord record) {
        return new AccountSnapshot(
                record.getId(),
                record.getLastKnownName(),
                record.getBalance(),
                record.getCreatedAt(),
                record.getUpdatedAt());
    }

    private static TransactionSnapshot toTransactionSnapshot(TransactionEntry entry) {
        return new TransactionSnapshot(
                mapTransactionKind(entry.getType()),
                entry.getCounterpartId(),
                entry.getTargetId(),
                entry.getAmount(),
                entry.getBalanceBefore(),
                entry.getBalanceAfter(),
                entry.getTimestamp());
    }

    private static TransactionKind mapTransactionKind(TransactionType type) {
        return switch (type) {
            case GIVE -> TransactionKind.GIVE;
            case TAKE -> TransactionKind.TAKE;
            case SET -> TransactionKind.SET;
            case RESET -> TransactionKind.RESET;
            case PAY_SENT -> TransactionKind.PAY_SENT;
            case PAY_RECEIVED -> TransactionKind.PAY_RECEIVED;
        };
    }

    private static TransactionType mapTransactionType(TransactionKind kind) {
        return switch (kind) {
            case GIVE -> TransactionType.GIVE;
            case TAKE -> TransactionType.TAKE;
            case SET -> TransactionType.SET;
            case RESET -> TransactionType.RESET;
            case PAY_SENT -> TransactionType.PAY_SENT;
            case PAY_RECEIVED -> TransactionType.PAY_RECEIVED;
        };
    }

    private static BalanceCheckResult.Status mapCheckStatus(EconomyResponse response) {
        if (response.transactionSuccess()) {
            return BalanceCheckResult.Status.ALLOWED;
        }
        return switch (response.errorMessage) {
            case "Account not found" -> BalanceCheckResult.Status.ACCOUNT_NOT_FOUND;
            case "Amount must be positive", "Amount cannot be negative" -> BalanceCheckResult.Status.INVALID_AMOUNT;
            case "Insufficient funds" -> BalanceCheckResult.Status.INSUFFICIENT_FUNDS;
            case "Balance limit reached" -> BalanceCheckResult.Status.BALANCE_LIMIT;
            default -> throw new SimpleEcoApiException("Unexpected balance check failure: " + response.errorMessage);
        };
    }

    private static BalanceChangeResult.Status mapChangeStatus(EconomyResponse response) {
        if (response.transactionSuccess()) {
            return BalanceChangeResult.Status.SUCCESS;
        }
        return switch (response.errorMessage) {
            case "Account not found" -> BalanceChangeResult.Status.ACCOUNT_NOT_FOUND;
            case "Amount must be positive", "Amount cannot be negative" -> BalanceChangeResult.Status.INVALID_AMOUNT;
            case "Insufficient funds" -> BalanceChangeResult.Status.INSUFFICIENT_FUNDS;
            case "Balance limit reached" -> BalanceChangeResult.Status.BALANCE_LIMIT;
            case "Cancelled by plugin" -> BalanceChangeResult.Status.CANCELLED;
            default -> throw new SimpleEcoApiException("Unexpected balance change failure: " + response.errorMessage);
        };
    }

    private static TransferResult.Status mapTransferStatus(PayResult.Status status) {
        return switch (status) {
            case SUCCESS -> TransferResult.Status.SUCCESS;
            case COOLDOWN -> TransferResult.Status.COOLDOWN;
            case INSUFFICIENT_FUNDS -> TransferResult.Status.INSUFFICIENT_FUNDS;
            case ACCOUNT_NOT_FOUND -> TransferResult.Status.ACCOUNT_NOT_FOUND;
            case BALANCE_LIMIT -> TransferResult.Status.BALANCE_LIMIT;
            case CANCELLED -> TransferResult.Status.CANCELLED;
            case TOO_LOW -> TransferResult.Status.TOO_LOW;
            case INVALID_AMOUNT -> TransferResult.Status.INVALID_AMOUNT;
            case SELF_TRANSFER -> TransferResult.Status.SELF_TRANSFER;
        };
    }

    private static UUID requireAccountId(UUID accountId) {
        return Objects.requireNonNull(accountId, "accountId");
    }

    private static BigDecimal requireAmount(BigDecimal amount) {
        return Objects.requireNonNull(amount, "amount");
    }

    private static BigDecimal requireNonNegativeAmount(BigDecimal amount) {
        BigDecimal validatedAmount = requireAmount(amount);
        if (validatedAmount.signum() < 0) {
            throw new IllegalArgumentException("amount must be >= 0");
        }
        return validatedAmount;
    }

    private static BigDecimal requirePositiveAmount(BigDecimal amount) {
        BigDecimal validatedAmount = requireAmount(amount);
        if (validatedAmount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be > 0");
        }
        return validatedAmount;
    }

    private static String requireName(String name) {
        Objects.requireNonNull(name, "name");
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (trimmed.length() > AccountRecord.MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("name must not exceed " + AccountRecord.MAX_NAME_LENGTH + " characters");
        }
        return trimmed;
    }

    private static int requirePositive(int value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be > 0");
        }
        return value;
    }

    @FunctionalInterface
    private interface BalanceMutation {
        EconomyResponse apply(UUID accountId, BigDecimal amount);
    }
}