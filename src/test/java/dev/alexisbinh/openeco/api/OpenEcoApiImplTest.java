package dev.alexisbinh.openeco.api;

import dev.alexisbinh.openeco.model.AccountRecord;
import dev.alexisbinh.openeco.model.PayResult;
import dev.alexisbinh.openeco.model.TransactionEntry;
import dev.alexisbinh.openeco.model.TransactionType;
import dev.alexisbinh.openeco.service.AccountService;
import net.milkbowl.vault2.economy.EconomyResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenEcoApiImplTest {

    @Mock
    private AccountService service;

    private OpenEcoApiImpl api;

    @BeforeEach
    void setUp() {
        api = new OpenEcoApiImpl(service);
    }

    @Test
    void createAccountReturnsCreatedResultWithImmutableSnapshot() {
        UUID accountId = UUID.randomUUID();
        AccountRecord account = new AccountRecord(accountId, "Alice", new BigDecimal("12.50"), 1L, 2L);

        when(service.createAccountDetailed(accountId, "Alice"))
            .thenReturn(AccountService.CreateAccountStatus.CREATED);
        when(service.getAccount(accountId)).thenReturn(Optional.of(account));

        AccountOperationResult result = api.createAccount(accountId, "Alice");

        assertTrue(result.isSuccess());
        assertEquals(AccountOperationResult.Status.CREATED, result.status());
        assertEquals("Alice", result.account().lastKnownName());
        assertEquals(new BigDecimal("12.50"), result.account().balance());
    }

    @Test
    void ensureAccountCreatesMissingAccount() {
        UUID accountId = UUID.randomUUID();
        AccountRecord account = new AccountRecord(accountId, "Alice", new BigDecimal("5.00"), 1L, 2L);

        when(service.getAccount(accountId)).thenReturn(Optional.empty()).thenReturn(Optional.of(account));
        when(service.createAccountDetailed(accountId, "Alice"))
                .thenReturn(AccountService.CreateAccountStatus.CREATED);

        AccountOperationResult result = api.ensureAccount(accountId, "Alice");

        assertEquals(AccountOperationResult.Status.CREATED, result.status());
        assertEquals("Alice", result.account().lastKnownName());
    }

    @Test
    void ensureAccountRenamesExistingAccountWhenNameChanged() {
        UUID accountId = UUID.randomUUID();
        AccountRecord before = new AccountRecord(accountId, "Alice", new BigDecimal("5.00"), 1L, 2L);
        AccountRecord after = new AccountRecord(accountId, "Alicia", new BigDecimal("5.00"), 1L, 3L);

        when(service.getAccount(accountId)).thenReturn(Optional.of(before)).thenReturn(Optional.of(after));
        when(service.renameAccountDetailed(accountId, "Alicia"))
                .thenReturn(AccountService.RenameAccountStatus.RENAMED);

        AccountOperationResult result = api.ensureAccount(accountId, "Alicia");

        assertEquals(AccountOperationResult.Status.RENAMED, result.status());
        assertEquals("Alicia", result.account().lastKnownName());
    }

    @Test
    void canWithdrawMapsServiceFailureIntoPluginResult() {
        UUID accountId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("15.00");
        BalanceCheckResult checkResult = new BalanceCheckResult(
                BalanceCheckResult.Status.INSUFFICIENT_FUNDS,
                amount,
                new BigDecimal("10.00"),
                new BigDecimal("10.00"));

        when(service.canWithdraw(accountId, amount)).thenReturn(checkResult);

        BalanceCheckResult result = api.canWithdraw(accountId, amount);

        assertEquals(BalanceCheckResult.Status.INSUFFICIENT_FUNDS, result.status());
        assertEquals(new BigDecimal("10.00"), result.currentBalance());
        assertEquals(new BigDecimal("10.00"), result.resultingBalance());
    }

    @Test
    void transferMapsCooldownResult() {
        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("5.00");

        when(service.pay(fromId, toId, amount)).thenReturn(PayResult.onCooldown(1_500L));

        TransferResult result = api.transfer(fromId, toId, amount);

        assertEquals(TransferResult.Status.COOLDOWN, result.status());
        assertEquals(1_500L, result.cooldownRemainingMs());
    }

    @Test
    void transferMapsSelfTransferResult() {
        UUID accountId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("5.00");

        when(service.pay(accountId, accountId, amount)).thenReturn(PayResult.selfTransfer());

        TransferResult result = api.transfer(accountId, accountId, amount);

        assertEquals(TransferResult.Status.SELF_TRANSFER, result.status());
    }

    @Test
    void transferTooLowDoesNotLeakMinimumIntoSentAmount() {
        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("0.05");

        when(service.pay(fromId, toId, amount)).thenReturn(PayResult.tooLow(new BigDecimal("0.10")));

        TransferResult result = api.transfer(fromId, toId, amount);

        assertEquals(TransferResult.Status.TOO_LOW, result.status());
        assertEquals(0, BigDecimal.ZERO.compareTo(result.sent()));
        assertEquals(0, BigDecimal.ZERO.compareTo(result.received()));
        assertEquals(0, BigDecimal.ZERO.compareTo(result.tax()));
    }

    @Test
    void transferMapsUnknownCurrencyResult() {
        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("5.00");

        when(service.pay(fromId, toId, amount)).thenReturn(PayResult.unknownCurrency());

        TransferResult result = api.transfer(fromId, toId, amount);

        assertEquals(TransferResult.Status.UNKNOWN_CURRENCY, result.status());
    }

    @Test
    void depositMapsUnknownCurrencyFailure() {
        UUID accountId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("10.00");
        AccountRecord account = new AccountRecord(accountId, "Alice", new BigDecimal("25.00"), 1L, 2L);

        when(service.getAccount(accountId)).thenReturn(Optional.of(account));
        when(service.deposit(accountId, amount)).thenReturn(new EconomyResponse(
                amount,
                account.getBalance(),
                EconomyResponse.ResponseType.FAILURE,
                "Unknown currency"));

        BalanceChangeResult result = api.deposit(accountId, amount);

        assertEquals(BalanceChangeResult.Status.UNKNOWN_CURRENCY, result.status());
        assertEquals(account.getBalance(), result.previousBalance());
        assertEquals(account.getBalance(), result.newBalance());
    }

    @Test
    void previewTransferDelegatesToService() {
        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("5.00");
        TransferPreviewResult preview = new TransferPreviewResult(
                TransferPreviewResult.Status.ALLOWED,
                amount,
                new BigDecimal("4.50"),
                new BigDecimal("0.50"),
                0,
                new BigDecimal("0.01"));

        when(service.previewTransfer(fromId, toId, amount)).thenReturn(preview);

        TransferPreviewResult result = api.previewTransfer(fromId, toId, amount);

        assertTrue(result.isAllowed());
        assertEquals(new BigDecimal("4.50"), result.received());
    }

    @Test
    void createAccountReturnsNameInUseWhenNameAlreadyBelongsToAnotherAccount() {
        UUID requestedId = UUID.randomUUID();

        when(service.createAccountDetailed(requestedId, "Alice"))
                .thenReturn(AccountService.CreateAccountStatus.NAME_IN_USE);

        AccountOperationResult result = api.createAccount(requestedId, "Alice");

        assertEquals(AccountOperationResult.Status.NAME_IN_USE, result.status());
    }

    @Test
    void renameAccountReturnsNameInUseWhenTargetNameBelongsToAnotherAccount() {
        UUID accountId = UUID.randomUUID();
        AccountRecord current = new AccountRecord(accountId, "Bob", new BigDecimal("10.00"), 1L, 2L);

        when(service.getAccount(accountId)).thenReturn(Optional.of(current));
        when(service.renameAccountDetailed(accountId, "Alice"))
                .thenReturn(AccountService.RenameAccountStatus.NAME_IN_USE);

        AccountOperationResult result = api.renameAccount(accountId, "Alice");

        assertEquals(AccountOperationResult.Status.NAME_IN_USE, result.status());
        assertEquals("Bob", result.account().lastKnownName());
    }

    @Test
    void getHistoryWrapsSqlExceptions() throws SQLException {
        UUID accountId = UUID.randomUUID();

        when(service.countTransactions(accountId)).thenThrow(new SQLException("database down"));

        assertThrows(OpenEcoApiException.class, () -> api.getHistory(accountId, 1, 10));
    }

    @Test
    void getHistoryMapsEntriesAndPageMetadata() throws SQLException {
        UUID accountId = UUID.randomUUID();
        TransactionEntry entry = new TransactionEntry(
                TransactionType.PAY_RECEIVED,
                UUID.randomUUID(),
                accountId,
                new BigDecimal("8.00"),
                new BigDecimal("2.00"),
                new BigDecimal("10.00"),
                99L,
                "QuestAddon",
                "Boss clear reward");

        when(service.countTransactions(accountId)).thenReturn(11);
        when(service.getTransactions(accountId, 2, 5)).thenReturn(List.of(entry));

        HistoryPage page = api.getHistory(accountId, 2, 5);

        assertEquals(2, page.page());
        assertEquals(5, page.pageSize());
        assertEquals(11, page.totalEntries());
        assertEquals(3, page.totalPages());
        assertEquals(TransactionKind.PAY_RECEIVED, page.entries().getFirst().kind());
        assertEquals("QuestAddon", page.entries().getFirst().source());
        assertEquals("Boss clear reward", page.entries().getFirst().note());
    }

    @Test
    void getRankOfDelegatesToService() {
        UUID accountId = UUID.randomUUID();

        when(service.getRankOf(accountId)).thenReturn(2);

        int rank = api.getRankOf(accountId);
        assertEquals(2, rank);
    }

    @Test
    void currencyAwareTopAccountsUseRequestedCurrencyBalanceInSnapshots() {
        AccountRecord account = new AccountRecord(
                UUID.randomUUID(),
                "Alice",
                "openeco",
                Map.of("openeco", new BigDecimal("100.00"), "gems", new BigDecimal("7")),
                1L,
                2L);

        when(service.getCanonicalCurrencyId("GEMS")).thenReturn("gems");
        when(service.getBalTopSnapshot("gems")).thenReturn(List.of(account));

        List<AccountSnapshot> top = api.getTopAccounts(10, "GEMS");

        assertEquals(1, top.size());
        assertEquals(0, new BigDecimal("7").compareTo(top.getFirst().balance()));
    }

    @Test
    void currencyAwareLeaderboardPageUsesRequestedCurrencyBalanceInSnapshots() {
        AccountRecord account = new AccountRecord(
                UUID.randomUUID(),
                "Alice",
                "openeco",
                Map.of("openeco", new BigDecimal("100.00"), "gems", new BigDecimal("7")),
                1L,
                2L);

        when(service.getCanonicalCurrencyId("gems")).thenReturn("gems");
        when(service.getBalTopSnapshot("gems")).thenReturn(List.of(account));

        LeaderboardPage page = api.getTopAccounts(1, 10, "gems");

        assertEquals(1, page.entries().size());
        assertEquals(0, new BigDecimal("7").compareTo(page.entries().getFirst().balance()));
    }

    @Test
    void getUUIDNameMapDelegatesToService() {
        UUID id = UUID.randomUUID();
        when(service.getUUIDNameMap()).thenReturn(Map.of(id, "Alice"));

        Map<UUID, String> map = api.getUUIDNameMap();
        assertEquals("Alice", map.get(id));
    }

    @Test
    void getRulesBuildsSnapshotFromServiceConfig() {
        when(service.getCurrencyId()).thenReturn("openeco");
        when(service.getCurrencySingular()).thenReturn("Coin");
        when(service.getCurrencyPlural()).thenReturn("Coins");
        when(service.getFractionalDigits()).thenReturn(2);
        when(service.getStartingBalance()).thenReturn(new BigDecimal("5.00"));
        when(service.getMaxBalance()).thenReturn(new BigDecimal("1000.00"));
        when(service.getPayCooldownMs()).thenReturn(5000L);
        when(service.getPayTaxRate()).thenReturn(new BigDecimal("2.50"));
        when(service.getPayMinAmount()).thenReturn(new BigDecimal("0.10"));
        when(service.getBalTopCacheTtlMs()).thenReturn(30000L);
        when(service.getHistoryRetentionDays()).thenReturn(-1);

        EconomyRulesSnapshot rules = api.getRules();

        assertEquals("openeco", rules.currency().id());
        assertEquals(5000L, rules.payCooldownMs());
        assertEquals(new BigDecimal("2.50"), rules.payTaxRate());
        assertTrue(rules.keepsHistoryForever());
    }

    @Test
    void hasRejectsNegativeAmounts() {
        assertThrows(IllegalArgumentException.class,
                () -> api.has(UUID.randomUUID(), new BigDecimal("-1.00")));

        verify(service, never()).has(any(UUID.class), any(BigDecimal.class));
    }

    @Test
    void canTransferDelegatesToService() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("10.00");

        when(service.canTransfer(from, to, amount))
                .thenReturn(new TransferCheckResult(TransferCheckResult.Status.ALLOWED, amount));

        TransferCheckResult result = api.canTransfer(from, to, amount);
        assertTrue(result.isAllowed());
    }

    @Test
    void canTransferReturnsSelfTransfer() {
        UUID accountId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("5.00");

        when(service.canTransfer(accountId, accountId, amount))
                .thenReturn(new TransferCheckResult(TransferCheckResult.Status.SELF_TRANSFER, amount));

        TransferCheckResult result = api.canTransfer(accountId, accountId, amount);
        assertEquals(TransferCheckResult.Status.SELF_TRANSFER, result.status());
    }

    @Test
    void getHistoryWithFilterMapsEntriesCorrectly() throws SQLException {
        UUID accountId = UUID.randomUUID();
        TransactionEntry entry = new TransactionEntry(
                TransactionType.GIVE, null, accountId,
                new BigDecimal("5.00"), BigDecimal.ZERO, new BigDecimal("5.00"), 500L);

        HistoryFilter filter = HistoryFilter.builder().kind(TransactionKind.GIVE).build();

        when(service.countTransactions(accountId, TransactionType.GIVE, 0, Long.MAX_VALUE)).thenReturn(1);
        when(service.getTransactions(accountId, 1, 10, TransactionType.GIVE, 0, Long.MAX_VALUE))
                .thenReturn(List.of(entry));

        HistoryPage page = api.getHistory(accountId, 1, 10, filter);

        assertEquals(1, page.totalEntries());
        assertEquals(TransactionKind.GIVE, page.entries().getFirst().kind());
    }

    @Test
    void getHistoryWithNoneFilterFallsBackToUnfiltered() throws SQLException {
        UUID accountId = UUID.randomUUID();
        when(service.countTransactions(accountId)).thenReturn(0);
        when(service.getTransactions(accountId, 1, 5)).thenReturn(List.of());

        HistoryPage page = api.getHistory(accountId, 1, 5, HistoryFilter.NONE);

        assertEquals(0, page.totalEntries());
    }

    @Test
    void logCustomTransactionWritesEntry() {
        UUID accountId = UUID.randomUUID();
        AccountRecord account = new AccountRecord(accountId, "Alice", new BigDecimal("100.00"), 1L, 2L);
        when(service.getAccount(accountId)).thenReturn(Optional.of(account));

        api.logCustomTransaction(accountId, new BigDecimal("10.00"), TransactionKind.GIVE);

        verify(service).logCustomTransaction(eq(accountId), any(TransactionEntry.class));
    }

    @Test
    void logCustomTransactionWithMetadataWritesMetadataIntoEntry() {
        UUID accountId = UUID.randomUUID();
        AccountRecord account = new AccountRecord(accountId, "Alice", new BigDecimal("100.00"), 1L, 2L);
        when(service.getAccount(accountId)).thenReturn(Optional.of(account));

        api.logCustomTransaction(
                accountId,
                new BigDecimal("10.00"),
                TransactionKind.GIVE,
                new TransactionMetadata("QuestAddon", "Daily contract payout"));

        verify(service).logCustomTransaction(eq(accountId), argThat(entry ->
                "QuestAddon".equals(entry.getSource())
                        && "Daily contract payout".equals(entry.getNote())));
    }

    @Test
    void logCustomTransactionRejectsNonPositiveAmounts() {
        assertThrows(IllegalArgumentException.class,
                () -> api.logCustomTransaction(UUID.randomUUID(), BigDecimal.ZERO, TransactionKind.GIVE));

        verify(service, never()).logCustomTransaction(any(UUID.class), any(TransactionEntry.class));
    }

    @Test
    void logCustomTransactionThrowsWhenAccountNotFound() {
        UUID accountId = UUID.randomUUID();
        when(service.getAccount(accountId)).thenReturn(Optional.empty());

        assertThrows(OpenEcoApiException.class,
                () -> api.logCustomTransaction(accountId, new BigDecimal("10.00"), TransactionKind.GIVE));
    }

    @Test
    void currencyAwareCustomTransactionCanonicalizesCurrencyIdBeforeWriting() {
        UUID accountId = UUID.randomUUID();
        when(service.getCanonicalCurrencyId(" GEMS ")).thenReturn("gems");
        when(service.hasAccount(accountId)).thenReturn(true);
        when(service.getBalance(accountId, "gems")).thenReturn(new BigDecimal("100.00"));

        api.logCustomTransaction(accountId, " GEMS ", new BigDecimal("10.00"), TransactionKind.GIVE);

        verify(service).logCustomTransaction(eq(accountId), argThat(entry ->
                "gems".equals(entry.getCurrencyId())
                        && 0 == new BigDecimal("100.00").compareTo(entry.getBalanceBefore())
                        && 0 == new BigDecimal("100.00").compareTo(entry.getBalanceAfter())));
    }
}