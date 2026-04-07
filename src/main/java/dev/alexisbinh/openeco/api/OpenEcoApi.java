package dev.alexisbinh.openeco.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface OpenEcoApi {

    boolean hasAccount(UUID accountId);

    Optional<AccountSnapshot> getAccount(UUID accountId);

    Optional<AccountSnapshot> findByName(String name);

    AccountOperationResult createAccount(UUID accountId, String name);

    /**
     * Ensures the account exists with the supplied name.
     *
     * <p>If the account does not exist yet, it is created. If it already exists
     * with a different name, a rename is attempted. If it already exists with the
     * same name, the result is {@link AccountOperationResult.Status#UNCHANGED}.</p>
     */
    AccountOperationResult ensureAccount(UUID accountId, String name);

    AccountOperationResult renameAccount(UUID accountId, String newName);

    AccountOperationResult deleteAccount(UUID accountId);

    BigDecimal getBalance(UUID accountId);

    BigDecimal getBalance(UUID accountId, String currencyId);

    /**
     * Returns whether the account has at least {@code amount} available.
     *
     * @param amount must be >= 0
     */
    boolean has(UUID accountId, BigDecimal amount);

    boolean has(UUID accountId, String currencyId, BigDecimal amount);

    BalanceCheckResult canDeposit(UUID accountId, BigDecimal amount);

    BalanceCheckResult canDeposit(UUID accountId, String currencyId, BigDecimal amount);

    BalanceCheckResult canWithdraw(UUID accountId, BigDecimal amount);

    BalanceCheckResult canWithdraw(UUID accountId, String currencyId, BigDecimal amount);

    BalanceChangeResult deposit(UUID accountId, BigDecimal amount);

    BalanceChangeResult deposit(UUID accountId, String currencyId, BigDecimal amount);

    BalanceChangeResult withdraw(UUID accountId, BigDecimal amount);

    BalanceChangeResult withdraw(UUID accountId, String currencyId, BigDecimal amount);

    BalanceChangeResult setBalance(UUID accountId, BigDecimal amount);

    BalanceChangeResult setBalance(UUID accountId, String currencyId, BigDecimal amount);

    BalanceChangeResult reset(UUID accountId);

    BalanceChangeResult reset(UUID accountId, String currencyId);

    TransferResult transfer(UUID fromId, UUID toId, BigDecimal amount);

    TransferResult transfer(UUID fromId, UUID toId, String currencyId, BigDecimal amount);

    /**
     * Returns a preview of what a transfer would do without mutating any state.
     *
     * <p>Unlike {@link #canTransfer(UUID, UUID, BigDecimal)}, this preview also
     * evaluates pay cooldown and minimum pay amount and includes tax and received
     * amount calculations. It does not account for cancellable plugin events.</p>
     */
    TransferPreviewResult previewTransfer(UUID fromId, UUID toId, BigDecimal amount);

    TransferPreviewResult previewTransfer(UUID fromId, UUID toId, String currencyId, BigDecimal amount);

    HistoryPage getHistory(UUID accountId, int page, int pageSize);

    HistoryPage getHistory(UUID accountId, String currencyId, int page, int pageSize);

    HistoryPage getHistory(UUID accountId, int page, int pageSize, HistoryFilter filter);

    HistoryPage getHistory(UUID accountId, String currencyId, int page, int pageSize, HistoryFilter filter);

    /**
     * Returns the 1-based leaderboard rank of the given account, or -1 if the
     * account does not exist.
     */
    int getRankOf(UUID accountId);

    int getRankOf(UUID accountId, String currencyId);

    /**
     * Returns an unmodifiable snapshot of all known account UUIDs mapped to their
     * last-known display names.
     */
    Map<UUID, String> getUUIDNameMap();

    /**
     * Checks whether a transfer from {@code fromId} to {@code toId} of {@code amount}
     * would succeed without actually executing it.
     *
     * <p>Only balance-level constraints are checked (account existence, sufficient
     * funds, balance cap, self-transfer). Cooldown and tax are not evaluated.</p>
     */
    TransferCheckResult canTransfer(UUID fromId, UUID toId, BigDecimal amount);

    TransferCheckResult canTransfer(UUID fromId, UUID toId, String currencyId, BigDecimal amount);

    /**
     * Writes a custom transaction entry to the history log without modifying any
     * balance. Useful for addon-initiated operations that should appear in a
     * player's transaction history.
     *
     * @param accountId the account whose history will receive the entry
     * @param amount    the amount to record (must be positive)
     * @param kind      the kind to record; must not be {@code null}
     */
    void logCustomTransaction(UUID accountId, BigDecimal amount, TransactionKind kind);

    void logCustomTransaction(UUID accountId, String currencyId, BigDecimal amount, TransactionKind kind);

    /**
     * Writes a custom history entry with addon-supplied metadata.
     *
     * <p>{@code metadata.source()} should usually identify the addon or feature
     * that emitted the entry. {@code metadata.note()} can carry a short human-readable
     * explanation such as a quest name or payout reason.</p>
     */
    void logCustomTransaction(UUID accountId, BigDecimal amount, TransactionKind kind, TransactionMetadata metadata);

    void logCustomTransaction(UUID accountId, String currencyId, BigDecimal amount, TransactionKind kind, TransactionMetadata metadata);

    List<AccountSnapshot> getTopAccounts(int limit);

    List<AccountSnapshot> getTopAccounts(int limit, String currencyId);

    /**
     * Returns a page from the leaderboard, ordered by balance descending.
     *
     * @param page     1-based page number
     * @param pageSize number of entries per page
     */
    LeaderboardPage getTopAccounts(int page, int pageSize);

    LeaderboardPage getTopAccounts(int page, int pageSize, String currencyId);

    /** Returns the current configured operational rules exposed by the plugin. */
    EconomyRulesSnapshot getRules();

    /**
     * Freezes the account. A frozen account cannot deposit, withdraw, or pay.
     *
     * @return {@code true} if the account existed and was frozen; {@code false} if not found
     */
    boolean freezeAccount(UUID accountId);

    /**
     * Unfreezes the account, restoring normal operation.
     *
     * @return {@code true} if the account existed and was unfrozen; {@code false} if not found
     */
    boolean unfreezeAccount(UUID accountId);

    /** Returns {@code true} if the account exists and is currently frozen. */
    boolean isFrozen(UUID accountId);

    CurrencyInfo getCurrencyInfo();

    CurrencyInfo getCurrencyInfo(String currencyId);

    List<CurrencyInfo> getCurrencies();

    boolean hasCurrency(String currencyId);

    String format(BigDecimal amount);

    String format(BigDecimal amount, String currencyId);
}