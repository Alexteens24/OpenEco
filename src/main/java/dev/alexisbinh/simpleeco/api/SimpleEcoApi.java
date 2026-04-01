package dev.alexisbinh.simpleeco.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface SimpleEcoApi {

    boolean hasAccount(UUID accountId);

    Optional<AccountSnapshot> getAccount(UUID accountId);

    Optional<AccountSnapshot> findByName(String name);

    AccountOperationResult createAccount(UUID accountId, String name);

    AccountOperationResult renameAccount(UUID accountId, String newName);

    AccountOperationResult deleteAccount(UUID accountId);

    BigDecimal getBalance(UUID accountId);

    /**
     * Returns whether the account has at least {@code amount} available.
     *
     * @param amount must be >= 0
     */
    boolean has(UUID accountId, BigDecimal amount);

    BalanceCheckResult canDeposit(UUID accountId, BigDecimal amount);

    BalanceCheckResult canWithdraw(UUID accountId, BigDecimal amount);

    BalanceChangeResult deposit(UUID accountId, BigDecimal amount);

    BalanceChangeResult withdraw(UUID accountId, BigDecimal amount);

    BalanceChangeResult setBalance(UUID accountId, BigDecimal amount);

    BalanceChangeResult reset(UUID accountId);

    TransferResult transfer(UUID fromId, UUID toId, BigDecimal amount);

    HistoryPage getHistory(UUID accountId, int page, int pageSize);

    HistoryPage getHistory(UUID accountId, int page, int pageSize, HistoryFilter filter);

    /**
     * Returns the 1-based leaderboard rank of the given account, or -1 if the
     * account does not exist.
     */
    int getRankOf(UUID accountId);

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

    List<AccountSnapshot> getTopAccounts(int limit);

    CurrencyInfo getCurrencyInfo();

    String format(BigDecimal amount);
}