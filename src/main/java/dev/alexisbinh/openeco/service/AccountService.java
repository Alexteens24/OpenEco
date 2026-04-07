package dev.alexisbinh.openeco.service;

import dev.alexisbinh.openeco.api.BalanceCheckResult;
import dev.alexisbinh.openeco.api.TransferPreviewResult;
import dev.alexisbinh.openeco.event.AccountCreateEvent;
import dev.alexisbinh.openeco.event.AccountDeleteEvent;
import dev.alexisbinh.openeco.event.AccountDeletedEvent;
import dev.alexisbinh.openeco.event.AccountRenameEvent;
import dev.alexisbinh.openeco.event.AccountRenamedEvent;
import dev.alexisbinh.openeco.model.AccountRecord;
import dev.alexisbinh.openeco.model.PayResult;
import dev.alexisbinh.openeco.model.TransactionEntry;
import dev.alexisbinh.openeco.model.TransactionType;
import dev.alexisbinh.openeco.api.TransferCheckResult;
import dev.alexisbinh.openeco.storage.AccountRepository;
import net.milkbowl.vault2.economy.EconomyResponse;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class AccountService {

    public enum CreateAccountStatus {
        CREATED,
        ALREADY_EXISTS,
        NAME_IN_USE,
        INVALID_NAME
    }

    public enum RenameAccountStatus {
        RENAMED,
        NOT_FOUND,
        UNCHANGED,
        NAME_IN_USE,
        INVALID_NAME,
        CANCELLED
    }

    public enum DeleteAccountStatus {
        DELETED,
        NOT_FOUND,
        FAILED
    }

    private final AccountRepository repository;
    private final Logger log;
    private final Object persistenceLock = new Object();
    private final AccountRegistry accountRegistry = new AccountRegistry();
    private final LeaderboardCache leaderboardCache = new LeaderboardCache();
    private final TransactionHistoryService transactionHistoryService;
    private final EventDispatcher eventDispatcher;
    private final EconomyOperations economyOperations;
    private volatile EconomyConfigSnapshot config;
    private volatile boolean crossServerEnabled;

    // Pay cooldown tracker
    private final ConcurrentHashMap<UUID, Long> lastPayTime = new ConcurrentHashMap<>();

    public AccountService(AccountRepository repository, JavaPlugin plugin, FileConfiguration config) {
        this(repository, plugin.getLogger(), plugin.getName(), config, new BukkitEventDispatcher());
    }

    AccountService(AccountRepository repository, Logger log, String threadNamePrefix,
                   FileConfiguration config, EventDispatcher eventDispatcher) {
        this.repository = repository;
        this.log = log;
        this.eventDispatcher = eventDispatcher;
        this.transactionHistoryService = new TransactionHistoryService(repository, threadNamePrefix, this.log);
        this.economyOperations = new EconomyOperations(
                accountRegistry,
                () -> this.config,
                lastPayTime,
                this::logTransaction,
                eventDispatcher);
        readConfig(config);
    }

    // ── Config ──────────────────────────────────────────────────────────────

    public void reloadConfig(FileConfiguration config) {
        readConfig(config);
    }

    private void readConfig(FileConfiguration config) {
        EconomyConfigSnapshot updated = EconomyConfigSnapshot.from(config);
        this.config = updated;
        syncConfiguredCurrencies(updated);
        leaderboardCache.setCacheTtlMs(updated.balTopCacheTtlMs());
        this.crossServerEnabled = config.getBoolean("cross-server.enabled", false);
    }

    public boolean isCrossServerEnabled() {
        return crossServerEnabled;
    }

    // ── Startup ─────────────────────────────────────────────────────────────

    public void loadAll() throws SQLException {
        List<AccountRecord> records = repository.loadAll();
        validateLoadedNames(records);
        accountRegistry.loadAll(records);
        syncConfiguredCurrencies(config);
        log.info("Loaded " + records.size() + " economy accounts.");
    }

    // ── Account management ───────────────────────────────────────────────────

    public boolean hasAccount(UUID id) {
        return accountRegistry.hasAccount(id);
    }

    public Optional<AccountRecord> getAccount(UUID id) {
        return accountRegistry.getSnapshot(id);
    }

    public Optional<AccountRecord> findByName(String name) {
        return accountRegistry.findSnapshotByName(name);
    }

    /** Creates an account if it doesn't exist yet. Returns true if created. */
    public boolean createAccount(UUID id, String name) {
        return createAccountDetailed(id, name) == CreateAccountStatus.CREATED;
    }

    public CreateAccountStatus createAccountDetailed(UUID id, String name) {
        String validatedName = sanitizeAccountName(name);
        if (validatedName == null) {
            return CreateAccountStatus.INVALID_NAME;
        }

        AccountCreateEvent createdEvent;

        synchronized (persistenceLock) {
            if (accountRegistry.hasAccount(id)) return CreateAccountStatus.ALREADY_EXISTS;
            if (accountRegistry.isNameClaimedByAnother(id, validatedName)) {
                return CreateAccountStatus.NAME_IN_USE;
            }
            EconomyConfigSnapshot currentConfig = config;
            long now = System.currentTimeMillis();
            Map<String, BigDecimal> startingBalances = new HashMap<>();
            for (CurrencyDefinition currency : currentConfig.currencies().all()) {
                startingBalances.put(currency.id(), currency.startingBalance());
            }
            AccountRecord record = new AccountRecord(
                    id,
                    validatedName,
                    currentConfig.currencyId(),
                    startingBalances,
                    now,
                    now);
            record.markDirty();
            if (!accountRegistry.create(record)) {
                return CreateAccountStatus.NAME_IN_USE;
            }
            invalidateBalTopCache();
            createdEvent = new AccountCreateEvent(id, validatedName, currentConfig.startingBalance());
        }

        eventDispatcher.dispatch(createdEvent);
        return CreateAccountStatus.CREATED;
    }

    /** Updates name in memory; marks dirty so the new name is flushed to DB. */
    public boolean renameAccount(UUID id, String newName) {
        RenameAccountStatus status = renameAccountDetailed(id, newName);
        return status == RenameAccountStatus.RENAMED || status == RenameAccountStatus.UNCHANGED;
    }

    public RenameAccountStatus renameAccountDetailed(UUID id, String newName) {
        String validatedName = sanitizeAccountName(newName);
        if (validatedName == null) {
            return RenameAccountStatus.INVALID_NAME;
        }

        AccountRenameEvent event;

        synchronized (persistenceLock) {
            AccountRecord record = accountRegistry.getLiveRecord(id);
            if (record == null) return RenameAccountStatus.NOT_FOUND;

            synchronized (record) {
                if (!hasLiveRecord(id, record)) {
                    return RenameAccountStatus.NOT_FOUND;
                }

                String oldName = record.getLastKnownName();
                if (oldName.equals(validatedName)) {
                    return RenameAccountStatus.UNCHANGED;
                }

                if (accountRegistry.isNameClaimedByAnother(id, validatedName)) {
                    return RenameAccountStatus.NAME_IN_USE;
                }

                event = new AccountRenameEvent(id, oldName, validatedName);
            }
        }

        eventDispatcher.dispatch(event);
        if (event.isCancelled()) {
            return RenameAccountStatus.CANCELLED;
        }

        synchronized (persistenceLock) {
            AccountRecord record = accountRegistry.getLiveRecord(id);
            if (record == null) return RenameAccountStatus.NOT_FOUND;

            synchronized (record) {
                if (!hasLiveRecord(id, record)) {
                    return RenameAccountStatus.NOT_FOUND;
                }

                String currentName = record.getLastKnownName();
                if (currentName.equals(validatedName)) {
                    return RenameAccountStatus.UNCHANGED;
                }

                if (accountRegistry.isNameClaimedByAnother(id, validatedName)) {
                    return RenameAccountStatus.NAME_IN_USE;
                }

                if (!accountRegistry.rename(record, validatedName)) {
                    return RenameAccountStatus.NAME_IN_USE;
                }
                invalidateBalTopCache();
            }
        }
        eventDispatcher.dispatch(new AccountRenamedEvent(id, event.getOldName(), validatedName));
        return RenameAccountStatus.RENAMED;
    }

    public boolean deleteAccount(UUID id) {
        return deleteAccountDetailed(id) == DeleteAccountStatus.DELETED;
    }

    public DeleteAccountStatus deleteAccountDetailed(UUID id) {
        AccountDeleteEvent event;

        synchronized (persistenceLock) {
            AccountRecord record = accountRegistry.getLiveRecord(id);
            if (record == null) return DeleteAccountStatus.NOT_FOUND;

            synchronized (record) {
                if (!hasLiveRecord(id, record)) {
                    return DeleteAccountStatus.NOT_FOUND;
                }
                event = new AccountDeleteEvent(id, record.getLastKnownName(), record.getBalance());
            }
        }

        eventDispatcher.dispatch(event);
        if (event.isCancelled()) {
            return DeleteAccountStatus.FAILED;
        }

        synchronized (persistenceLock) {
            AccountRecord record = accountRegistry.getLiveRecord(id);
            if (record == null) return DeleteAccountStatus.NOT_FOUND;
            Long previousPayTime;

            synchronized (record) {
                if (!hasLiveRecord(id, record)) {
                    return DeleteAccountStatus.NOT_FOUND;
                }
                if (!accountRegistry.remove(id, record)) {
                    return DeleteAccountStatus.FAILED;
                }
                previousPayTime = lastPayTime.remove(id);
            }

            if (!transactionHistoryService.waitForDrain()) {
                restoreDeletedAccount(id, record, previousPayTime);
                return DeleteAccountStatus.FAILED;
            }

            try {
                repository.delete(id);
            } catch (SQLException e) {
                restoreDeletedAccount(id, record, previousPayTime);
                log.severe("Failed to delete account " + id + ": " + e.getMessage());
                return DeleteAccountStatus.FAILED;
            }
            invalidateBalTopCache();
            AccountDeletedEvent deletedEvent = new AccountDeletedEvent(id, event.getPlayerName(), event.getBalance());
            eventDispatcher.dispatch(deletedEvent);
            return DeleteAccountStatus.DELETED;
        }
    }

    public Map<UUID, String> getUUIDNameMap() {
        return accountRegistry.getUUIDNameMap();
    }

    public List<String> getAccountNames() {
        return accountRegistry.getAccountNames();
    }

    // ── Balance operations ───────────────────────────────────────────────────

    public BigDecimal getBalance(UUID id) {
        return getBalance(id, config.currencyId());
    }

    public BigDecimal getBalance(UUID id, String currencyId) {
        String resolvedCurrencyId = resolveCurrencyIdOrFallback(currencyId);
        AccountRecord record = accountRegistry.getLiveRecord(id);
        return record == null ? BigDecimal.ZERO : record.getBalance(resolvedCurrencyId);
    }

    public boolean has(UUID id, BigDecimal amount) {
        return has(id, config.currencyId(), amount);
    }

    public boolean has(UUID id, String currencyId, BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            return false;
        }

        CurrencyDefinition currency = resolveCurrency(currencyId);
        if (currency == null) {
            return false;
        }

        BigDecimal scaled = amount.setScale(currency.fractionalDigits(), RoundingMode.HALF_UP);
        if (amount.compareTo(BigDecimal.ZERO) > 0 && scaled.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }

        AccountRecord record = accountRegistry.getLiveRecord(id);
        if (record == null) return false;
        return record.getBalance(currency.id()).compareTo(scaled) >= 0;
    }

    public BalanceCheckResult canDeposit(UUID id, BigDecimal amount) {
        return canDeposit(id, config.currencyId(), amount);
    }

    public BalanceCheckResult canDeposit(UUID id, String currencyId, BigDecimal amount) {
        return economyOperations.canDeposit(id, currencyId, amount);
    }

    public BalanceCheckResult canWithdraw(UUID id, BigDecimal amount) {
        return canWithdraw(id, config.currencyId(), amount);
    }

    public BalanceCheckResult canWithdraw(UUID id, String currencyId, BigDecimal amount) {
        return economyOperations.canWithdraw(id, currencyId, amount);
    }

    public EconomyResponse deposit(UUID id, BigDecimal amount) {
        return deposit(id, config.currencyId(), amount);
    }

    public EconomyResponse deposit(UUID id, String currencyId, BigDecimal amount) {
        return economyOperations.deposit(id, currencyId, amount);
    }

    public EconomyResponse withdraw(UUID id, BigDecimal amount) {
        return withdraw(id, config.currencyId(), amount);
    }

    public EconomyResponse withdraw(UUID id, String currencyId, BigDecimal amount) {
        return economyOperations.withdraw(id, currencyId, amount);
    }

    public EconomyResponse set(UUID id, BigDecimal amount) {
        return set(id, config.currencyId(), amount);
    }

    public EconomyResponse set(UUID id, String currencyId, BigDecimal amount) {
        return economyOperations.set(id, currencyId, amount);
    }

    public EconomyResponse reset(UUID id) {
        return reset(id, config.currencyId());
    }

    public EconomyResponse reset(UUID id, String currencyId) {
        return economyOperations.reset(id, currencyId);
    }

    /**
     * Atomically transfers money from {@code fromId} to {@code toId}, applying
     * cooldown and tax according to config. Both accounts must already exist.
     */
    public PayResult pay(UUID fromId, UUID toId, BigDecimal rawAmount) {
        return pay(fromId, toId, config.currencyId(), rawAmount);
    }

    public PayResult pay(UUID fromId, UUID toId, String currencyId, BigDecimal rawAmount) {
        return economyOperations.pay(fromId, toId, currencyId, rawAmount);
    }

    public TransferCheckResult canTransfer(UUID fromId, UUID toId, BigDecimal amount) {
        return canTransfer(fromId, toId, config.currencyId(), amount);
    }

    public TransferCheckResult canTransfer(UUID fromId, UUID toId, String currencyId, BigDecimal amount) {
        return economyOperations.canTransfer(fromId, toId, currencyId, amount);
    }

    public TransferPreviewResult previewTransfer(UUID fromId, UUID toId, BigDecimal amount) {
        return previewTransfer(fromId, toId, config.currencyId(), amount);
    }

    public TransferPreviewResult previewTransfer(UUID fromId, UUID toId, String currencyId, BigDecimal amount) {
        return economyOperations.previewTransfer(fromId, toId, currencyId, amount);
    }

    // ── Transaction history ──────────────────────────────────────────────────

    public List<TransactionEntry> getTransactions(UUID playerId, int page, int pageSize) throws SQLException {
        return getTransactions(playerId, config.currencyId(), page, pageSize);
    }

    public List<TransactionEntry> getTransactions(UUID playerId, String currencyId, int page, int pageSize) throws SQLException {
        return transactionHistoryService.getTransactions(playerId, page, pageSize, resolveCurrencyIdOrFallback(currencyId));
    }

    public List<TransactionEntry> getTransactions(UUID playerId, int page, int pageSize,
            @Nullable TransactionType type, long fromMs, long toMs) throws SQLException {
        return getTransactions(playerId, config.currencyId(), page, pageSize, type, fromMs, toMs);
    }

    public List<TransactionEntry> getTransactions(UUID playerId, String currencyId, int page, int pageSize,
            @Nullable TransactionType type, long fromMs, long toMs) throws SQLException {
        return transactionHistoryService.getTransactions(playerId, page, pageSize, type, fromMs, toMs,
                resolveCurrencyIdOrFallback(currencyId));
    }

    public int countTransactions(UUID playerId) throws SQLException {
        return countTransactions(playerId, config.currencyId());
    }

    public int countTransactions(UUID playerId, String currencyId) throws SQLException {
        return transactionHistoryService.countTransactions(playerId, resolveCurrencyIdOrFallback(currencyId));
    }

    public int countTransactions(UUID playerId, @Nullable TransactionType type,
            long fromMs, long toMs) throws SQLException {
        return countTransactions(playerId, config.currencyId(), type, fromMs, toMs);
    }

    public int countTransactions(UUID playerId, String currencyId, @Nullable TransactionType type,
            long fromMs, long toMs) throws SQLException {
        return transactionHistoryService.countTransactions(playerId, type, fromMs, toMs,
                resolveCurrencyIdOrFallback(currencyId));
    }

    public void logCustomTransaction(UUID accountId, TransactionEntry entry) {
        if (!accountId.equals(entry.getTargetId())) {
            throw new IllegalArgumentException(
                "entry.targetId must match accountId (got targetId=" + entry.getTargetId() + ")");
        }
        transactionHistoryService.log(entry);
    }

    /**
     * Prunes transaction history according to the configured retention period.
     * Does nothing when {@code history.retention-days} is -1 (unlimited).
     */
    public void pruneHistory() {
        int retentionDays = config.historyRetentionDays();
        if (retentionDays > 0) {
            transactionHistoryService.pruneOldTransactions(retentionDays);
        }
    }

    // ── Baltop ───────────────────────────────────────────────────────────────

    public List<AccountRecord> getBalTopSnapshot() {
        return getBalTopSnapshot(config.currencyId());
    }

    public List<AccountRecord> getBalTopSnapshot(String currencyId) {
        return leaderboardCache.getSnapshot(resolveCurrencyIdOrFallback(currencyId), accountRegistry.liveRecords());
    }

    public int getRankOf(UUID accountId) {
        return getRankOf(accountId, config.currencyId());
    }

    public int getRankOf(UUID accountId, String currencyId) {
        List<AccountRecord> snapshot = getBalTopSnapshot(currencyId);
        for (int i = 0; i < snapshot.size(); i++) {
            if (snapshot.get(i).getId().equals(accountId)) {
                return i + 1;
            }
        }
        return -1;
    }

    // ── Account freeze ────────────────────────────────────────────────────────

    public boolean freezeAccount(UUID id) {
        AccountRecord record = accountRegistry.getLiveRecord(id);
        if (record == null) return false;
        synchronized (record) {
            if (!accountRegistry.isLive(id, record)) return false;
            record.setFrozen(true);
            return true;
        }
    }

    public boolean unfreezeAccount(UUID id) {
        AccountRecord record = accountRegistry.getLiveRecord(id);
        if (record == null) return false;
        synchronized (record) {
            if (!accountRegistry.isLive(id, record)) return false;
            record.setFrozen(false);
            return true;
        }
    }

    public boolean isFrozen(UUID id) {
        AccountRecord record = accountRegistry.getLiveRecord(id);
        return record != null && record.isFrozen();
    }

    // ── Formatting / currency ─────────────────────────────────────────────────

    public String format(BigDecimal amount) {
        return format(amount, config.currencyId());
    }

    public String format(BigDecimal amount, String currencyId) {
        CurrencyDefinition currency = resolveCurrency(currencyId);
        if (currency == null) {
            return amount.toPlainString();
        }
        BigDecimal scaled = amount.setScale(currency.fractionalDigits(), RoundingMode.HALF_UP);
        String unit = scaled.abs().compareTo(BigDecimal.ONE) == 0
                ? currency.singularName()
                : currency.pluralName();
        return scaled.toPlainString() + " " + unit;
    }

    public String getCurrencyId() { return config.currencyId(); }
    public String getCurrencySingular() { return config.currencySingular(); }
    public String getCurrencyPlural() { return config.currencyPlural(); }
    public int getFractionalDigits() { return config.fractionalDigits(); }
    public BigDecimal getStartingBalance() { return config.startingBalance(); }
    public BigDecimal getMaxBalance() { return config.maxBalance(); }
    public boolean hasCurrency(String currencyId) { return resolveCurrency(currencyId) != null; }
    public @Nullable String getCanonicalCurrencyId(String currencyId) {
        CurrencyDefinition currency = resolveCurrency(currencyId);
        return currency != null ? currency.id() : null;
    }
    public List<String> getCurrencyIds() { return config.currencies().all().stream().map(CurrencyDefinition::id).toList(); }
    public String getCurrencySingular(String currencyId) {
        CurrencyDefinition currency = resolveCurrency(currencyId);
        return currency != null ? currency.singularName() : config.currencySingular();
    }
    public String getCurrencyPlural(String currencyId) {
        CurrencyDefinition currency = resolveCurrency(currencyId);
        return currency != null ? currency.pluralName() : config.currencyPlural();
    }
    public int getFractionalDigits(String currencyId) {
        CurrencyDefinition currency = resolveCurrency(currencyId);
        return currency != null ? currency.fractionalDigits() : config.fractionalDigits();
    }
    public BigDecimal getStartingBalance(String currencyId) {
        CurrencyDefinition currency = resolveCurrency(currencyId);
        return currency != null ? currency.startingBalance() : config.startingBalance();
    }
    public BigDecimal getMaxBalance(String currencyId) {
        CurrencyDefinition currency = resolveCurrency(currencyId);
        return currency != null ? currency.maxBalance() : config.maxBalance();
    }
    public long getPayCooldownMs() { return config.payCooldownMs(); }
    public BigDecimal getPayTaxRate() { return config.payTaxRate(); }
    public BigDecimal getPayMinAmount() { return config.payMinAmount(); }
    public long getBalTopCacheTtlMs() { return config.balTopCacheTtlMs(); }
    public int getHistoryRetentionDays() { return config.historyRetentionDays(); }
    /** Returns the formatted max balance string, or null if unlimited. */
    public String getFormattedMaxBalance() {
        return getFormattedMaxBalance(config.currencyId());
    }

    public String getFormattedMaxBalance(String currencyId) {
        BigDecimal maxBalance = getMaxBalance(currencyId);
        return maxBalance != null ? format(maxBalance, currencyId) : null;
    }

    // ── Flush ─────────────────────────────────────────────────────────────────

    /**
     * Flushes all dirty records to the database. Thread-safe: takes a snapshot
     * under per-record lock, clears dirty flag, then batches to DB.
     */
    public void flushDirty() {
        synchronized (persistenceLock) {
            List<AccountRecord> snapshots = new ArrayList<>();
            for (AccountRecord record : accountRegistry.liveRecords()) {
                if (!record.isDirty()) continue;
                AccountRecord snap;
                synchronized (record) {
                    if (!record.isDirty()) continue;
                    snap = record.snapshot();
                    record.clearDirty();
                }
                snapshots.add(snap);
            }
            if (snapshots.isEmpty()) return;

            if (!transactionHistoryService.waitForDrain()) {
                log.warning("Skipping balance flush because pending transaction writes did not drain in time.");
                for (AccountRecord snap : snapshots) {
                    AccountRecord live = accountRegistry.getLiveRecord(snap.getId());
                    if (live != null) live.markDirty();
                }
                return;
            }

            try {
                repository.upsertBatch(snapshots);
            } catch (SQLException e) {
                log.severe("Auto-save failed: " + e.getMessage());
                // Re-mark dirty so next cycle retries
                for (AccountRecord snap : snapshots) {
                    AccountRecord live = accountRegistry.getLiveRecord(snap.getId());
                    if (live != null) live.markDirty();
                }
            }
        }
    }

    /**
     * Immediately flushes a single account to the database.
     * Intended for cross-server use: call async before the player disconnects.
     */
    public void flushAccount(UUID id) {
        AccountRecord live = accountRegistry.getLiveRecord(id);
        if (live == null) return;
        AccountRecord snap;
        synchronized (live) {
            if (!live.isDirty()) return;
            snap = live.snapshot();
            live.clearDirty();
        }
        try {
            repository.upsertBatch(List.of(snap));
        } catch (SQLException e) {
            log.warning("Cross-server flush failed for " + id + ": " + e.getMessage());
            live.markDirty();
        }
    }

    /**
     * Re-reads a single account from the database and refreshes the in-memory record.
     * Intended for cross-server use: call async when a player connects from another server.
     */
    public void refreshAccount(UUID id) {
        try {
            Optional<AccountRecord> fresh = repository.loadAccount(id);
            if (fresh.isEmpty()) return;
            AccountRecord freshRecord = fresh.get();
            alignLoadedRecordCurrencies(freshRecord, config);

            synchronized (persistenceLock) {
                if (!accountRegistry.replace(freshRecord)) {
                    log.warning("Cross-server refresh skipped for " + id
                            + " because refreshed account name '" + freshRecord.getLastKnownName()
                            + "' is already claimed by another in-memory account.");
                    return;
                }
            }
            invalidateBalTopCache();
        } catch (SQLException e) {
            log.warning("Cross-server refresh failed for " + id + ": " + e.getMessage());
        }
    }

    public void shutdown() {
        transactionHistoryService.shutdown();
        flushDirty();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private void logTransaction(TransactionEntry entry) {
        transactionHistoryService.log(entry);
    }

    private boolean hasLiveRecord(UUID id, AccountRecord record) {
        return accountRegistry.isLive(id, record);
    }

    private void restoreDeletedAccount(UUID id, AccountRecord record, Long previousPayTime) {
        accountRegistry.restore(record);
        if (previousPayTime != null) {
            lastPayTime.put(id, previousPayTime);
        } else {
            lastPayTime.remove(id);
        }
    }

    private void invalidateBalTopCache() {
        leaderboardCache.invalidate();
    }

    private void syncConfiguredCurrencies(EconomyConfigSnapshot configSnapshot) {
        accountRegistry.syncCurrencies(configSnapshot.currencyId(), currencyId -> {
            CurrencyDefinition currency = configSnapshot.currencies().find(currencyId).orElse(null);
            return currency != null ? currency.id() : null;
        });
    }

    private void alignLoadedRecordCurrencies(AccountRecord record, EconomyConfigSnapshot configSnapshot) {
        record.canonicalizeCurrencyIds(currencyId -> {
            CurrencyDefinition currency = configSnapshot.currencies().find(currencyId).orElse(null);
            return currency != null ? currency.id() : null;
        });
        record.setPrimaryCurrencyId(configSnapshot.currencyId());
        record.clearDirty();
    }

    private static String sanitizeAccountName(String name) {
        if (name == null) {
            return null;
        }

        String trimmed = name.trim();
        if (trimmed.isEmpty() || trimmed.length() > AccountRecord.MAX_NAME_LENGTH) {
            return null;
        }
        return trimmed;
    }

    private static void validateLoadedNames(List<AccountRecord> records) throws SQLException {
        Map<String, UUID> owners = new HashMap<>();
        for (AccountRecord record : records) {
            String sanitized = sanitizeAccountName(record.getLastKnownName());
            if (sanitized == null || !sanitized.equals(record.getLastKnownName())) {
                throw new SQLException("Invalid stored account name for " + record.getId()
                        + ": '" + record.getLastKnownName() + "'. Resolve invalid names before starting openeco.");
            }

            String normalized = AccountRegistry.normalizeName(record.getLastKnownName());
            UUID existingOwner = owners.putIfAbsent(normalized, record.getId());
            if (existingOwner != null && !existingOwner.equals(record.getId())) {
                throw new SQLException("Duplicate stored account name '" + record.getLastKnownName() + "' for "
                        + existingOwner + " and " + record.getId() + ". Resolve duplicates before starting openeco.");
            }
        }
    }

    private CurrencyDefinition resolveCurrency(String currencyId) {
        return config.currencies().find(currencyId).orElse(null);
    }

    private String resolveCurrencyIdOrFallback(String currencyId) {
        CurrencyDefinition currency = resolveCurrency(currencyId);
        return currency != null ? currency.id() : config.currencyId();
    }
}
