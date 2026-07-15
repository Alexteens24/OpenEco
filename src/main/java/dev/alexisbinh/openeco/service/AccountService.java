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

package dev.alexisbinh.openeco.service;

import dev.alexisbinh.openeco.api.BalanceCheckResult;
import dev.alexisbinh.openeco.api.OpenEcoApiException;
import dev.alexisbinh.openeco.api.TransferPreviewResult;
import dev.alexisbinh.openeco.event.AccountCreateEvent;
import dev.alexisbinh.openeco.event.AccountDeleteEvent;
import dev.alexisbinh.openeco.event.AccountDeletedEvent;
import dev.alexisbinh.openeco.event.AccountRenameEvent;
import dev.alexisbinh.openeco.event.AccountRenamedEvent;
import dev.alexisbinh.openeco.model.AccountRecord;
import dev.alexisbinh.openeco.model.DirectTransferResult;
import dev.alexisbinh.openeco.model.PayResult;
import dev.alexisbinh.openeco.model.TransactionEntry;
import dev.alexisbinh.openeco.model.TransactionType;
import dev.alexisbinh.openeco.api.TransferCheckResult;
import dev.alexisbinh.openeco.storage.AccountRepository;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

public class AccountService {

    private static final int ACCOUNT_LOAD_BATCH_SIZE = 500;
    private static final long NEGATIVE_CACHE_TTL_NANOS = TimeUnit.SECONDS.toNanos(30);

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
    private final Object accountIdentityLock = new Object();
    private final AccountRegistry accountRegistry = new AccountRegistry();
    private final LeaderboardCache leaderboardCache = new LeaderboardCache();
    private final TransactionHistoryService transactionHistoryService;
    private final EventDispatcher eventDispatcher;
    private final EconomyOperations economyOperations;
    private volatile EconomyConfigSnapshot config;
    private volatile boolean crossServerEnabled;
    private final AtomicBoolean leaderboardRefreshRunning = new AtomicBoolean();
    private final AtomicBoolean lazyLeaderboardDirty = new AtomicBoolean();
    private final AtomicLong accountIdentityVersion = new AtomicLong();
    private final ConcurrentHashMap<UUID, CompletableFuture<Optional<AccountRecord>>> accountLoads = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> missingAccounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> missingNames = new ConcurrentHashMap<>();
    private final ExecutorService accountLoader = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "OpenEco-account-loader");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean lazyAccountMode;
    private volatile boolean lazyCacheEnabled;
    private volatile int lazyCacheMaximumSize;
    private volatile long lazyCacheExpireNanos;
    private boolean accountLoadingModeInitialized;

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
                eventDispatcher,
                this::markLeaderboardDirty,
                this::getOrLoadLiveRecord);
        readConfig(config);
        this.crossServerEnabled = config.getBoolean("cross-server.enabled", false);
    }

    // ── Config ──────────────────────────────────────────────────────────────

    public void reloadConfig(FileConfiguration config) {
        readConfig(config);
    }

    private void readConfig(FileConfiguration config) {
        EconomyConfigSnapshot updated = EconomyConfigSnapshot.from(config);
        String loadingMode = config.getString("account-loading.mode", "eager");
        if (loadingMode == null || (!loadingMode.equalsIgnoreCase("eager") && !loadingMode.equalsIgnoreCase("lazy"))) {
            throw new IllegalArgumentException("account-loading.mode must be eager or lazy");
        }
        boolean requestedLazyMode = loadingMode.equalsIgnoreCase("lazy");
        boolean requestedCacheEnabled = config.getBoolean("account-loading.lazy.cache.enabled", true);
        int maximumSize = config.getInt("account-loading.lazy.cache.maximum-size", 50_000);
        long expireMinutes = config.getLong("account-loading.lazy.cache.expire-after-access-minutes", 30L);
        if (maximumSize <= 0) throw new IllegalArgumentException(
                "account-loading.lazy.cache.maximum-size must be greater than 0");
        if (expireMinutes <= 0) throw new IllegalArgumentException(
                "account-loading.lazy.cache.expire-after-access-minutes must be greater than 0");
        if (accountLoadingModeInitialized && requestedLazyMode != lazyAccountMode) {
            throw new IllegalArgumentException("account-loading.mode requires a server restart");
        }
        if (accountLoadingModeInitialized && lazyAccountMode && requestedCacheEnabled != lazyCacheEnabled) {
            throw new IllegalArgumentException("account-loading.lazy.cache.enabled requires a server restart");
        }
        this.lazyAccountMode = requestedLazyMode;
        this.lazyCacheEnabled = requestedCacheEnabled;
        this.accountLoadingModeInitialized = true;
        this.lazyCacheMaximumSize = maximumSize;
        this.lazyCacheExpireNanos = TimeUnit.MINUTES.toNanos(expireMinutes);
        this.config = updated;
        syncConfiguredCurrencies(updated);
        leaderboardCache.configureCurrencies(updated.currencies().all().stream().map(CurrencyDefinition::id).toList());
    }

    public boolean isCrossServerEnabled() {
        return crossServerEnabled;
    }

    public boolean isLazyAccountModeEnabled() {
        return lazyAccountMode;
    }

    public boolean isLazyAccountRetentionEnabled() {
        return lazyAccountMode && lazyCacheEnabled;
    }

    public long getAccountCacheMaintenanceIntervalSeconds() {
        return isLazyAccountRetentionEnabled() ? 60L : 1L;
    }

    // ── Startup ─────────────────────────────────────────────────────────────

    public void loadAll() throws SQLException {
        clearStartupState();
        if (lazyAccountMode) {
            log.info("Lazy account loading enabled; " + repository.countAccounts()
                    + " stored accounts will be loaded on demand"
                    + (lazyCacheEnabled ? " and retained in the working-set cache." : " without clean-record retention."));
            return;
        }
        try {
            int[] loaded = {0};
            repository.loadBatches(ACCOUNT_LOAD_BATCH_SIZE, records -> {
                for (AccountRecord record : records) {
                    validateLoadedName(record);
                    if (alignLoadedRecordCurrencies(record, config)) {
                        record.markDirty();
                    }
                    if (!accountRegistry.addLoaded(record)) {
                        throw new SQLException("Duplicate stored account id or name for " + record.getId()
                                + " ('" + record.getLastKnownName()
                                + "'). Resolve duplicates before starting openeco.");
                    }
                    loaded[0]++;
                }
            });
            leaderboardCache.rebuildAll(accountRegistry.liveRecords());
            log.info("Loaded " + loaded[0] + " economy accounts.");
        } catch (SQLException | RuntimeException e) {
            clearStartupState();
            throw e;
        }
    }

    private void clearStartupState() {
        accountRegistry.clear();
        leaderboardCache.clearSnapshots();
    }

    // ── Account management ───────────────────────────────────────────────────

    public boolean hasAccount(UUID id) {
        return getOrLoadLiveRecord(id) != null;
    }

    public Optional<AccountRecord> getAccount(UUID id) {
        Optional<AccountRecord> snapshot = accountRegistry.getSnapshot(id);
        if (snapshot.isPresent()) {
            return snapshot;
        }

        AccountRecord record = getOrLoadLiveRecord(id);
        if (record == null) {
            return Optional.empty();
        }
        synchronized (record) {
            return Optional.of(record.snapshot());
        }
    }

    public Optional<AccountRecord> findByName(String name) {
        Optional<AccountRecord> cached = accountRegistry.findSnapshotByName(name);
        if (cached.isPresent() || !lazyAccountMode || name == null) return cached;
        String normalized = AccountRegistry.normalizeName(name);
        if (lazyCacheEnabled && isNegativeCached(missingNames, normalized)) return Optional.empty();
        while (true) {
            long observedIdentityVersion = accountIdentityVersion.get();
            try {
                Optional<AccountRecord> loaded = repository.loadAccountByName(normalized);
                synchronized (accountIdentityLock) {
                    Optional<AccountRecord> live = accountRegistry.findSnapshotByName(name);
                    if (live.isPresent()) return live;
                    if (observedIdentityVersion != accountIdentityVersion.get()) continue;
                    if (loaded.isEmpty()) {
                        if (lazyCacheEnabled) missingNames.put(normalized, System.nanoTime());
                        return Optional.empty();
                    }
                    AccountRecord record = prepareLoadedRecord(loaded.get());
                    accountRegistry.addLoaded(record);
                    missingAccounts.remove(record.getId());
                    missingNames.remove(normalized);
                    return accountRegistry.getSnapshot(record.getId());
                }
            } catch (SQLException e) {
                throw new OpenEcoApiException("Failed to load account by name", e);
            }
        }
    }

    public CompletableFuture<Optional<AccountRecord>> findByNameAsync(String name) {
        return CompletableFuture.supplyAsync(() -> findByName(name), accountLoader);
    }

    public boolean isAccountNameCached(String name) {
        return accountRegistry.findSnapshotByName(name).isPresent();
    }

    public CompletableFuture<Optional<AccountRecord>> preloadAccount(UUID id) {
        return loadAccountAsync(id).thenApply(optional -> optional.map(AccountRecord::snapshot));
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
            if (getOrLoadLiveRecord(id) != null) {
                return CreateAccountStatus.ALREADY_EXISTS;
            }
            if (isNameClaimedByAnotherIncludingPersistence(id, validatedName)) {
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
            synchronized (accountIdentityLock) {
                if (lazyAccountMode) {
                    try {
                        if (!repository.insertAccount(record.snapshot())) return CreateAccountStatus.NAME_IN_USE;
                        record.clearDirty();
                    } catch (SQLException e) {
                        throw new OpenEcoApiException("Failed to create account", e);
                    }
                }
                if (!accountRegistry.create(record)) {
                    return CreateAccountStatus.NAME_IN_USE;
                }
                accountIdentityVersion.incrementAndGet();
            }
            missingAccounts.remove(id);
            missingNames.remove(AccountRegistry.normalizeName(validatedName));
            markAllLeaderboardsDirty();
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
            AccountRecord record = getOrLoadLiveRecord(id);
            if (record == null) return RenameAccountStatus.NOT_FOUND;

            synchronized (record) {
                if (!hasLiveRecord(id, record)) {
                    return RenameAccountStatus.NOT_FOUND;
                }

                String oldName = record.getLastKnownName();
                if (oldName.equals(validatedName)) {
                    return RenameAccountStatus.UNCHANGED;
                }

                if (isNameClaimedByAnotherIncludingPersistence(id, validatedName)) {
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
            AccountRecord record = getOrLoadLiveRecord(id);
            if (record == null) return RenameAccountStatus.NOT_FOUND;

            synchronized (accountIdentityLock) {
                synchronized (record) {
                    if (!hasLiveRecord(id, record)) {
                        return RenameAccountStatus.NOT_FOUND;
                    }

                    String currentName = record.getLastKnownName();
                    if (currentName.equals(validatedName)) {
                        return RenameAccountStatus.UNCHANGED;
                    }

                    if (isNameClaimedByAnotherIncludingPersistence(id, validatedName)) {
                        return RenameAccountStatus.NAME_IN_USE;
                    }

                    boolean wasDirty = record.isDirty();
                    if (lazyAccountMode) {
                        try {
                            if (!repository.renameAccount(id, validatedName, System.currentTimeMillis())) {
                                return RenameAccountStatus.NAME_IN_USE;
                            }
                        } catch (SQLException e) {
                            throw new OpenEcoApiException("Failed to rename account", e);
                        }
                    }
                    if (!accountRegistry.rename(record, validatedName)) {
                        return RenameAccountStatus.NAME_IN_USE;
                    }
                    accountIdentityVersion.incrementAndGet();
                    if (lazyAccountMode && !wasDirty) record.clearDirty();
                }
                missingNames.remove(AccountRegistry.normalizeName(validatedName));
                markAllLeaderboardsDirty();
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
            AccountRecord record = getOrLoadLiveRecord(id);
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
            AccountRecord record = getOrLoadLiveRecord(id);
            if (record == null) return DeleteAccountStatus.NOT_FOUND;
            Long previousPayTime;

            synchronized (accountIdentityLock) {
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
                accountIdentityVersion.incrementAndGet();
            }
            markAllLeaderboardsDirty();
            AccountDeletedEvent deletedEvent = new AccountDeletedEvent(id, event.getPlayerName(), event.getBalance());
            eventDispatcher.dispatch(deletedEvent);
            return DeleteAccountStatus.DELETED;
        }
    }

    public Map<UUID, String> getUUIDNameMap() {
        if (!lazyAccountMode) return accountRegistry.getUUIDNameMap();
        try {
            return repository.loadNameMap();
        } catch (SQLException e) {
            throw new OpenEcoApiException("Failed to load account name map", e);
        }
    }

    public int getAccountCount() {
        if (!lazyAccountMode) return accountRegistry.size();
        try {
            return repository.countAccounts();
        } catch (SQLException e) {
            log.warning("Failed to count stored accounts: " + e.getMessage());
            return accountRegistry.size();
        }
    }

    public List<String> getAccountNames() {
        if (lazyAccountMode) return accountRegistry.getCachedAccountNames();
        return new ArrayList<>(getUUIDNameMap().values());
    }

    public List<String> getCachedAccountNames() {
        return accountRegistry.getCachedAccountNames();
    }

    // ── Balance operations ───────────────────────────────────────────────────

    public BigDecimal getBalance(UUID id) {
        return getBalance(id, config.currencyId());
    }

    public BigDecimal getBalance(UUID id, String currencyId) {
        String resolvedCurrencyId = resolveCurrencyIdOrFallback(currencyId);
        AccountRecord record = getOrLoadLiveRecord(id);
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

        AccountRecord record = getOrLoadLiveRecord(id);
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

    public EconomyOperationResponse deposit(UUID id, BigDecimal amount) {
        return deposit(id, config.currencyId(), amount);
    }

    public EconomyOperationResponse deposit(UUID id, String currencyId, BigDecimal amount) {
        return economyOperations.deposit(id, currencyId, amount);
    }

    public EconomyOperationResponse withdraw(UUID id, BigDecimal amount) {
        return withdraw(id, config.currencyId(), amount);
    }

    public EconomyOperationResponse withdraw(UUID id, String currencyId, BigDecimal amount) {
        return economyOperations.withdraw(id, currencyId, amount);
    }

    public EconomyOperationResponse set(UUID id, BigDecimal amount) {
        return set(id, config.currencyId(), amount);
    }

    public EconomyOperationResponse set(UUID id, String currencyId, BigDecimal amount) {
        return economyOperations.set(id, currencyId, amount);
    }

    public EconomyOperationResponse reset(UUID id) {
        return reset(id, config.currencyId());
    }

    public EconomyOperationResponse reset(UUID id, String currencyId) {
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

    /** Atomic peer transfer without pay cooldown, tax, or cancellable pay events. */
    public DirectTransferResult directTransfer(UUID fromId, UUID toId, BigDecimal rawAmount) {
        return directTransfer(fromId, toId, config.currencyId(), rawAmount);
    }

    public DirectTransferResult directTransfer(UUID fromId, UUID toId, String currencyId, BigDecimal rawAmount) {
        return economyOperations.directTransfer(fromId, toId, currencyId, rawAmount);
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

    public LeaderboardView getLeaderboardPage(int offset, int limit) {
        return getLeaderboardPage(config.currencyId(), offset, limit);
    }

    public LeaderboardView getLeaderboardPage(String currencyId, int offset, int limit) {
        String resolvedCurrencyId = resolveCurrencyIdOrFallback(currencyId);
        if (lazyAccountMode) {
            try {
                return repository.loadLeaderboardPage(resolvedCurrencyId, offset, limit);
            } catch (SQLException e) {
                throw new OpenEcoApiException("Failed to load leaderboard", e);
            }
        }
        return leaderboardCache.page(resolvedCurrencyId, offset, limit);
    }

    public @Nullable LeaderboardEntry getLeaderboardEntry(int rank, String currencyId) {
        if (lazyAccountMode) {
            if (rank < 1) return null;
            LeaderboardView page = getLeaderboardPage(currencyId, Math.max(0, rank - 1), 1);
            return page.entries().isEmpty() ? null : page.entries().getFirst();
        }
        return leaderboardCache.entryAtRank(resolveCurrencyIdOrFallback(currencyId), rank);
    }

    public int getRankOf(UUID accountId) {
        return getRankOf(accountId, config.currencyId());
    }

    public int getRankOf(UUID accountId, String currencyId) {
        if (lazyAccountMode) {
            try {
                return repository.loadLeaderboardRank(resolveCurrencyIdOrFallback(currencyId), accountId);
            } catch (SQLException e) {
                throw new OpenEcoApiException("Failed to load leaderboard rank", e);
            }
        }
        return leaderboardCache.rankOf(resolveCurrencyIdOrFallback(currencyId), accountId);
    }

    public void refreshLeaderboards() {
        if (!leaderboardRefreshRunning.compareAndSet(false, true)) return;
        try {
            if (lazyAccountMode) {
                if (lazyLeaderboardDirty.getAndSet(false) && !flushDirty()) {
                    lazyLeaderboardDirty.set(true);
                }
                return;
            }
            leaderboardCache.refreshDirty(accountRegistry.liveRecords());
        } finally {
            leaderboardRefreshRunning.set(false);
        }
    }

    // ── Account freeze ────────────────────────────────────────────────────────

    public boolean freezeAccount(UUID id) {
        AccountRecord record = getOrLoadLiveRecord(id);
        if (record == null) return false;
        synchronized (record) {
            if (!accountRegistry.isLive(id, record)) return false;
            record.setFrozen(true);
            return true;
        }
    }

    public boolean unfreezeAccount(UUID id) {
        AccountRecord record = getOrLoadLiveRecord(id);
        if (record == null) return false;
        synchronized (record) {
            if (!accountRegistry.isLive(id, record)) return false;
            record.setFrozen(false);
            return true;
        }
    }

    public boolean isFrozen(UUID id) {
        AccountRecord record = getOrLoadLiveRecord(id);
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
        return currency.format(amount);
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
    public int getCurrencyCount() { return config.currencies().size(); }
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
    public int getBalTopPageSize() { return config.balTopPageSize(); }
    public int getHistoryPageSize() { return config.historyPageSize(); }
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
    public boolean flushDirty() {
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
            if (snapshots.isEmpty()) return true;

            if (!transactionHistoryService.waitForDrain()) {
                log.warning("Skipping balance flush because pending transaction writes did not drain in time.");
                for (AccountRecord snap : snapshots) {
                    AccountRecord live = accountRegistry.getLiveRecord(snap.getId());
                    if (live != null) live.markDirty();
                }
                return false;
            }

            try {
                repository.upsertBatch(snapshots);
                return true;
            } catch (SQLException e) {
                log.severe("Auto-save failed: " + e.getMessage());
                // Re-mark dirty so next cycle retries
                for (AccountRecord snap : snapshots) {
                    AccountRecord live = accountRegistry.getLiveRecord(snap.getId());
                    if (live != null) live.markDirty();
                }
                return false;
            }
        }
    }

    /**
     * Immediately flushes a single account to the database.
     * Intended for cross-server use: call async before the player disconnects.
     */
    public void flushAccount(UUID id) {
        synchronized (persistenceLock) {
            AccountRecord live = accountRegistry.getLiveRecord(id);
            if (live == null) return;
            AccountRecord snap;
            synchronized (live) {
                if (!live.isDirty()) return;
                snap = live.snapshot();
                live.clearDirty();
            }

            if (!transactionHistoryService.waitForDrain()) {
                log.warning("Skipping cross-server flush for " + id
                        + " because pending transaction writes did not drain in time.");
                AccountRecord current = accountRegistry.getLiveRecord(id);
                if (current != null) {
                    current.markDirty();
                }
                return;
            }

            try {
                repository.upsertBatch(List.of(snap));
            } catch (SQLException e) {
                log.warning("Cross-server flush failed for " + id + ": " + e.getMessage());
                AccountRecord current = accountRegistry.getLiveRecord(id);
                if (current != null) {
                    current.markDirty();
                }
            }
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
                synchronized (accountIdentityLock) {
                    AccountRecord live = accountRegistry.getLiveRecord(id);
                    if (live != null && live.isDirty()) {
                        log.info("Cross-server refresh skipped for " + id
                                + " because the in-memory account has unsaved local changes.");
                        return;
                    }
                    if (!accountRegistry.refreshInPlace(freshRecord)) {
                        log.warning("Cross-server refresh skipped for " + id
                                + " because refreshed account name '" + freshRecord.getLastKnownName()
                                + "' is already claimed by another in-memory account.");
                        return;
                    }
                    accountIdentityVersion.incrementAndGet();
                }
            }
            markAllLeaderboardsDirty();
        } catch (SQLException e) {
            log.warning("Cross-server refresh failed for " + id + ": " + e.getMessage());
        }
    }

    public void shutdown() {
        accountLoader.shutdown();
        try {
            if (!accountLoader.awaitTermination(5, TimeUnit.SECONDS)) accountLoader.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            accountLoader.shutdownNow();
        }
        transactionHistoryService.shutdown();
        flushDirty();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private @Nullable AccountRecord getOrLoadLiveRecord(UUID id) {
        AccountRecord cached = accountRegistry.getLiveRecord(id);
        if (cached != null || !lazyAccountMode) return cached;
        if (lazyCacheEnabled && isNegativeCached(missingAccounts, id)) return null;
        try {
            return loadAccountAsync(id).join().orElse(null);
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            throw new OpenEcoApiException("Failed to load account " + id, cause != null ? cause : e);
        }
    }

    private boolean isNameClaimedByAnotherIncludingPersistence(UUID id, String name) {
        if (accountRegistry.isNameClaimedByAnother(id, name)) {
            return true;
        }
        if (!lazyAccountMode) return false;
        try {
            return repository.isNameClaimedByAnother(id, AccountRegistry.normalizeName(name));
        } catch (SQLException e) {
            throw new OpenEcoApiException("Failed to validate account name", e);
        }
    }

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
        markAllLeaderboardsDirty();
    }

    private void markAllLeaderboardsDirty() {
        if (lazyAccountMode) lazyLeaderboardDirty.set(true);
        leaderboardCache.markAllDirty();
    }

    private void markLeaderboardDirty(String currencyId) {
        if (lazyAccountMode) lazyLeaderboardDirty.set(true);
        leaderboardCache.markDirty(currencyId);
    }

    public void markAccountOnline(UUID id) {
        if (isLazyAccountRetentionEnabled() || !lazyAccountMode) accountRegistry.markOnline(id);
    }

    public void markAccountOffline(UUID id) {
        accountRegistry.markOffline(id);
    }

    public void maintainAccountCache() {
        if (!lazyAccountMode) return;
        int evicted;
        synchronized (persistenceLock) {
            long expiry = lazyCacheEnabled ? lazyCacheExpireNanos : 0L;
            int maximumSize = lazyCacheEnabled ? lazyCacheMaximumSize : 0;
            evicted = accountRegistry.evict(expiry, maximumSize);
        }
        long cutoff = System.nanoTime() - NEGATIVE_CACHE_TTL_NANOS;
        missingAccounts.entrySet().removeIf(entry -> entry.getValue() < cutoff);
        missingNames.entrySet().removeIf(entry -> entry.getValue() < cutoff);
        if (evicted > 0) log.fine("Evicted " + evicted + " inactive economy accounts from cache.");
    }

    private CompletableFuture<Optional<AccountRecord>> loadAccountAsync(UUID id) {
        AccountRecord cached = accountRegistry.getLiveRecord(id);
        if (cached != null) return CompletableFuture.completedFuture(Optional.of(cached));
        if (lazyCacheEnabled && isNegativeCached(missingAccounts, id)) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        CompletableFuture<Optional<AccountRecord>> future = accountLoads.computeIfAbsent(id,
                ignored -> CompletableFuture.supplyAsync(() -> {
            while (true) {
                long observedIdentityVersion = accountIdentityVersion.get();
                try {
                    Optional<AccountRecord> loaded = repository.loadAccount(id);
                    synchronized (accountIdentityLock) {
                        AccountRecord live = accountRegistry.getLiveRecord(id);
                        if (live != null) return Optional.of(live);
                        if (observedIdentityVersion != accountIdentityVersion.get()) continue;
                        if (loaded.isEmpty()) {
                            if (lazyCacheEnabled) missingAccounts.put(id, System.nanoTime());
                            return Optional.<AccountRecord>empty();
                        }
                        AccountRecord record = prepareLoadedRecord(loaded.get());
                        if (!accountRegistry.addLoaded(record)) {
                            return Optional.ofNullable(accountRegistry.getLiveRecord(id));
                        }
                        missingAccounts.remove(id);
                        missingNames.remove(AccountRegistry.normalizeName(record.getLastKnownName()));
                        return Optional.of(record);
                    }
                } catch (SQLException e) {
                    throw new CompletionException(e);
                }
            }
        }, accountLoader));
        future.whenComplete((ignoredResult, ignoredError) -> accountLoads.remove(id, future));
        return future;
    }

    private AccountRecord prepareLoadedRecord(AccountRecord record) throws SQLException {
        validateLoadedName(record);
        if (alignLoadedRecordCurrencies(record, config)) record.markDirty();
        return record;
    }

    private static <K> boolean isNegativeCached(ConcurrentHashMap<K, Long> cache, K key) {
        Long cachedAt = cache.get(key);
        if (cachedAt == null) return false;
        if (System.nanoTime() - cachedAt < NEGATIVE_CACHE_TTL_NANOS) return true;
        cache.remove(key, cachedAt);
        return false;
    }

    private void syncConfiguredCurrencies(EconomyConfigSnapshot configSnapshot) {
        accountRegistry.syncCurrencies(configSnapshot.currencyId(), currencyId -> {
            CurrencyDefinition currency = configSnapshot.currencies().find(currencyId).orElse(null);
            return currency != null ? currency.id() : null;
        });
    }

    private boolean alignLoadedRecordCurrencies(AccountRecord record, EconomyConfigSnapshot configSnapshot) {
        boolean changed = record.canonicalizeCurrencyIds(currencyId -> {
            CurrencyDefinition currency = configSnapshot.currencies().find(currencyId).orElse(null);
            return currency != null ? currency.id() : null;
        });
        record.setPrimaryCurrencyId(configSnapshot.currencyId());
        record.clearDirty();
        return changed;
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

    private static void validateLoadedName(AccountRecord record) throws SQLException {
        String sanitized = sanitizeAccountName(record.getLastKnownName());
        if (sanitized == null || !sanitized.equals(record.getLastKnownName())) {
            throw new SQLException("Invalid stored account name for " + record.getId()
                    + ": '" + record.getLastKnownName() + "'. Resolve invalid names before starting openeco.");
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
