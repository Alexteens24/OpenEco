package dev.alexisbinh.simpleeco.api;

import dev.alexisbinh.simpleeco.model.AccountRecord;
import dev.alexisbinh.simpleeco.model.PayResult;
import dev.alexisbinh.simpleeco.model.TransactionEntry;
import dev.alexisbinh.simpleeco.model.TransactionType;
import dev.alexisbinh.simpleeco.service.AccountService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimpleEcoApiImplTest {

    @Mock
    private AccountService service;

    private SimpleEcoApiImpl api;

    @BeforeEach
    void setUp() {
        api = new SimpleEcoApiImpl(service);
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
    void canWithdrawMapsServiceFailureIntoPluginResult() {
        UUID accountId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("15.00");
        AccountRecord account = new AccountRecord(accountId, "Alice", new BigDecimal("10.00"), 1L, 2L);
        EconomyResponse response = new EconomyResponse(
                amount,
                new BigDecimal("10.00"),
                EconomyResponse.ResponseType.FAILURE,
                "Insufficient funds");

        when(service.getAccount(accountId)).thenReturn(Optional.of(account));
        when(service.canWithdraw(accountId, amount)).thenReturn(response);

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

        assertThrows(SimpleEcoApiException.class, () -> api.getHistory(accountId, 1, 10));
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
                99L);

        when(service.countTransactions(accountId)).thenReturn(11);
        when(service.getTransactions(accountId, 2, 5)).thenReturn(List.of(entry));

        HistoryPage page = api.getHistory(accountId, 2, 5);

        assertEquals(2, page.page());
        assertEquals(5, page.pageSize());
        assertEquals(11, page.totalEntries());
        assertEquals(3, page.totalPages());
        assertEquals(TransactionKind.PAY_RECEIVED, page.entries().getFirst().kind());
    }

    @Test
    void getRankOfDelegatesToService() {
        UUID accountId = UUID.randomUUID();

        when(service.getRankOf(accountId)).thenReturn(2);

        int rank = api.getRankOf(accountId);
        assertEquals(2, rank);
    }

    @Test
    void getUUIDNameMapDelegatesToService() {
        UUID id = UUID.randomUUID();
        when(service.getUUIDNameMap()).thenReturn(Map.of(id, "Alice"));

        Map<UUID, String> map = api.getUUIDNameMap();
        assertEquals("Alice", map.get(id));
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
    void logCustomTransactionRejectsNonPositiveAmounts() {
        assertThrows(IllegalArgumentException.class,
                () -> api.logCustomTransaction(UUID.randomUUID(), BigDecimal.ZERO, TransactionKind.GIVE));

        verify(service, never()).logCustomTransaction(any(UUID.class), any(TransactionEntry.class));
    }

    @Test
    void logCustomTransactionThrowsWhenAccountNotFound() {
        UUID accountId = UUID.randomUUID();
        when(service.getAccount(accountId)).thenReturn(Optional.empty());

        assertThrows(SimpleEcoApiException.class,
                () -> api.logCustomTransaction(accountId, new BigDecimal("10.00"), TransactionKind.GIVE));
    }
}