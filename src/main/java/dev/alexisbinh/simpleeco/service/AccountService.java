package dev.alexisbinh.simpleeco.service;

import dev.alexisbinh.simpleeco.event.AccountCreateEvent;
import dev.alexisbinh.simpleeco.event.AccountDeleteEvent;
import dev.alexisbinh.simpleeco.event.AccountRenameEvent;
import dev.alexisbinh.simpleeco.model.AccountRecord;
import dev.alexisbinh.simpleeco.model.PayResult;
import dev.alexisbinh.simpleeco.model.TransactionEntry;
import dev.alexisbinh.simpleeco.model.TransactionType;
import dev.alexisbinh.simpleeco.api.TransferCheckResult;
import dev.alexisbinh.simpleeco.storage.AccountRepository;
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
                this::invalidateBalTopCache,
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
        leaderboardCache.setCacheTtlMs(updated.balTopCacheTtlMs());
    }

    // ── Startup ─────────────────────────────────────────────────────────────

    public void loadAll() throws SQLException {
        List<AccountRecord> records = repository.loadAll();
        validateLoadedNames(records);
        accountRegistry.loadAll(records);
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

        synchronized (persistenceLock) {
            if (accountRegistry.hasAccount(id)) return CreateAccountStatus.ALREADY_EXISTS;
            if (accountRegistry.isNameClaimedByAnother(id, validatedName)) {
                return CreateAccountStatus.NAME_IN_USE;
            }
            EconomyConfigSnapshot currentConfig = config;
            long now = System.currentTimeMillis();
            AccountRecord record = new AccountRecord(id, validatedName, currentConfig.startingBalance(), now, now);
            record.markDirty();
            if (!accountRegistry.create(record)) {
                return CreateAccountStatus.NAME_IN_USE;
            }
            invalidateBalTopCache();
            eventDispatcher.dispatch(new AccountCreateEvent(id, validatedName, currentConfig.startingBalance()));
            return CreateAccountStatus.CREATED;
        }
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

                AccountRenameEvent event = new AccountRenameEvent(id, oldName, validatedName);
                eventDispatcher.dispatch(event);
                if (event.isCancelled()) {
                    return RenameAccountStatus.CANCELLED;
                }

                if (!accountRegistry.rename(record, validatedName)) {
                    return RenameAccountStatus.NAME_IN_USE;
                }
                return RenameAccountStatus.RENAMED;
            }
        }
    }

    public boolean deleteAccount(UUID id) {
        return deleteAccountDetailed(id) == DeleteAccountStatus.DELETED;
    }

    public DeleteAccountStatus deleteAccountDetailed(UUID id) {
        synchronized (persistenceLock) {
            AccountRecord record = accountRegistry.getLiveRecord(id);
            if (record == null) return DeleteAccountStatus.NOT_FOUND;
            Long previousPayTime;

            synchronized (record) {
                AccountDeleteEvent event = new AccountDeleteEvent(id, record.getLastKnownName(), record.getBalance());
                eventDispatcher.dispatch(event);
                if (event.isCancelled()) {
                    return DeleteAccountStatus.FAILED;
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
            return DeleteAccountStatus.DELETED;
        }
    }

    public Map<UUID, String> getUUIDNameMap() {
        return accountRegistry.getUUIDNameMap();
    }

    // ── Balance operations ───────────────────────────────────────────────────

    public BigDecimal getBalance(UUID id) {
        AccountRecord record = accountRegistry.getLiveRecord(id);
        return record == null ? BigDecimal.ZERO : record.getBalance();
    }

    public boolean has(UUID id, BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            return false;
        }
        AccountRecord record = accountRegistry.getLiveRecord(id);
        if (record == null) return false;
        return record.getBalance().compareTo(amount) >= 0;
    }

    public EconomyResponse canDeposit(UUID id, BigDecimal amount) {
        return economyOperations.canDeposit(id, amount);
    }

    public EconomyResponse canWithdraw(UUID id, BigDecimal amount) {
        return economyOperations.canWithdraw(id, amount);
    }

    public EconomyResponse deposit(UUID id, BigDecimal amount) {
        return economyOperations.deposit(id, amount);
    }

    public EconomyResponse withdraw(UUID id, BigDecimal amount) {
        return economyOperations.withdraw(id, amount);
    }

    public EconomyResponse set(UUID id, BigDecimal amount) {
        return economyOperations.set(id, amount);
    }

    public EconomyResponse reset(UUID id) {
        return economyOperations.reset(id);
    }

    /**
     * Atomically transfers money from {@code fromId} to {@code toId}, applying
     * cooldown and tax according to config. Both accounts must already exist.
     */
    public PayResult pay(UUID fromId, UUID toId, BigDecimal rawAmount) {
        return economyOperations.pay(fromId, toId, rawAmount);
    }

    public TransferCheckResult canTransfer(UUID fromId, UUID toId, BigDecimal amount) {
        return economyOperations.canTransfer(fromId, toId, amount);
    }

    // ── Transaction history ──────────────────────────────────────────────────

    public List<TransactionEntry> getTransactions(UUID playerId, int page, int pageSize) throws SQLException {
        return transactionHistoryService.getTransactions(playerId, page, pageSize);
    }

    public List<TransactionEntry> getTransactions(UUID playerId, int page, int pageSize,
            @Nullable TransactionType type, long fromMs, long toMs) throws SQLException {
        return transactionHistoryService.getTransactions(playerId, page, pageSize, type, fromMs, toMs);
    }

    public int countTransactions(UUID playerId) throws SQLException {
        return transactionHistoryService.countTransactions(playerId);
    }

    public int countTransactions(UUID playerId, @Nullable TransactionType type,
            long fromMs, long toMs) throws SQLException {
        return transactionHistoryService.countTransactions(playerId, type, fromMs, toMs);
    }

    public void logCustomTransaction(UUID accountId, TransactionEntry entry) {
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
        return leaderboardCache.getSnapshot(accountRegistry.liveRecords());
    }

    public int getRankOf(UUID accountId) {
        List<AccountRecord> snapshot = getBalTopSnapshot();
        for (int i = 0; i < snapshot.size(); i++) {
            if (snapshot.get(i).getId().equals(accountId)) {
                return i + 1;
            }
        }
        return -1;
    }

    // ── Formatting / currency ─────────────────────────────────────────────────

    public String format(BigDecimal amount) {
        EconomyConfigSnapshot currentConfig = config;
        BigDecimal scaled = amount.setScale(currentConfig.fractionalDigits(), RoundingMode.HALF_UP);
        String unit = scaled.abs().compareTo(BigDecimal.ONE) == 0
                ? currentConfig.currencySingular()
                : currentConfig.currencyPlural();
        return scaled.toPlainString() + " " + unit;
    }

    public String getCurrencyId() { return config.currencyId(); }
    public String getCurrencySingular() { return config.currencySingular(); }
    public String getCurrencyPlural() { return config.currencyPlural(); }
    public int getFractionalDigits() { return config.fractionalDigits(); }
    public BigDecimal getStartingBalance() { return config.startingBalance(); }
    public BigDecimal getMaxBalance() { return config.maxBalance(); }
    /** Returns the formatted max balance string, or null if unlimited. */
    public String getFormattedMaxBalance() {
        return config.maxBalance() != null ? format(config.maxBalance()) : null;
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

    public void shutdown() {
        flushDirty();
        transactionHistoryService.shutdown();
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
                        + ": '" + record.getLastKnownName() + "'. Resolve invalid names before starting SimpleEco.");
            }

            String normalized = AccountRegistry.normalizeName(record.getLastKnownName());
            UUID existingOwner = owners.putIfAbsent(normalized, record.getId());
            if (existingOwner != null && !existingOwner.equals(record.getId())) {
                throw new SQLException("Duplicate stored account name '" + record.getLastKnownName() + "' for "
                        + existingOwner + " and " + record.getId() + ". Resolve duplicates before starting SimpleEco.");
            }
        }
    }
}
