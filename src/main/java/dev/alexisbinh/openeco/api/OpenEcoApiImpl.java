package dev.alexisbinh.openeco.api;

import dev.alexisbinh.openeco.model.AccountRecord;
import dev.alexisbinh.openeco.model.PayResult;
import dev.alexisbinh.openeco.model.TransactionEntry;
import dev.alexisbinh.openeco.model.TransactionType;
import dev.alexisbinh.openeco.service.AccountService;
import net.milkbowl.vault2.economy.EconomyResponse;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class OpenEcoApiImpl implements OpenEcoApi {

    private final AccountService service;

    public OpenEcoApiImpl(AccountService service) {
        this.service = service;
    }

    @Override
    public boolean hasAccount(UUID accountId) {
        return service.hasAccount(requireAccountId(accountId));
    }

    @Override
    public Optional<AccountSnapshot> getAccount(UUID accountId) {
        return service.getAccount(requireAccountId(accountId)).map(OpenEcoApiImpl::toAccountSnapshot);
    }

    @Override
    public Optional<AccountSnapshot> findByName(String name) {
        return service.findByName(requireName(name)).map(OpenEcoApiImpl::toAccountSnapshot);
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
    public AccountOperationResult ensureAccount(UUID accountId, String name) {
        UUID validatedId = requireAccountId(accountId);
        String validatedName = requireName(name);

        Optional<AccountSnapshot> currentOpt = getAccount(validatedId);
        if (currentOpt.isPresent()) {
            return reconcileExistingAccount(validatedId, validatedName, currentOpt.get());
        }

        return switch (service.createAccountDetailed(validatedId, validatedName)) {
            case CREATED -> getAccount(validatedId)
                    .map(AccountOperationResult::created)
                    .orElseGet(() -> AccountOperationResult.failed(null, "Account was created but could not be reloaded"));
            case ALREADY_EXISTS -> getAccount(validatedId)
                    .map(current -> reconcileExistingAccount(validatedId, validatedName, current))
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

        return reconcileExistingAccount(validatedId, validatedName, currentOpt.get());
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
    public BigDecimal getBalance(UUID accountId, String currencyId) {
        UUID validatedId = requireAccountId(accountId);
        String validatedCurrencyId = requireKnownCurrency(currencyId);
        return service.getBalance(validatedId, validatedCurrencyId);
    }

    @Override
    public boolean has(UUID accountId, BigDecimal amount) {
        return service.has(requireAccountId(accountId), requireNonNegativeAmount(amount));
    }

    @Override
    public boolean has(UUID accountId, String currencyId, BigDecimal amount) {
        return service.has(requireAccountId(accountId), requireKnownCurrency(currencyId), requireNonNegativeAmount(amount));
    }

    @Override
    public BalanceCheckResult canDeposit(UUID accountId, BigDecimal amount) {
        return service.canDeposit(requireAccountId(accountId), requireAmount(amount));
    }

    @Override
    public BalanceCheckResult canDeposit(UUID accountId, String currencyId, BigDecimal amount) {
        return service.canDeposit(requireAccountId(accountId), requireKnownCurrency(currencyId), requireAmount(amount));
    }

    @Override
    public BalanceCheckResult canWithdraw(UUID accountId, BigDecimal amount) {
        return service.canWithdraw(requireAccountId(accountId), requireAmount(amount));
    }

    @Override
    public BalanceCheckResult canWithdraw(UUID accountId, String currencyId, BigDecimal amount) {
        return service.canWithdraw(requireAccountId(accountId), requireKnownCurrency(currencyId), requireAmount(amount));
    }

    @Override
    public BalanceChangeResult deposit(UUID accountId, BigDecimal amount) {
        return applyBalanceChange(requireAccountId(accountId), requireAmount(amount), service::deposit);
    }

    @Override
    public BalanceChangeResult deposit(UUID accountId, String currencyId, BigDecimal amount) {
        UUID validatedId = requireAccountId(accountId);
        String validatedCurrencyId = requireKnownCurrency(currencyId);
        BigDecimal validatedAmount = requireAmount(amount);
        return applyBalanceChange(validatedId, validatedCurrencyId, validatedAmount, service::deposit);
    }

    @Override
    public BalanceChangeResult withdraw(UUID accountId, BigDecimal amount) {
        return applyBalanceChange(requireAccountId(accountId), requireAmount(amount), service::withdraw);
    }

    @Override
    public BalanceChangeResult withdraw(UUID accountId, String currencyId, BigDecimal amount) {
        UUID validatedId = requireAccountId(accountId);
        String validatedCurrencyId = requireKnownCurrency(currencyId);
        BigDecimal validatedAmount = requireAmount(amount);
        return applyBalanceChange(validatedId, validatedCurrencyId, validatedAmount, service::withdraw);
    }

    @Override
    public BalanceChangeResult setBalance(UUID accountId, BigDecimal amount) {
        return applyBalanceChange(requireAccountId(accountId), requireAmount(amount), service::set);
    }

    @Override
    public BalanceChangeResult setBalance(UUID accountId, String currencyId, BigDecimal amount) {
        UUID validatedId = requireAccountId(accountId);
        String validatedCurrencyId = requireKnownCurrency(currencyId);
        BigDecimal validatedAmount = requireAmount(amount);
        return applyBalanceChange(validatedId, validatedCurrencyId, validatedAmount, service::set);
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
    public BalanceChangeResult reset(UUID accountId, String currencyId) {
        UUID validatedId = requireAccountId(accountId);
        String validatedCurrencyId = requireKnownCurrency(currencyId);
        BigDecimal previousBalance = service.getBalance(validatedId, validatedCurrencyId);
        EconomyResponse response = service.reset(validatedId, validatedCurrencyId);
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
    public TransferResult transfer(UUID fromId, UUID toId, String currencyId, BigDecimal amount) {
        PayResult result = service.pay(
                requireAccountId(fromId),
                requireAccountId(toId),
                requireKnownCurrency(currencyId),
                requireAmount(amount));
        return new TransferResult(
                mapTransferStatus(result.getStatus()),
                result.getSent(),
                result.getReceived(),
                result.getTax(),
                result.getCooldownRemainingMs());
    }

    @Override
    public TransferPreviewResult previewTransfer(UUID fromId, UUID toId, BigDecimal amount) {
        return service.previewTransfer(requireAccountId(fromId), requireAccountId(toId), requireAmount(amount));
    }

    @Override
    public TransferPreviewResult previewTransfer(UUID fromId, UUID toId, String currencyId, BigDecimal amount) {
        return service.previewTransfer(
                requireAccountId(fromId),
                requireAccountId(toId),
                requireKnownCurrency(currencyId),
                requireAmount(amount));
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
                    .map(OpenEcoApiImpl::toTransactionSnapshot)
                    .toList();
            int totalPages = totalEntries == 0 ? 0 : (int) Math.ceil(totalEntries / (double) validatedPageSize);
            return new HistoryPage(validatedPage, validatedPageSize, totalEntries, totalPages, entries);
        } catch (SQLException e) {
            throw new OpenEcoApiException("Failed to load account history", e);
        }
    }

    @Override
    public HistoryPage getHistory(UUID accountId, String currencyId, int page, int pageSize) {
        return loadHistoryPage(requireAccountId(accountId), requireKnownCurrency(currencyId), page, pageSize, null);
    }

    @Override
    public HistoryPage getHistory(UUID accountId, int page, int pageSize, HistoryFilter filter) {
        if (filter == null || filter.equals(HistoryFilter.NONE)) {
            return getHistory(accountId, page, pageSize);
        }

        if (filter.currencyId() == null) {
            UUID validatedId = requireAccountId(accountId);
            int validatedPage = requirePositive(page, "page");
            int validatedPageSize = requirePositive(pageSize, "pageSize");
            TransactionType type = filter.kind() != null ? mapTransactionType(filter.kind()) : null;
            try {
                int totalEntries = service.countTransactions(validatedId, type, filter.fromMs(), filter.toMs());
                List<TransactionSnapshot> entries = service.getTransactions(
                                validatedId, validatedPage, validatedPageSize, type, filter.fromMs(), filter.toMs())
                        .stream()
                        .map(OpenEcoApiImpl::toTransactionSnapshot)
                        .toList();
                int totalPages = totalEntries == 0 ? 0 : (int) Math.ceil(totalEntries / (double) validatedPageSize);
                return new HistoryPage(validatedPage, validatedPageSize, totalEntries, totalPages, entries);
            } catch (SQLException e) {
                throw new OpenEcoApiException("Failed to load filtered account history", e);
            }
        }

        return loadHistoryPage(requireAccountId(accountId), requireKnownCurrency(filter.currencyId()), page, pageSize, filter);
    }

    @Override
    public HistoryPage getHistory(UUID accountId, String currencyId, int page, int pageSize, HistoryFilter filter) {
        return loadHistoryPage(requireAccountId(accountId), requireKnownCurrency(currencyId), page, pageSize, filter);
    }

    @Override
    public int getRankOf(UUID accountId) {
        return service.getRankOf(requireAccountId(accountId));
    }

    @Override
    public int getRankOf(UUID accountId, String currencyId) {
        return service.getRankOf(requireAccountId(accountId), requireKnownCurrency(currencyId));
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
    public TransferCheckResult canTransfer(UUID fromId, UUID toId, String currencyId, BigDecimal amount) {
        return service.canTransfer(
                requireAccountId(fromId),
                requireAccountId(toId),
                requireKnownCurrency(currencyId),
                requireAmount(amount));
    }

    @Override
    public void logCustomTransaction(UUID accountId, BigDecimal amount, TransactionKind kind) {
        logCustomTransaction(accountId, amount, kind, TransactionMetadata.empty());
    }

    @Override
    public void logCustomTransaction(UUID accountId, String currencyId, BigDecimal amount, TransactionKind kind) {
        logCustomTransaction(accountId, currencyId, amount, kind, TransactionMetadata.empty());
    }

    @Override
    public void logCustomTransaction(UUID accountId, BigDecimal amount, TransactionKind kind, TransactionMetadata metadata) {
        UUID validatedId = requireAccountId(accountId);
        BigDecimal validatedAmount = requirePositiveAmount(amount);
        Objects.requireNonNull(kind, "kind");
        TransactionMetadata validatedMetadata = Objects.requireNonNull(metadata, "metadata");

        Optional<AccountSnapshot> accountOpt = getAccount(validatedId);
        if (accountOpt.isEmpty()) {
            throw new OpenEcoApiException("Account not found: " + validatedId);
        }
        BigDecimal currentBalance = accountOpt.get().balance();

        TransactionEntry entry = new TransactionEntry(
                mapTransactionType(kind),
                null,
                validatedId,
                validatedAmount,
                currentBalance,
                currentBalance,
                System.currentTimeMillis(),
                validatedMetadata.source(),
                validatedMetadata.note());
        service.logCustomTransaction(validatedId, entry);
    }

    @Override
    public void logCustomTransaction(UUID accountId, String currencyId, BigDecimal amount, TransactionKind kind, TransactionMetadata metadata) {
        UUID validatedId = requireAccountId(accountId);
        String validatedCurrencyId = requireKnownCurrency(currencyId);
        BigDecimal validatedAmount = requirePositiveAmount(amount);
        Objects.requireNonNull(kind, "kind");
        TransactionMetadata validatedMetadata = Objects.requireNonNull(metadata, "metadata");

        if (!service.hasAccount(validatedId)) {
            throw new OpenEcoApiException("Account not found: " + validatedId);
        }
        BigDecimal currentBalance = service.getBalance(validatedId, validatedCurrencyId);

        TransactionEntry entry = new TransactionEntry(
                mapTransactionType(kind),
                null,
                validatedId,
                validatedAmount,
                currentBalance,
                currentBalance,
                System.currentTimeMillis(),
                validatedMetadata.source(),
                validatedMetadata.note(),
                validatedCurrencyId);
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
                .map(OpenEcoApiImpl::toAccountSnapshot)
                .toList();
    }

    @Override
    public List<AccountSnapshot> getTopAccounts(int limit, String currencyId) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must be >= 0");
        }
        if (limit == 0) {
            return List.of();
        }
        String validatedCurrencyId = requireKnownCurrency(currencyId);
        return service.getBalTopSnapshot(validatedCurrencyId).stream()
                .limit(limit)
                .map(record -> toAccountSnapshot(record, record.getBalance(validatedCurrencyId)))
                .toList();
    }

    @Override
    public LeaderboardPage getTopAccounts(int page, int pageSize) {
        int validatedPage = requirePositive(page, "page");
        int validatedPageSize = requirePositive(pageSize, "pageSize");
        List<AccountSnapshot> all = service.getBalTopSnapshot().stream()
            .map(OpenEcoApiImpl::toAccountSnapshot)
            .toList();
        int total = all.size();
        int totalPages = total == 0 ? 0 : (int) Math.ceil(total / (double) validatedPageSize);
        int fromIndex = (validatedPage - 1) * validatedPageSize;
        List<AccountSnapshot> slice = fromIndex >= total
            ? List.of()
            : all.subList(fromIndex, Math.min(fromIndex + validatedPageSize, total));
        return new LeaderboardPage(validatedPage, validatedPageSize, total, totalPages, slice);
    }

    @Override
    public LeaderboardPage getTopAccounts(int page, int pageSize, String currencyId) {
        int validatedPage = requirePositive(page, "page");
        int validatedPageSize = requirePositive(pageSize, "pageSize");
        String validatedCurrencyId = requireKnownCurrency(currencyId);
        List<AccountSnapshot> all = service.getBalTopSnapshot(validatedCurrencyId).stream()
            .map(record -> toAccountSnapshot(record, record.getBalance(validatedCurrencyId)))
                .toList();
        int total = all.size();
        int totalPages = total == 0 ? 0 : (int) Math.ceil(total / (double) validatedPageSize);
        int fromIndex = (validatedPage - 1) * validatedPageSize;
        List<AccountSnapshot> slice = fromIndex >= total
                ? List.of()
                : all.subList(fromIndex, Math.min(fromIndex + validatedPageSize, total));
        return new LeaderboardPage(validatedPage, validatedPageSize, total, totalPages, slice);
    }

    @Override
    public EconomyRulesSnapshot getRules() {
        return new EconomyRulesSnapshot(
                getCurrencyInfo(),
                service.getPayCooldownMs(),
                service.getPayTaxRate(),
                service.getPayMinAmount(),
                service.getBalTopCacheTtlMs(),
                service.getHistoryRetentionDays());
    }

    @Override
    public boolean freezeAccount(UUID accountId) {
        return service.freezeAccount(requireAccountId(accountId));
    }

    @Override
    public boolean unfreezeAccount(UUID accountId) {
        return service.unfreezeAccount(requireAccountId(accountId));
    }

    @Override
    public boolean isFrozen(UUID accountId) {
        return service.isFrozen(requireAccountId(accountId));
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
    public CurrencyInfo getCurrencyInfo(String currencyId) {
        String validatedCurrencyId = requireKnownCurrency(currencyId);
        return new CurrencyInfo(
                validatedCurrencyId,
                service.getCurrencySingular(validatedCurrencyId),
                service.getCurrencyPlural(validatedCurrencyId),
                service.getFractionalDigits(validatedCurrencyId),
                service.getStartingBalance(validatedCurrencyId),
                service.getMaxBalance(validatedCurrencyId));
    }

    @Override
    public List<CurrencyInfo> getCurrencies() {
        return service.getCurrencyIds().stream()
                .map(this::getCurrencyInfo)
                .toList();
    }

    @Override
    public boolean hasCurrency(String currencyId) {
        return currencyId != null && service.hasCurrency(currencyId);
    }

    @Override
    public String format(BigDecimal amount) {
        return service.format(requireAmount(amount));
    }

    @Override
    public String format(BigDecimal amount, String currencyId) {
        return service.format(requireAmount(amount), requireKnownCurrency(currencyId));
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

    private BalanceChangeResult applyBalanceChange(UUID accountId, String currencyId, BigDecimal amount,
                                                   CurrencyBalanceMutation mutation) {
        BigDecimal previousBalance = service.getBalance(accountId, currencyId);
        EconomyResponse response = mutation.apply(accountId, currencyId, amount);
        return new BalanceChangeResult(
                mapChangeStatus(response),
                response.amount,
                previousBalance,
                response.transactionSuccess() ? response.balance : previousBalance);
    }

    private HistoryPage loadHistoryPage(UUID accountId, String currencyId, int page, int pageSize, HistoryFilter filter) {
        int validatedPage = requirePositive(page, "page");
        int validatedPageSize = requirePositive(pageSize, "pageSize");

        if (filter == null || filter.equals(HistoryFilter.NONE)) {
            try {
                int totalEntries = service.countTransactions(accountId, currencyId);
                List<TransactionSnapshot> entries = service.getTransactions(accountId, currencyId, validatedPage, validatedPageSize)
                        .stream()
                        .map(OpenEcoApiImpl::toTransactionSnapshot)
                        .toList();
                int totalPages = totalEntries == 0 ? 0 : (int) Math.ceil(totalEntries / (double) validatedPageSize);
                return new HistoryPage(validatedPage, validatedPageSize, totalEntries, totalPages, entries);
            } catch (SQLException e) {
                throw new OpenEcoApiException("Failed to load account history", e);
            }
        }

        if (filter.currencyId() != null && !currencyId.equalsIgnoreCase(filter.currencyId())) {
            throw new IllegalArgumentException("currencyId does not match HistoryFilter currencyId");
        }

        TransactionType type = filter.kind() != null ? mapTransactionType(filter.kind()) : null;
        try {
            int totalEntries = service.countTransactions(accountId, currencyId, type, filter.fromMs(), filter.toMs());
            List<TransactionSnapshot> entries = service.getTransactions(
                    accountId, currencyId, validatedPage, validatedPageSize, type, filter.fromMs(), filter.toMs())
                    .stream()
                    .map(OpenEcoApiImpl::toTransactionSnapshot)
                    .toList();
            int totalPages = totalEntries == 0 ? 0 : (int) Math.ceil(totalEntries / (double) validatedPageSize);
            return new HistoryPage(validatedPage, validatedPageSize, totalEntries, totalPages, entries);
        } catch (SQLException e) {
            throw new OpenEcoApiException("Failed to load filtered account history", e);
        }
    }

    private AccountOperationResult reconcileExistingAccount(UUID accountId, String validatedName,
                                                            AccountSnapshot current) {
        if (current.lastKnownName().equals(validatedName)) {
            return AccountOperationResult.unchanged(current);
        }

        return switch (service.renameAccountDetailed(accountId, validatedName)) {
            case RENAMED -> getAccount(accountId)
                    .map(AccountOperationResult::renamed)
                    .orElseGet(() -> AccountOperationResult.failed(current, "Account was renamed but could not be reloaded"));
            case NOT_FOUND -> AccountOperationResult.notFound();
            case UNCHANGED -> AccountOperationResult.unchanged(current);
            case NAME_IN_USE -> AccountOperationResult.nameInUse(current);
            case INVALID_NAME -> AccountOperationResult.failed(current, "Invalid account name");
            case CANCELLED -> AccountOperationResult.failed(current, "Account rename was cancelled or could not be applied");
        };
    }

    private static AccountSnapshot toAccountSnapshot(AccountRecord record) {
        return toAccountSnapshot(record, record.getBalance());
    }

    private static AccountSnapshot toAccountSnapshot(AccountRecord record, BigDecimal balance) {
        return new AccountSnapshot(
                record.getId(),
                record.getLastKnownName(),
                balance,
                record.getCreatedAt(),
                record.getUpdatedAt(),
                record.isFrozen());
    }

    private static TransactionSnapshot toTransactionSnapshot(TransactionEntry entry) {
        return new TransactionSnapshot(
                mapTransactionKind(entry.getType()),
                entry.getCounterpartId(),
                entry.getTargetId(),
                entry.getCurrencyId(),
                entry.getAmount(),
                entry.getBalanceBefore(),
                entry.getBalanceAfter(),
                entry.getTimestamp(),
                entry.getSource(),
                entry.getNote());
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

    private static BalanceChangeResult.Status mapChangeStatus(EconomyResponse response) {
        if (response.transactionSuccess()) {
            return BalanceChangeResult.Status.SUCCESS;
        }
        return switch (response.errorMessage) {
            case "Unknown currency" -> BalanceChangeResult.Status.UNKNOWN_CURRENCY;
            case "Account not found" -> BalanceChangeResult.Status.ACCOUNT_NOT_FOUND;
            case "Amount must be positive", "Amount cannot be negative" -> BalanceChangeResult.Status.INVALID_AMOUNT;
            case "Insufficient funds" -> BalanceChangeResult.Status.INSUFFICIENT_FUNDS;
            case "Balance limit reached" -> BalanceChangeResult.Status.BALANCE_LIMIT;
            case "Account is frozen" -> BalanceChangeResult.Status.FROZEN;
            case "Cancelled by plugin" -> BalanceChangeResult.Status.CANCELLED;
            default -> throw new OpenEcoApiException("Unexpected balance change failure: " + response.errorMessage);
        };
    }

    private static TransferResult.Status mapTransferStatus(PayResult.Status status) {
        return switch (status) {
            case SUCCESS -> TransferResult.Status.SUCCESS;
            case UNKNOWN_CURRENCY -> TransferResult.Status.UNKNOWN_CURRENCY;
            case COOLDOWN -> TransferResult.Status.COOLDOWN;
            case INSUFFICIENT_FUNDS -> TransferResult.Status.INSUFFICIENT_FUNDS;
            case ACCOUNT_NOT_FOUND -> TransferResult.Status.ACCOUNT_NOT_FOUND;
            case BALANCE_LIMIT -> TransferResult.Status.BALANCE_LIMIT;
            case CANCELLED -> TransferResult.Status.CANCELLED;
            case TOO_LOW -> TransferResult.Status.TOO_LOW;
            case INVALID_AMOUNT -> TransferResult.Status.INVALID_AMOUNT;
            case SELF_TRANSFER -> TransferResult.Status.SELF_TRANSFER;
            case FROZEN -> TransferResult.Status.FROZEN;
        };
    }

    private static UUID requireAccountId(UUID accountId) {
        return Objects.requireNonNull(accountId, "accountId");
    }

    private static BigDecimal requireAmount(BigDecimal amount) {
        return Objects.requireNonNull(amount, "amount");
    }

    private String requireKnownCurrency(String currencyId) {
        Objects.requireNonNull(currencyId, "currencyId");
        String validatedCurrencyId = service.getCanonicalCurrencyId(currencyId);
        if (validatedCurrencyId == null) {
            throw new IllegalArgumentException("Unknown currency: " + currencyId);
        }
        return validatedCurrencyId;
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

    @FunctionalInterface
    private interface CurrencyBalanceMutation {
        EconomyResponse apply(UUID accountId, String currencyId, BigDecimal amount);
    }
}