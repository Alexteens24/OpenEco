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
import dev.alexisbinh.openeco.api.ExchangeResult;
import dev.alexisbinh.openeco.api.MutationPolicyContext;
import dev.alexisbinh.openeco.api.OpenEcoApiException;
import dev.alexisbinh.openeco.api.OpenEcoAsyncApi;
import dev.alexisbinh.openeco.api.TransferPreviewResult;
import dev.alexisbinh.openeco.event.AccountCreateEvent;
import dev.alexisbinh.openeco.event.AccountDeleteEvent;
import dev.alexisbinh.openeco.event.AccountDeletedEvent;
import dev.alexisbinh.openeco.event.AccountRenameEvent;
import dev.alexisbinh.openeco.event.AccountRenamedEvent;
import dev.alexisbinh.openeco.event.BalanceChangeEvent;
import dev.alexisbinh.openeco.event.BalanceChangedEvent;
import dev.alexisbinh.openeco.event.PayCompletedEvent;
import dev.alexisbinh.openeco.event.PayEvent;
import dev.alexisbinh.openeco.model.AccountRecord;
import dev.alexisbinh.openeco.model.DirectTransferResult;
import dev.alexisbinh.openeco.model.PayResult;
import dev.alexisbinh.openeco.model.TransactionEntry;
import dev.alexisbinh.openeco.model.TransactionType;
import dev.alexisbinh.openeco.api.TransferCheckResult;
import dev.alexisbinh.openeco.crossserver.MultiWriterChangeNotifier;
import dev.alexisbinh.openeco.storage.AccountRepository;
import dev.alexisbinh.openeco.storage.MultiWriterRepository;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
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

    public enum LoginAccountStatus {
        READY,
        READY_WITH_STALE_NAME
    }

    public enum RefreshStatus {
        REFRESHED,
        REMOTE_MISSING,
        DEFERRED_DIRTY,
        DEFERRED_IN_USE,
        NAME_CONFLICT,
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
    private final EconomyPolicyRegistryImpl policyRegistry = new EconomyPolicyRegistryImpl();
    private volatile EconomyConfigSnapshot config;
    private volatile boolean crossServerEnabled;
    private final CrossServerMode crossServerMode;
    private final @Nullable MultiWriterRepository multiWriterRepository;
    private final ClusterJobCoordinatorImpl clusterJobCoordinator;
    private volatile long changeCursor;
    private volatile MultiWriterChangeNotifier changeNotifier = (id, version, kind) -> { };
    private final AtomicBoolean leaderboardRefreshRunning = new AtomicBoolean();
    private final AtomicBoolean changePollRunning = new AtomicBoolean();
    private final AtomicBoolean lazyLeaderboardDirty = new AtomicBoolean();
    private final AtomicLong accountIdentityVersion = new AtomicLong();
    private final ConcurrentHashMap<UUID, CompletableFuture<Optional<AccountRecord>>> accountLoads = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<Optional<AccountRecord>>> nameLoads = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> missingAccounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> missingNames = new ConcurrentHashMap<>();
    private final ExecutorService accountLoader;
    private final ExecutorService integrationExecutor;
    private volatile boolean lazyAccountMode;
    private volatile boolean lazyCacheEnabled;
    private volatile int lazyCacheMaximumSize;
    private volatile long lazyCacheExpireNanos;
    private boolean accountLoadingModeInitialized;

    // Pay cooldown tracker
    private final ConcurrentHashMap<UUID, Long> lastPayTime = new ConcurrentHashMap<>();

    public AccountService(AccountRepository repository, JavaPlugin plugin, FileConfiguration config) {
        this(repository, plugin.getLogger(), plugin.getName(), config, new BukkitEventDispatcher(plugin));
    }

    AccountService(AccountRepository repository, Logger log, String threadNamePrefix,
                   FileConfiguration config, EventDispatcher eventDispatcher) {
        this.repository = repository;
        this.log = log;
        this.eventDispatcher = eventDispatcher;
        this.accountLoader = boundedExecutor(threadNamePrefix + "-account-loader", 512);
        this.integrationExecutor = boundedExecutor(threadNamePrefix + "-integration", 256);
        this.transactionHistoryService = new TransactionHistoryService(repository, threadNamePrefix, this.log);
        this.economyOperations = new EconomyOperations(
                accountRegistry,
                () -> this.config,
                lastPayTime,
                this::logTransaction,
                eventDispatcher,
                this::markLeaderboardDirty,
                this::acquireAccountLease);
        readConfig(config);
        this.crossServerEnabled = config.getBoolean("cross-server.enabled", false);
        this.crossServerMode = CrossServerMode.fromConfig(config.getString("cross-server.mode", "multi-writer"));
        this.multiWriterRepository = crossServerEnabled && crossServerMode == CrossServerMode.MULTI_WRITER
                && repository instanceof MultiWriterRepository writer ? writer : null;
        if (crossServerEnabled && crossServerMode == CrossServerMode.MULTI_WRITER && multiWriterRepository == null) {
            throw new IllegalArgumentException("multi-writer mode requires a compatible remote JDBC repository");
        }
        this.clusterJobCoordinator = new ClusterJobCoordinatorImpl(multiWriterRepository, log);
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
        if (requestedLazyMode && requestedCacheEnabled && maximumSize <= 0) throw new IllegalArgumentException(
                "account-loading.lazy.cache.maximum-size must be greater than 0");
        if (requestedLazyMode && requestedCacheEnabled && expireMinutes <= 0) throw new IllegalArgumentException(
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

    public CrossServerMode getCrossServerMode() {
        return crossServerMode;
    }

    public boolean isMultiWriterEnabled() {
        return crossServerEnabled && crossServerMode == CrossServerMode.MULTI_WRITER;
    }

    public void setChangeNotifier(@Nullable MultiWriterChangeNotifier notifier) {
        changeNotifier = notifier == null ? (id, version, kind) -> { } : notifier;
    }

    public EconomyPolicyRegistryImpl getPolicyRegistry() {
        return policyRegistry;
    }

    public ClusterJobCoordinatorImpl getClusterJobCoordinator() {
        return clusterJobCoordinator;
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

    public long getLeaderboardRefreshIntervalMillis() {
        return config.balTopCacheTtlMs();
    }

    // ── Startup ─────────────────────────────────────────────────────────────

    public void loadAll() throws SQLException {
        clearStartupState();
        try {
            if (multiWriterRepository != null) {
                changeCursor = multiWriterRepository.currentChangeSequence();
            }
            if (lazyAccountMode) {
                log.info("Lazy account loading enabled; " + repository.countAccounts()
                        + " stored accounts will be loaded on demand"
                        + (lazyCacheEnabled ? " and retained in the working-set cache." : " without clean-record retention."));
                return;
            }
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
        synchronized (accountIdentityLock) {
            accountRegistry.clear();
            missingAccounts.clear();
            missingNames.clear();
            accountIdentityVersion.incrementAndGet();
            leaderboardCache.clearSnapshots();
        }
    }

    // ── Account management ───────────────────────────────────────────────────

    public boolean hasAccount(UUID id) {
        try (AccountLease lease = acquireAccountLease(id)) {
            return lease != null;
        }
    }

    public Optional<AccountRecord> getAccount(UUID id) {
        Optional<AccountRecord> snapshot = accountRegistry.getSnapshot(id);
        if (snapshot.isPresent()) {
            return snapshot;
        }

        try (AccountLease lease = acquireAccountLease(id)) {
            if (lease == null) return Optional.empty();
            synchronized (lease.record()) {
                return Optional.of(lease.record().snapshot());
            }
        }
    }

    public Optional<AccountRecord> findByName(String name) {
        try {
            return loadByNameAsync(name).join();
        } catch (CompletionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof OpenEcoApiException apiError) throw apiError;
            throw new OpenEcoApiException("Failed to load account by name", cause != null ? cause : error);
        }
    }

    public CompletableFuture<Optional<AccountRecord>> findByNameAsync(String name) {
        return loadByNameAsync(name);
    }

    private CompletableFuture<Optional<AccountRecord>> loadByNameAsync(String name) {
        Optional<AccountRecord> cached = accountRegistry.findSnapshotByName(name);
        if (cached.isPresent() || !lazyAccountMode || name == null) {
            return CompletableFuture.completedFuture(cached);
        }
        String normalized = AccountRegistry.normalizeName(name);
        if (lazyCacheEnabled && isNegativeCached(missingNames, normalized)) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        CompletableFuture<Optional<AccountRecord>> future = nameLoads.computeIfAbsent(normalized,
                ignored -> submit(accountLoader, () -> loadNameFromRepository(normalized),
                        "account name lookup queue is full"));
        future.whenComplete((ignoredResult, ignoredError) -> nameLoads.remove(normalized, future));
        return future;
    }

    private Optional<AccountRecord> loadNameFromRepository(String normalized) {
        while (true) {
            long observedIdentityVersion = accountIdentityVersion.get();
            try {
                Optional<AccountRecord> loaded = repository.loadAccountByName(normalized);
                synchronized (accountIdentityLock) {
                    Optional<AccountRecord> live = accountRegistry.findSnapshotByName(normalized);
                    if (live.isPresent()) return live;
                    if (observedIdentityVersion != accountIdentityVersion.get()) continue;
                    if (loaded.isEmpty()) {
                        if (lazyCacheEnabled) missingNames.put(normalized, System.nanoTime());
                        return Optional.empty();
                    }
                    AccountRecord record = prepareLoadedRecord(loaded.get());
                    if (!accountRegistry.addLoaded(record)) {
                        return accountRegistry.findSnapshotByName(normalized)
                                .or(() -> accountRegistry.getSnapshot(record.getId()));
                    }
                    missingAccounts.remove(record.getId());
                    missingNames.remove(normalized);
                    return accountRegistry.getSnapshot(record.getId());
                }
            } catch (SQLException e) {
                throw new OpenEcoApiException("Failed to load account by name", e);
            }
        }
    }

    public Map<UUID, String> loadAccountNames(java.util.Collection<UUID> accountIds) throws SQLException {
        return repository.loadNames(accountIds);
    }

    public <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
        return submit(integrationExecutor, supplier, "async integration queue is full");
    }

    public boolean isAccountNameCached(String name) {
        return accountRegistry.findSnapshotByName(name).isPresent();
    }

    public boolean isAccountNameKnownMissing(String name) {
        return name != null && lazyCacheEnabled
                && isNegativeCached(missingNames, AccountRegistry.normalizeName(name));
    }

    public CompletableFuture<Optional<AccountRecord>> preloadAccount(UUID id) {
        return loadAccountAsync(id).thenApply(optional -> optional.map(AccountRecord::snapshot));
    }

    public interface AccountPin extends AutoCloseable {
        @Override
        void close();
    }

    public Optional<AccountPin> pinAccount(UUID id) {
        AccountLease lease = acquireAccountLease(id);
        return lease == null ? Optional.empty() : Optional.of(lease::close);
    }

    /** Prepares and pins the account before Bukkit admits the player to the server. */
    public PreparedLoginAccount prepareLoginAccount(UUID id, String name) {
        if (crossServerEnabled) {
            RefreshStatus refreshStatus = refreshForLogin(id);
            if (refreshStatus == RefreshStatus.DEFERRED_DIRTY
                    || refreshStatus == RefreshStatus.DEFERRED_IN_USE
                    || refreshStatus == RefreshStatus.NAME_CONFLICT) {
                throw new OpenEcoApiException("Cross-server account refresh did not complete: " + refreshStatus);
            }
        }

        Optional<AccountRecord> existing = getAccount(id);
        LoginAccountStatus status = LoginAccountStatus.READY;
        if (existing.isEmpty()) {
            CreateAccountStatus created = createAccountDetailed(id, name);
            if (created != CreateAccountStatus.CREATED && created != CreateAccountStatus.ALREADY_EXISTS) {
                throw new OpenEcoApiException("Could not create account for login: " + created);
            }
        } else if (!existing.get().getLastKnownName().equals(name)) {
            RenameAccountStatus renamed = renameAccountDetailed(id, name);
            if (renamed == RenameAccountStatus.NOT_FOUND) {
                CreateAccountStatus created = createAccountDetailed(id, name);
                if (created != CreateAccountStatus.CREATED && created != CreateAccountStatus.ALREADY_EXISTS) {
                    throw new OpenEcoApiException("Could not restore account for login: " + created);
                }
            } else if (renamed != RenameAccountStatus.RENAMED && renamed != RenameAccountStatus.UNCHANGED) {
                status = LoginAccountStatus.READY_WITH_STALE_NAME;
                log.warning("Keeping the previous economy account name for " + id
                        + " because login rename returned " + renamed + '.');
            }
        }

        AccountPin pin = pinAccount(id)
                .orElseThrow(() -> new OpenEcoApiException("Economy account disappeared during login preparation"));
        try {
            if (!lazyAccountMode) flushAccountOrThrow(id);
            return new PreparedLoginAccount(status, pin);
        } catch (RuntimeException error) {
            pin.close();
            throw error;
        }
    }

    public record PreparedLoginAccount(LoginAccountStatus status, AccountPin pin) implements AutoCloseable {
        @Override
        public void close() {
            pin.close();
        }
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
        if (multiWriterRepository != null) {
            return createAccountMultiWriter(id, validatedName);
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
        if (multiWriterRepository != null) {
            return renameAccountMultiWriter(id, validatedName);
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
        if (multiWriterRepository != null) {
            return deleteAccountMultiWriter(id);
        }
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
        try (AccountLease lease = acquireAccountLease(id)) {
            return lease == null ? BigDecimal.ZERO : lease.record().getBalance(resolvedCurrencyId);
        }
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

        try (AccountLease lease = acquireAccountLease(id)) {
            return lease != null && lease.record().getBalance(currency.id()).compareTo(scaled) >= 0;
        }
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
        if (multiWriterRepository != null) {
            return mutateBalanceMultiWriter(id, currencyId, amount,
                    MultiWriterRepository.BalanceMutationKind.DEPOSIT,
                    TransactionType.GIVE, BalanceChangeEvent.Reason.GIVE);
        }
        return economyOperations.deposit(id, currencyId, amount);
    }

    /** Applies a multi-writer deposit at most once for the supplied durable operation id. */
    public EconomyOperationResponse depositOnce(
            UUID operationId, UUID id, String currencyId, BigDecimal amount) {
        if (operationId == null) throw new IllegalArgumentException("operationId must not be null");
        if (multiWriterRepository == null) {
            throw new IllegalStateException("Idempotent deposits require multi-writer mode");
        }
        try {
            Optional<MultiWriterRepository.AppliedBalanceMutation> applied =
                    multiWriterRepository.findAppliedBalanceMutation(operationId);
            if (applied.isPresent()) {
                MultiWriterRepository.AppliedBalanceMutation existing = applied.get();
                boolean sameDeposit = existing.accountId().equals(id)
                        && existing.currencyId().equalsIgnoreCase(currencyId)
                        && existing.transactionType() == TransactionType.GIVE
                        && existing.amount().compareTo(amount) == 0;
                if (!sameDeposit) {
                    return operationFailure(amount, existing.balanceAfter(),
                            "Operation ID was already used for another mutation");
                }
                return new EconomyOperationResponse(
                        existing.amount(), existing.balanceAfter(),
                        EconomyOperationResponse.ResponseType.SUCCESS, "Already applied");
            }
        } catch (SQLException e) {
            log.severe("Could not check idempotent deposit " + operationId + ": " + e.getMessage());
            return operationFailure(amount, BigDecimal.ZERO, "Storage unavailable");
        }
        return mutateBalanceMultiWriter(operationId, id, currencyId, amount,
                MultiWriterRepository.BalanceMutationKind.DEPOSIT,
                TransactionType.GIVE, BalanceChangeEvent.Reason.GIVE);
    }

    public Optional<OpenEcoAsyncApi.AppliedDeposit> findAppliedDeposit(UUID operationId) {
        if (multiWriterRepository == null) return Optional.empty();
        try {
            return multiWriterRepository.findAppliedBalanceMutation(operationId)
                    .map(applied -> new OpenEcoAsyncApi.AppliedDeposit(
                            applied.accountId(), applied.currencyId(), applied.amount()));
        } catch (SQLException e) {
            throw new OpenEcoApiException("Failed to load idempotent deposit", e);
        }
    }

    public EconomyOperationResponse withdraw(UUID id, BigDecimal amount) {
        return withdraw(id, config.currencyId(), amount);
    }

    public EconomyOperationResponse withdraw(UUID id, String currencyId, BigDecimal amount) {
        if (multiWriterRepository != null) {
            return mutateBalanceMultiWriter(id, currencyId, amount,
                    MultiWriterRepository.BalanceMutationKind.WITHDRAW,
                    TransactionType.TAKE, BalanceChangeEvent.Reason.TAKE);
        }
        return economyOperations.withdraw(id, currencyId, amount);
    }

    public EconomyOperationResponse set(UUID id, BigDecimal amount) {
        return set(id, config.currencyId(), amount);
    }

    public EconomyOperationResponse set(UUID id, String currencyId, BigDecimal amount) {
        if (multiWriterRepository != null) {
            return mutateBalanceMultiWriter(id, currencyId, amount,
                    MultiWriterRepository.BalanceMutationKind.SET,
                    TransactionType.SET, BalanceChangeEvent.Reason.SET);
        }
        return economyOperations.set(id, currencyId, amount);
    }

    public EconomyOperationResponse reset(UUID id) {
        return reset(id, config.currencyId());
    }

    public EconomyOperationResponse reset(UUID id, String currencyId) {
        if (multiWriterRepository != null) {
            CurrencyDefinition currency = resolveCurrency(currencyId);
            if (currency == null) return operationFailure(BigDecimal.ZERO, BigDecimal.ZERO, "Unknown currency");
            return mutateBalanceMultiWriter(id, currency.id(), currency.startingBalance(),
                    MultiWriterRepository.BalanceMutationKind.SET,
                    TransactionType.RESET, BalanceChangeEvent.Reason.RESET);
        }
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
        if (multiWriterRepository != null) return payMultiWriter(fromId, toId, currencyId, rawAmount, true);
        return economyOperations.pay(fromId, toId, currencyId, rawAmount);
    }

    /** Atomic peer transfer without pay cooldown, tax, or cancellable pay events. */
    public DirectTransferResult directTransfer(UUID fromId, UUID toId, BigDecimal rawAmount) {
        return directTransfer(fromId, toId, config.currencyId(), rawAmount);
    }

    public DirectTransferResult directTransfer(UUID fromId, UUID toId, String currencyId, BigDecimal rawAmount) {
        if (multiWriterRepository != null) return directTransferMultiWriter(fromId, toId, currencyId, rawAmount);
        return economyOperations.directTransfer(fromId, toId, currencyId, rawAmount);
    }

    public ExchangeResult exchange(UUID accountId, String fromCurrencyId, String toCurrencyId,
                                   BigDecimal rawFromAmount, BigDecimal rawToAmount) {
        CurrencyDefinition fromCurrency = resolveCurrency(fromCurrencyId);
        CurrencyDefinition toCurrency = resolveCurrency(toCurrencyId);
        if (fromCurrency == null || toCurrency == null) return exchangeFailure(ExchangeResult.Status.UNKNOWN_CURRENCY);
        if (fromCurrency.id().equalsIgnoreCase(toCurrency.id())) return exchangeFailure(ExchangeResult.Status.SAME_CURRENCY);
        BigDecimal fromAmount = rawFromAmount.setScale(fromCurrency.fractionalDigits(), RoundingMode.HALF_UP);
        BigDecimal toAmount = rawToAmount.setScale(toCurrency.fractionalDigits(), RoundingMode.HALF_UP);
        if (fromAmount.signum() <= 0 || toAmount.signum() <= 0) return exchangeFailure(ExchangeResult.Status.INVALID_AMOUNT);
        AccountRecord cached = getOrLoadLiveRecord(accountId);
        if (cached == null) return exchangeFailure(ExchangeResult.Status.ACCOUNT_NOT_FOUND);

        BigDecimal fromBefore = cached.getBalance(fromCurrency.id());
        BigDecimal toBefore = cached.getBalance(toCurrency.id());
        BalanceChangeEvent takeEvent = new BalanceChangeEvent(accountId, fromBefore,
                fromBefore.subtract(fromAmount), BalanceChangeEvent.Reason.TAKE, fromCurrency.id());
        eventDispatcher.dispatch(takeEvent);
        if (takeEvent.isCancelled()) return exchangeFailure(ExchangeResult.Status.CANCELLED);
        BalanceChangeEvent giveEvent = new BalanceChangeEvent(accountId, toBefore,
                toBefore.add(toAmount), BalanceChangeEvent.Reason.GIVE, toCurrency.id());
        eventDispatcher.dispatch(giveEvent);
        if (giveEvent.isCancelled()) return exchangeFailure(ExchangeResult.Status.CANCELLED);
        EconomyPolicyRegistryImpl.ResolvedPolicy exchangePolicy = policyRegistry.evaluate(
                new MutationPolicyContext(MutationPolicyContext.Kind.EXCHANGE, accountId, accountId,
                        toCurrency.id(), toAmount));
        if (!exchangePolicy.allowed()) return exchangeFailure(ExchangeResult.Status.POLICY_REJECTED);
        BigDecimal exchangeCap = minimumCap(toCurrency.maxBalance(), exchangePolicy.maximumTargetBalance());

        if (multiWriterRepository != null) {
            try {
                MultiWriterRepository.ExchangeMutationResult result = multiWriterRepository.exchange(
                        new MultiWriterRepository.ExchangeMutationRequest(UUID.randomUUID(), accountId,
                                fromCurrency.id(), toCurrency.id(), fromAmount, toAmount,
                                exchangeCap, System.currentTimeMillis(), "exchange"));
                if (result.status() != MultiWriterRepository.MutationStatus.SUCCESS) {
                    return exchangeFailure(switch (result.status()) {
                        case ACCOUNT_NOT_FOUND -> ExchangeResult.Status.ACCOUNT_NOT_FOUND;
                        case INSUFFICIENT_FUNDS -> ExchangeResult.Status.INSUFFICIENT_FUNDS;
                        case BALANCE_LIMIT -> ExchangeResult.Status.BALANCE_LIMIT;
                        case FROZEN -> ExchangeResult.Status.FROZEN;
                        default -> ExchangeResult.Status.STORAGE_ERROR;
                    });
                }
                applyAuthoritativeAccount(result.account());
                notifyUpsert(result.account());
                eventDispatcher.dispatch(new BalanceChangedEvent(accountId, result.fromBefore(), result.fromAfter(),
                        BalanceChangeEvent.Reason.TAKE, fromCurrency.id()));
                eventDispatcher.dispatch(new BalanceChangedEvent(accountId, result.toBefore(), result.toAfter(),
                        BalanceChangeEvent.Reason.GIVE, toCurrency.id()));
                leaderboardCache.markDirty(fromCurrency.id());
                leaderboardCache.markDirty(toCurrency.id());
                return new ExchangeResult(ExchangeResult.Status.SUCCESS, fromAmount, toAmount,
                        result.fromAfter(), result.toAfter());
            } catch (SQLException e) {
                log.severe("Multi-writer exchange failed for " + accountId + ": " + e.getMessage());
                return exchangeFailure(ExchangeResult.Status.STORAGE_ERROR);
            }
        }

        synchronized (cached) {
            if (!accountRegistry.isLive(accountId, cached)) return exchangeFailure(ExchangeResult.Status.ACCOUNT_NOT_FOUND);
            if (cached.isFrozen()) return exchangeFailure(ExchangeResult.Status.FROZEN);
            fromBefore = cached.getBalance(fromCurrency.id());
            toBefore = cached.getBalance(toCurrency.id());
            if (fromBefore.compareTo(fromAmount) < 0) return exchangeFailure(ExchangeResult.Status.INSUFFICIENT_FUNDS);
            BigDecimal fromAfter = fromBefore.subtract(fromAmount);
            BigDecimal toAfter = toBefore.add(toAmount);
            if (exchangeCap != null && toAfter.compareTo(exchangeCap) > 0) {
                return exchangeFailure(ExchangeResult.Status.BALANCE_LIMIT);
            }
            cached.setBalance(fromCurrency.id(), fromAfter);
            cached.setBalance(toCurrency.id(), toAfter);
            long now = System.currentTimeMillis();
            logTransaction(new TransactionEntry(TransactionType.TAKE, null, accountId, fromAmount,
                    fromBefore, fromAfter, now, "exchange", fromCurrency.id() + "->" + toCurrency.id(), fromCurrency.id()));
            logTransaction(new TransactionEntry(TransactionType.GIVE, null, accountId, toAmount,
                    toBefore, toAfter, now, "exchange", fromCurrency.id() + "->" + toCurrency.id(), toCurrency.id()));
            eventDispatcher.dispatch(new BalanceChangedEvent(accountId, fromBefore, fromAfter,
                    BalanceChangeEvent.Reason.TAKE, fromCurrency.id()));
            eventDispatcher.dispatch(new BalanceChangedEvent(accountId, toBefore, toAfter,
                    BalanceChangeEvent.Reason.GIVE, toCurrency.id()));
            leaderboardCache.markDirty(fromCurrency.id());
            leaderboardCache.markDirty(toCurrency.id());
            return new ExchangeResult(ExchangeResult.Status.SUCCESS, fromAmount, toAmount, fromAfter, toAfter);
        }
    }

    private static ExchangeResult exchangeFailure(ExchangeResult.Status status) {
        return new ExchangeResult(status, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
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

    /** Prunes durable cache-invalidation rows after every server has had ample time to consume them. */
    public void pruneMultiWriterChanges(int retentionDays) {
        if (multiWriterRepository == null || retentionDays <= 0) return;
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionDays);
        try {
            multiWriterRepository.pruneAccountChanges(cutoff);
        } catch (SQLException e) {
            log.warning("Failed to prune multi-writer change log: " + e.getMessage());
        }
    }

    /** Prunes expired idempotency keys, rolling usage, and old cluster-job rows. */
    public void pruneMultiWriterState(int operationRetentionDays, int abandonedJobRetentionDays) {
        if (multiWriterRepository == null
                || operationRetentionDays <= 0 || abandonedJobRetentionDays <= 0) return;
        long now = System.currentTimeMillis();
        try {
            MultiWriterRepository.PruneResult result = multiWriterRepository.pruneMultiWriterState(
                    now - TimeUnit.DAYS.toMillis(operationRetentionDays),
                    now - TimeUnit.DAYS.toMillis(abandonedJobRetentionDays));
            int total = result.operations() + result.policyUsage() + result.clusterJobs();
            if (total > 0) {
                log.info("Pruned multi-writer state: " + result.operations() + " operation(s), "
                        + result.policyUsage() + " policy usage row(s), "
                        + result.clusterJobs() + " cluster job(s).");
            }
        } catch (SQLException e) {
            log.warning("Failed to prune multi-writer state: " + e.getMessage());
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
        if (multiWriterRepository != null) return setFrozenMultiWriter(id, true);
        try (AccountLease lease = acquireAccountLease(id)) {
            if (lease == null) return false;
            AccountRecord record = lease.record();
            synchronized (record) {
                if (!accountRegistry.isLive(id, record)) return false;
                record.setFrozen(true);
                return true;
            }
        }
    }

    public boolean unfreezeAccount(UUID id) {
        if (multiWriterRepository != null) return setFrozenMultiWriter(id, false);
        try (AccountLease lease = acquireAccountLease(id)) {
            if (lease == null) return false;
            AccountRecord record = lease.record();
            synchronized (record) {
                if (!accountRegistry.isLive(id, record)) return false;
                record.setFrozen(false);
                return true;
            }
        }
    }

    public boolean isFrozen(UUID id) {
        try (AccountLease lease = acquireAccountLease(id)) {
            return lease != null && lease.record().isFrozen();
        }
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

    public BigDecimal getMinimumPayAmount(String currencyId) {
        CurrencyDefinition currency = resolveCurrency(currencyId);
        if (currency == null || config.payMinAmount() == null) return BigDecimal.ZERO;
        return config.payMinAmount().setScale(currency.fractionalDigits(), RoundingMode.HALF_UP);
    }
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
        if (multiWriterRepository != null) return true;
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
        if (multiWriterRepository != null) return;
        try {
            flushAccountOrThrow(id);
        } catch (OpenEcoApiException error) {
            log.warning("Cross-server flush failed for " + id + ": " + error.getMessage());
        }
    }

    private RefreshStatus refreshForLogin(UUID id) {
        RefreshStatus status = RefreshStatus.FAILED;
        for (int attempt = 0; attempt < 3; attempt++) {
            status = refreshAccountOrThrow(id);
            if (status != RefreshStatus.DEFERRED_IN_USE && status != RefreshStatus.DEFERRED_DIRTY) {
                return status;
            }
            if (attempt < 2) {
                try {
                    Thread.sleep(25L);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new OpenEcoApiException("Interrupted while waiting to refresh login account", error);
                }
            }
        }
        return status;
    }

    private void flushAccountOrThrow(UUID id) {
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
                throw new OpenEcoApiException("Pending transaction writes did not drain in time");
            }

            try {
                repository.upsertBatch(List.of(snap));
            } catch (SQLException e) {
                AccountRecord current = accountRegistry.getLiveRecord(id);
                if (current != null) {
                    current.markDirty();
                }
                throw new OpenEcoApiException("Failed to flush account " + id, e);
            }
        }
    }

    /**
     * Re-reads a single account from the database and refreshes the in-memory record.
     * Intended for cross-server use: call async when a player connects from another server.
     */
    public RefreshStatus refreshAccount(UUID id) {
        try {
            return refreshAccountOrThrow(id);
        } catch (OpenEcoApiException error) {
            log.warning("Cross-server refresh failed for " + id + ": " + error.getMessage());
            return RefreshStatus.FAILED;
        }
    }

    public Optional<AccountRecord> loadFreshAccount(UUID id) throws SQLException {
        Optional<AccountRecord> fresh = repository.loadAccount(id);
        if (fresh.isEmpty()) {
            removeCachedAccount(id);
            return Optional.empty();
        }
        AccountRecord record = fresh.get();
        alignLoadedRecordCurrencies(record, config);
        applyAuthoritativeAccount(record);
        return accountRegistry.getSnapshot(id);
    }

    /** Applies durable change-log invalidations from other backend servers. */
    public void pollMultiWriterChanges(int batchSize) {
        if (multiWriterRepository == null || !changePollRunning.compareAndSet(false, true)) return;
        try {
            List<MultiWriterRepository.AccountChange> changes = multiWriterRepository.loadChangesAfter(
                    changeCursor, Math.max(1, batchSize));
            if (changes.isEmpty()) return;
            if (changes.getFirst().sequence() > changeCursor + 1L) {
                log.warning("Multi-writer change-log gap detected; rebuilding the local account cache.");
                resyncAuthoritativeCache();
                return;
            }
            Map<UUID, MultiWriterRepository.AccountChange> latest = new java.util.LinkedHashMap<>();
            long nextCursor = changeCursor;
            for (MultiWriterRepository.AccountChange change : changes) {
                latest.put(change.accountId(), change);
                nextCursor = Math.max(nextCursor, change.sequence());
            }
            List<UUID> reload = latest.values().stream()
                    .filter(change -> change.kind() == MultiWriterRepository.ChangeKind.UPSERT)
                    .map(MultiWriterRepository.AccountChange::accountId).toList();
            Map<UUID, Optional<AccountRecord>> loaded = multiWriterRepository.loadAccounts(reload);
            latest.values().stream()
                    .filter(change -> change.kind() == MultiWriterRepository.ChangeKind.DELETE)
                    .forEach(change -> removeCachedAccount(change.accountId(), change.version()));
            boolean appliedAll = true;
            for (MultiWriterRepository.AccountChange change : latest.values()) {
                if (change.kind() == MultiWriterRepository.ChangeKind.DELETE) continue;
                Optional<AccountRecord> fresh = loaded.getOrDefault(change.accountId(), Optional.empty());
                if (fresh.isEmpty()) {
                    // The account was deleted after this UPSERT was logged. The later DELETE
                    // remains in the change log, but the authoritative absence is safe to apply now.
                    removeCachedAccount(change.accountId());
                } else if (!applyAuthoritativeAccount(fresh.get())) {
                    appliedAll = false;
                }
            }
            markAllLeaderboardsDirty();
            if (!appliedAll) {
                log.warning("Multi-writer change batch was not acknowledged because an authoritative "
                        + "account could not be applied; it will be retried.");
                return;
            }
            changeCursor = nextCursor;
        } catch (SQLException e) {
            log.warning("Multi-writer change polling failed: " + e.getMessage());
        } finally {
            changePollRunning.set(false);
        }
    }

    private boolean resyncAuthoritativeCache() throws SQLException {
        long watermark = multiWriterRepository.currentChangeSequence();
        if (lazyAccountMode) {
            List<UUID> cachedIds = accountRegistry.liveRecords().stream()
                    .map(AccountRecord::getId).toList();
            Map<UUID, Optional<AccountRecord>> loaded = multiWriterRepository.loadAccounts(cachedIds);
            boolean appliedAll = true;
            for (UUID id : cachedIds) {
                Optional<AccountRecord> fresh = loaded.getOrDefault(id, Optional.empty());
                if (fresh.isPresent()) appliedAll &= applyAuthoritativeAccount(fresh.get());
                else removeCachedAccount(id);
            }
            markAllLeaderboardsDirty();
            if (appliedAll) changeCursor = watermark;
            return appliedAll;
        }
        HashSet<UUID> seen = new HashSet<>();
        AtomicBoolean appliedAll = new AtomicBoolean(true);
        repository.loadBatches(ACCOUNT_LOAD_BATCH_SIZE, records -> {
            for (AccountRecord record : records) {
                alignLoadedRecordCurrencies(record, config);
                seen.add(record.getId());
                if (!applyAuthoritativeAccount(record)) appliedAll.set(false);
            }
        });
        for (AccountRecord record : List.copyOf(accountRegistry.liveRecords())) {
            if (!seen.contains(record.getId())) removeCachedAccount(record.getId());
        }
        markAllLeaderboardsDirty();
        if (appliedAll.get()) changeCursor = watermark;
        return appliedAll.get();
    }

    public long getChangeCursor() {
        return changeCursor;
    }

    private RefreshStatus refreshAccountOrThrow(UUID id) {
        try {
            while (true) {
                long observedIdentityVersion = accountIdentityVersion.get();
                Optional<AccountRecord> fresh = repository.loadAccount(id);
                if (fresh.isEmpty()) {
                    synchronized (persistenceLock) {
                        synchronized (accountIdentityLock) {
                            if (observedIdentityVersion != accountIdentityVersion.get()) continue;
                            AccountRecord live = accountRegistry.getLiveRecord(id);
                            if (live == null) return RefreshStatus.REMOTE_MISSING;
                            synchronized (live) {
                                if (live.isDirty()) {
                                    log.info("Cross-server deletion refresh deferred for " + id
                                            + " because the local account is dirty.");
                                    return RefreshStatus.DEFERRED_DIRTY;
                                }
                                if (accountRegistry.hasActiveLease(id)) {
                                    log.info("Cross-server deletion refresh deferred for " + id
                                            + " because the local account is in use.");
                                    return RefreshStatus.DEFERRED_IN_USE;
                                }
                                if (accountRegistry.remove(id, live)) {
                                    accountIdentityVersion.incrementAndGet();
                                    if (lazyCacheEnabled) missingAccounts.put(id, System.nanoTime());
                                    markAllLeaderboardsDirty();
                                }
                            }
                        }
                    }
                    return RefreshStatus.REMOTE_MISSING;
                }
                AccountRecord freshRecord = fresh.get();
                alignLoadedRecordCurrencies(freshRecord, config);

                synchronized (persistenceLock) {
                    synchronized (accountIdentityLock) {
                        if (observedIdentityVersion != accountIdentityVersion.get()) continue;
                        AccountRecord live = accountRegistry.getLiveRecord(id);
                        if (live != null) {
                            synchronized (live) {
                                if (live.isDirty()) {
                                    log.info("Cross-server refresh skipped for " + id
                                            + " because the in-memory account is dirty.");
                                    return RefreshStatus.DEFERRED_DIRTY;
                                }
                                if (accountRegistry.hasActiveLease(id)) {
                                    log.info("Cross-server refresh skipped for " + id
                                            + " because the in-memory account is in use.");
                                    return RefreshStatus.DEFERRED_IN_USE;
                                }
                                if (!accountRegistry.refreshInPlace(freshRecord)) {
                                    logRefreshNameConflict(id, freshRecord);
                                    return RefreshStatus.NAME_CONFLICT;
                                }
                            }
                        } else if (!accountRegistry.refreshInPlace(freshRecord)) {
                            logRefreshNameConflict(id, freshRecord);
                            return RefreshStatus.NAME_CONFLICT;
                        }
                        accountIdentityVersion.incrementAndGet();
                    }
                }
                markAllLeaderboardsDirty();
                return RefreshStatus.REFRESHED;
            }
        } catch (SQLException e) {
            throw new OpenEcoApiException("Failed to refresh account " + id, e);
        }
    }

    private void logRefreshNameConflict(UUID id, AccountRecord freshRecord) {
        log.warning("Cross-server refresh skipped for " + id
                + " because refreshed account name '" + freshRecord.getLastKnownName()
                + "' is already claimed by another in-memory account.");
    }

    public void shutdown() {
        shutdownExecutor(integrationExecutor);
        shutdownExecutor(accountLoader);
        transactionHistoryService.shutdown();
        if (multiWriterRepository == null) flushDirty();
    }

    // ── Multi-writer implementation ─────────────────────────────────────────

    private CreateAccountStatus createAccountMultiWriter(UUID id, String name) {
        EconomyConfigSnapshot current = config;
        Map<String, BigDecimal> balances = new HashMap<>();
        for (CurrencyDefinition currency : current.currencies().all()) {
            balances.put(currency.id(), currency.startingBalance());
        }
        long now = System.currentTimeMillis();
        try {
            MultiWriterRepository.AccountWriteResult result = multiWriterRepository.createAccount(
                    UUID.randomUUID(), id, name, balances, current.currencyId(), now);
            return switch (result.status()) {
                case SUCCESS -> {
                    applyAuthoritativeAccount(result.account());
                    notifyUpsert(result.account());
                    eventDispatcher.dispatch(new AccountCreateEvent(id, name, current.startingBalance()));
                    yield CreateAccountStatus.CREATED;
                }
                case ALREADY_EXISTS -> CreateAccountStatus.ALREADY_EXISTS;
                case NAME_IN_USE -> CreateAccountStatus.NAME_IN_USE;
                default -> CreateAccountStatus.NAME_IN_USE;
            };
        } catch (SQLException e) {
            log.severe("Multi-writer account create failed for " + id + ": " + e.getMessage());
            return CreateAccountStatus.NAME_IN_USE;
        }
    }

    private RenameAccountStatus renameAccountMultiWriter(UUID id, String newName) {
        AccountRecord current = getOrLoadLiveRecord(id);
        if (current == null) return RenameAccountStatus.NOT_FOUND;
        String oldName = current.getLastKnownName();
        if (oldName.equals(newName)) return RenameAccountStatus.UNCHANGED;
        AccountRenameEvent event = new AccountRenameEvent(id, oldName, newName);
        eventDispatcher.dispatch(event);
        if (event.isCancelled()) return RenameAccountStatus.CANCELLED;
        try {
            MultiWriterRepository.AccountWriteResult result = multiWriterRepository.renameAccount(
                    UUID.randomUUID(), id, newName, System.currentTimeMillis());
            return switch (result.status()) {
                case SUCCESS -> {
                    applyAuthoritativeAccount(result.account());
                    notifyUpsert(result.account());
                    eventDispatcher.dispatch(new AccountRenamedEvent(id, oldName, newName));
                    yield RenameAccountStatus.RENAMED;
                }
                case ACCOUNT_NOT_FOUND -> RenameAccountStatus.NOT_FOUND;
                case NAME_IN_USE -> RenameAccountStatus.NAME_IN_USE;
                default -> RenameAccountStatus.NAME_IN_USE;
            };
        } catch (SQLException e) {
            log.severe("Multi-writer account rename failed for " + id + ": " + e.getMessage());
            return RenameAccountStatus.NOT_FOUND;
        }
    }

    private DeleteAccountStatus deleteAccountMultiWriter(UUID id) {
        AccountRecord current = getOrLoadLiveRecord(id);
        if (current == null) return DeleteAccountStatus.NOT_FOUND;
        AccountDeleteEvent event = new AccountDeleteEvent(id, current.getLastKnownName(), current.getBalance());
        eventDispatcher.dispatch(event);
        if (event.isCancelled()) return DeleteAccountStatus.FAILED;
        try {
            MultiWriterRepository.AccountWriteResult result = multiWriterRepository.deleteAccount(
                    UUID.randomUUID(), id, System.currentTimeMillis());
            if (result.status() == MultiWriterRepository.MutationStatus.ACCOUNT_NOT_FOUND) {
                return DeleteAccountStatus.NOT_FOUND;
            }
            if (result.status() != MultiWriterRepository.MutationStatus.SUCCESS) return DeleteAccountStatus.FAILED;
            removeCachedAccount(id);
            changeNotifier.publish(id, result.account().getVersion(),
                    MultiWriterRepository.ChangeKind.DELETE);
            markAllLeaderboardsDirty();
            eventDispatcher.dispatch(new AccountDeletedEvent(id, event.getPlayerName(), event.getBalance()));
            return DeleteAccountStatus.DELETED;
        } catch (SQLException e) {
            log.severe("Multi-writer account delete failed for " + id + ": " + e.getMessage());
            return DeleteAccountStatus.FAILED;
        }
    }

    private boolean setFrozenMultiWriter(UUID id, boolean frozen) {
        try {
            MultiWriterRepository.AccountWriteResult result = multiWriterRepository.setFrozen(
                    UUID.randomUUID(), id, frozen, System.currentTimeMillis());
            if (result.status() != MultiWriterRepository.MutationStatus.SUCCESS) return false;
            applyAuthoritativeAccount(result.account());
            notifyUpsert(result.account());
            return true;
        } catch (SQLException e) {
            log.severe("Multi-writer freeze update failed for " + id + ": " + e.getMessage());
            return false;
        }
    }

    private EconomyOperationResponse mutateBalanceMultiWriter(
            UUID id, String currencyId, BigDecimal rawAmount,
            MultiWriterRepository.BalanceMutationKind kind,
            TransactionType transactionType, BalanceChangeEvent.Reason reason) {
        return mutateBalanceMultiWriter(UUID.randomUUID(), id, currencyId, rawAmount,
                kind, transactionType, reason);
    }

    private EconomyOperationResponse mutateBalanceMultiWriter(
            UUID operationId, UUID id, String currencyId, BigDecimal rawAmount,
            MultiWriterRepository.BalanceMutationKind kind,
            TransactionType transactionType, BalanceChangeEvent.Reason reason) {
        CurrencyDefinition currency = resolveCurrency(currencyId);
        if (currency == null) return operationFailure(BigDecimal.ZERO, BigDecimal.ZERO, "Unknown currency");
        BigDecimal amount = rawAmount.setScale(currency.fractionalDigits(), RoundingMode.HALF_UP);
        boolean invalid = kind == MultiWriterRepository.BalanceMutationKind.SET
                ? amount.signum() < 0 : amount.signum() <= 0;
        if (invalid) return operationFailure(amount, getBalance(id, currency.id()),
                kind == MultiWriterRepository.BalanceMutationKind.SET ? "Amount cannot be negative" : "Amount must be positive");
        AccountRecord cached = getOrLoadLiveRecord(id);
        if (cached == null) return operationFailure(amount, BigDecimal.ZERO, "Account not found");
        BigDecimal proposedBefore = cached.getBalance(currency.id());
        BigDecimal proposedAfter = switch (kind) {
            case DEPOSIT -> proposedBefore.add(amount);
            case WITHDRAW -> proposedBefore.subtract(amount);
            case SET -> amount;
        };
        BalanceChangeEvent event = new BalanceChangeEvent(id, proposedBefore, proposedAfter, reason, currency.id());
        eventDispatcher.dispatch(event);
        if (event.isCancelled()) return operationFailure(amount, proposedBefore, "Cancelled by plugin");
        MutationPolicyContext.Kind policyKind = switch (reason) {
            case GIVE -> MutationPolicyContext.Kind.DEPOSIT;
            case TAKE -> MutationPolicyContext.Kind.WITHDRAW;
            case SET -> MutationPolicyContext.Kind.SET;
            case RESET -> MutationPolicyContext.Kind.RESET;
            default -> MutationPolicyContext.Kind.DEPOSIT;
        };
        EconomyPolicyRegistryImpl.ResolvedPolicy policy = policyRegistry.evaluate(
                new MutationPolicyContext(policyKind, null, id, currency.id(), amount));
        if (!policy.allowed()) return operationFailure(amount, proposedBefore, "Policy rejected");
        BigDecimal effectiveCap = minimumCap(currency.maxBalance(), policy.maximumTargetBalance());
        long now = System.currentTimeMillis();
        try {
            MultiWriterRepository.BalanceMutationResult result = multiWriterRepository.mutateBalance(
                    new MultiWriterRepository.BalanceMutationRequest(operationId, id, currency.id(), kind,
                            amount, effectiveCap, transactionType, now, null, null));
            if (result.status() == MultiWriterRepository.MutationStatus.ALREADY_APPLIED) {
                applyAuthoritativeAccount(result.account());
                return new EconomyOperationResponse(
                        amount, result.after(), EconomyOperationResponse.ResponseType.SUCCESS, "Already applied");
            }
            if (result.status() != MultiWriterRepository.MutationStatus.SUCCESS) {
                return operationFailure(amount, result.before(), mutationError(result.status()));
            }
            applyAuthoritativeAccount(result.account());
            notifyUpsert(result.account());
            eventDispatcher.dispatch(new BalanceChangedEvent(id, result.before(), result.after(), reason, currency.id()));
            leaderboardCache.markDirty(currency.id());
            return new EconomyOperationResponse(amount, result.after(), EconomyOperationResponse.ResponseType.SUCCESS, "");
        } catch (SQLException e) {
            log.severe("Multi-writer balance mutation failed for " + id + ": " + e.getMessage());
            return operationFailure(amount, proposedBefore, "Storage unavailable");
        }
    }

    private PayResult payMultiWriter(UUID fromId, UUID toId, String currencyId,
                                     BigDecimal rawAmount, boolean firePayEvent) {
        CurrencyDefinition currency = resolveCurrency(currencyId);
        if (currency == null) return PayResult.unknownCurrency();
        BigDecimal sent = rawAmount.setScale(currency.fractionalDigits(), RoundingMode.HALF_UP);
        if (sent.signum() <= 0) return PayResult.invalidAmount();
        if (fromId.equals(toId)) return PayResult.selfTransfer();
        BigDecimal minimum = config.payMinAmount() == null ? null
                : config.payMinAmount().setScale(currency.fractionalDigits(), RoundingMode.HALF_UP);
        if (minimum != null && sent.compareTo(minimum) < 0) return PayResult.tooLow(minimum);
        BigDecimal tax = sent.multiply(config.payTaxRate())
                .divide(BigDecimal.valueOf(100), currency.fractionalDigits(), RoundingMode.HALF_UP);
        BigDecimal received = sent.subtract(tax);
        if (firePayEvent) {
            PayEvent event = new PayEvent(fromId, toId, sent, tax, received, currency.id());
            eventDispatcher.dispatch(event);
            if (event.isCancelled()) return PayResult.cancelled();
        }
        EconomyPolicyRegistryImpl.ResolvedPolicy policy = policyRegistry.evaluate(
                new MutationPolicyContext(MutationPolicyContext.Kind.PAY, fromId, toId, currency.id(), sent));
        if (!policy.allowed()) return PayResult.policyRejected();
        BigDecimal effectiveCap = minimumCap(currency.maxBalance(), policy.maximumTargetBalance());
        long now = System.currentTimeMillis();
        try {
            MultiWriterRepository.TransferMutationResult result = multiWriterRepository.transfer(
                    new MultiWriterRepository.TransferMutationRequest(UUID.randomUUID(), fromId, toId,
                            currency.id(), sent, received, tax, effectiveCap,
                            config.payCooldownMs(), true, toRollingPolicies(policy), now));
            PayResult mapped = mapPayResult(result);
            if (!mapped.isSuccess()) return mapped;
            applyAuthoritativeAccount(result.fromAccount());
            applyAuthoritativeAccount(result.toAccount());
            notifyUpsert(result.fromAccount());
            notifyUpsert(result.toAccount());
            PayCompletedEvent completed = new PayCompletedEvent(fromId, toId, sent, received, tax,
                    result.fromAccount().getBalance(currency.id()).add(sent),
                    result.fromAccount().getBalance(currency.id()),
                    result.toAccount().getBalance(currency.id()).subtract(received),
                    result.toAccount().getBalance(currency.id()), currency.id());
            eventDispatcher.dispatch(completed);
            eventDispatcher.dispatch(new BalanceChangedEvent(fromId, completed.getFromBalanceBefore(),
                    completed.getFromBalanceAfter(), BalanceChangeEvent.Reason.PAY_SENT, currency.id()));
            eventDispatcher.dispatch(new BalanceChangedEvent(toId, completed.getToBalanceBefore(),
                    completed.getToBalanceAfter(), BalanceChangeEvent.Reason.PAY_RECEIVED, currency.id()));
            leaderboardCache.markDirty(currency.id());
            return mapped;
        } catch (SQLException e) {
            log.severe("Multi-writer transfer failed from " + fromId + " to " + toId + ": " + e.getMessage());
            return PayResult.storageError();
        }
    }

    private DirectTransferResult directTransferMultiWriter(UUID fromId, UUID toId, String currencyId,
                                                           BigDecimal rawAmount) {
        CurrencyDefinition currency = resolveCurrency(currencyId);
        if (currency == null) return DirectTransferResult.failure(DirectTransferResult.Status.UNKNOWN_CURRENCY,
                BigDecimal.ZERO, "Unknown currency");
        BigDecimal amount = rawAmount.setScale(currency.fractionalDigits(), RoundingMode.HALF_UP);
        if (amount.signum() <= 0) return DirectTransferResult.failure(DirectTransferResult.Status.INVALID_AMOUNT, amount, "Amount must be positive");
        if (fromId.equals(toId)) return DirectTransferResult.failure(DirectTransferResult.Status.SELF_TRANSFER, amount, "Cannot transfer to self");
        EconomyPolicyRegistryImpl.ResolvedPolicy policy = policyRegistry.evaluate(
                new MutationPolicyContext(MutationPolicyContext.Kind.TRANSFER, fromId, toId, currency.id(), amount));
        if (!policy.allowed()) return DirectTransferResult.failure(DirectTransferResult.Status.POLICY_REJECTED,
                amount, "Policy rejected");
        try {
            MultiWriterRepository.TransferMutationResult result = multiWriterRepository.transfer(
                    new MultiWriterRepository.TransferMutationRequest(UUID.randomUUID(), fromId, toId,
                            currency.id(), amount, amount, BigDecimal.ZERO,
                            minimumCap(currency.maxBalance(), policy.maximumTargetBalance()), 0L, false,
                            toRollingPolicies(policy), System.currentTimeMillis()));
            if (result.status() != MultiWriterRepository.MutationStatus.SUCCESS) {
                return DirectTransferResult.failure(mapDirectStatus(result.status()), amount, mutationError(result.status()));
            }
            applyAuthoritativeAccount(result.fromAccount());
            applyAuthoritativeAccount(result.toAccount());
            notifyUpsert(result.fromAccount());
            notifyUpsert(result.toAccount());
            leaderboardCache.markDirty(currency.id());
            return DirectTransferResult.success(amount, result.fromAccount().getBalance(currency.id()),
                    result.toAccount().getBalance(currency.id()));
        } catch (SQLException e) {
            log.severe("Multi-writer direct transfer failed: " + e.getMessage());
            return DirectTransferResult.failure(DirectTransferResult.Status.STORAGE_ERROR, amount, "Storage unavailable");
        }
    }

    private PayResult mapPayResult(MultiWriterRepository.TransferMutationResult result) {
        return switch (result.status()) {
            case SUCCESS -> PayResult.success(result.sent(), result.received(), result.tax());
            case COOLDOWN -> PayResult.onCooldown(result.cooldownRemainingMs());
            case INSUFFICIENT_FUNDS -> PayResult.insufficientFunds();
            case ACCOUNT_NOT_FOUND -> PayResult.accountNotFound();
            case BALANCE_LIMIT -> PayResult.balanceLimit();
            case FROZEN -> PayResult.frozen();
            case POLICY_REJECTED -> PayResult.policyRejected();
            default -> PayResult.storageError();
        };
    }

    private static DirectTransferResult.Status mapDirectStatus(MultiWriterRepository.MutationStatus status) {
        return switch (status) {
            case ACCOUNT_NOT_FOUND -> DirectTransferResult.Status.ACCOUNT_NOT_FOUND;
            case INSUFFICIENT_FUNDS -> DirectTransferResult.Status.INSUFFICIENT_FUNDS;
            case BALANCE_LIMIT -> DirectTransferResult.Status.BALANCE_LIMIT;
            case FROZEN -> DirectTransferResult.Status.FROZEN;
            case POLICY_REJECTED -> DirectTransferResult.Status.POLICY_REJECTED;
            default -> DirectTransferResult.Status.STORAGE_ERROR;
        };
    }

    private static String mutationError(MultiWriterRepository.MutationStatus status) {
        return switch (status) {
            case ACCOUNT_NOT_FOUND -> "Account not found";
            case INSUFFICIENT_FUNDS -> "Insufficient funds";
            case BALANCE_LIMIT -> "Balance limit reached";
            case FROZEN -> "Account is frozen";
            case COOLDOWN -> "Pay cooldown active";
            case IDEMPOTENCY_CONFLICT -> "Operation ID payload conflict";
            default -> "Storage unavailable";
        };
    }

    private static EconomyOperationResponse operationFailure(BigDecimal amount, BigDecimal balance, String message) {
        return new EconomyOperationResponse(amount, balance, EconomyOperationResponse.ResponseType.FAILURE, message);
    }

    private static @Nullable BigDecimal minimumCap(@Nullable BigDecimal first, @Nullable BigDecimal second) {
        if (first == null) return second;
        if (second == null) return first;
        return first.min(second);
    }

    private static List<MultiWriterRepository.RollingPolicyConstraint> toRollingPolicies(
            EconomyPolicyRegistryImpl.ResolvedPolicy policy) {
        return policy.rollingConstraints().stream()
                .map(constraint -> new MultiWriterRepository.RollingPolicyConstraint(
                        constraint.providerId(), constraint.limit().maximumAmount(), constraint.limit().windowMs()))
                .toList();
    }

    private boolean applyAuthoritativeAccount(@Nullable AccountRecord fresh) {
        if (fresh == null) return true;
        synchronized (persistenceLock) {
            synchronized (accountIdentityLock) {
                AccountRecord live = accountRegistry.getLiveRecord(fresh.getId());
                if (live != null && live.getVersion() > fresh.getVersion()) return true;
                if (!accountRegistry.refreshInPlace(fresh)) {
                    log.warning("Could not apply authoritative account " + fresh.getId()
                            + " because its name is claimed by another cached account");
                    return false;
                }
                accountIdentityVersion.incrementAndGet();
                missingAccounts.remove(fresh.getId());
                missingNames.remove(AccountRegistry.normalizeName(fresh.getLastKnownName()));
                return true;
            }
        }
    }

    private void notifyUpsert(@Nullable AccountRecord account) {
        if (account != null) {
            changeNotifier.publish(account.getId(), account.getVersion(), MultiWriterRepository.ChangeKind.UPSERT);
        }
    }

    private void removeCachedAccount(UUID id) {
        removeCachedAccount(id, Long.MAX_VALUE);
    }

    private void removeCachedAccount(UUID id, long tombstoneVersion) {
        synchronized (persistenceLock) {
            synchronized (accountIdentityLock) {
                AccountRecord live = accountRegistry.getLiveRecord(id);
                if (live == null || live.getVersion() > tombstoneVersion) return;
                String normalizedName = AccountRegistry.normalizeName(live.getLastKnownName());
                if (accountRegistry.remove(id, live)) {
                    accountIdentityVersion.incrementAndGet();
                    if (lazyCacheEnabled) {
                        long now = System.nanoTime();
                        missingAccounts.put(id, now);
                        missingNames.put(normalizedName, now);
                    }
                }
            }
        }
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

    private @Nullable AccountLease acquireAccountLease(UUID id) {
        while (true) {
            AccountRecord record = getOrLoadLiveRecord(id);
            if (record == null) return null;
            AccountLease lease = accountRegistry.tryAcquireLease(id, record);
            if (lease != null) return lease;
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
        accountRegistry.markOnline(id);
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
                ignored -> submit(accountLoader, () -> {
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
        }, "account load queue is full"));
        future.whenComplete((ignoredResult, ignoredError) -> accountLoads.remove(id, future));
        return future;
    }

    private static ExecutorService boundedExecutor(String threadName, int queueCapacity) {
        return new ThreadPoolExecutor(
                4,
                4,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable, threadName);
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static <T> CompletableFuture<T> submit(
            ExecutorService executor, Supplier<T> supplier, String rejectionMessage) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    future.complete(supplier.get());
                } catch (Throwable error) {
                    future.completeExceptionally(error);
                }
            });
        } catch (RejectedExecutionException error) {
            future.completeExceptionally(new OpenEcoApiException(rejectionMessage, error));
        }
        return future;
    }

    private static void shutdownExecutor(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
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
