package dev.alexisbinh.openeco.service;

import dev.alexisbinh.openeco.api.BalanceCheckResult;
import dev.alexisbinh.openeco.api.TransferPreviewResult;
import dev.alexisbinh.openeco.api.TransferCheckResult;
import dev.alexisbinh.openeco.event.BalanceChangeEvent;
import dev.alexisbinh.openeco.event.BalanceChangedEvent;
import dev.alexisbinh.openeco.event.PayEvent;
import dev.alexisbinh.openeco.event.PayCompletedEvent;
import dev.alexisbinh.openeco.model.AccountRecord;
import dev.alexisbinh.openeco.model.PayResult;
import dev.alexisbinh.openeco.model.TransactionEntry;
import dev.alexisbinh.openeco.model.TransactionType;
import net.milkbowl.vault2.economy.EconomyResponse;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class EconomyOperationsTest {

    private AccountRegistry registry;
    private EconomyConfigSnapshot config;
    private List<TransactionEntry> logged;
    private List<Event> dispatchedEvents;
    private EconomyOperations ops;

    private UUID aliceId;
    private UUID bobId;

    @BeforeEach
    void setUp() {
        registry = new AccountRegistry();
        logged = new ArrayList<>();
        dispatchedEvents = new ArrayList<>();

        aliceId = UUID.randomUUID();
        bobId = UUID.randomUUID();

        config = configWith(0.0, 0, null, 2);

        registry.create(account(aliceId, "Alice", new BigDecimal("10.00")));
        registry.create(account(bobId, "Bob", new BigDecimal("5.00")));

        ops = buildOps(event -> { });
    }

    // ── deposit ──────────────────────────────────────────────────────────────

    @Test
    void deposit_updatesBalanceAndLogsTransaction() {
        EconomyResponse resp = ops.deposit(aliceId, new BigDecimal("3.50"));

        assertTrue(resp.transactionSuccess());
        assertEquals(0, new BigDecimal("13.50").compareTo(registry.getLiveRecord(aliceId).getBalance()));
        assertEquals(1, logged.size());
        assertEquals(TransactionType.GIVE, logged.getFirst().getType());
        assertEquals(0, new BigDecimal("3.50").compareTo(logged.getFirst().getAmount()));
        assertEquals(List.of(BalanceChangeEvent.class, BalanceChangedEvent.class),
            dispatchedEvents.stream().map(Event::getClass).toList());
    }

    @Test
    void deposit_cancelledByEvent_doesNotMutateBalance() {
        ops = buildOps(event -> {
            if (event instanceof BalanceChangeEvent e) e.setCancelled(true);
        });

        EconomyResponse resp = ops.deposit(aliceId, new BigDecimal("3.00"));

        assertFalse(resp.transactionSuccess());
        assertEquals(0, new BigDecimal("10.00").compareTo(registry.getLiveRecord(aliceId).getBalance()));
        assertTrue(logged.isEmpty());
        assertEquals(List.of(BalanceChangeEvent.class), dispatchedEvents.stream().map(Event::getClass).toList());
    }

    @Test
    void deposit_exceedsMaxBalance_returnsFailure() {
        config = configWith(0.0, 0, new BigDecimal("12.00"), 2);
        ops = buildOps(event -> { });

        EconomyResponse resp = ops.deposit(aliceId, new BigDecimal("5.00"));

        assertFalse(resp.transactionSuccess());
        assertEquals(0, new BigDecimal("10.00").compareTo(registry.getLiveRecord(aliceId).getBalance()));
        assertTrue(logged.isEmpty());
    }

    @Test
    void deposit_nonPositiveAmount_returnsFailure() {
        EconomyResponse resp = ops.deposit(aliceId, BigDecimal.ZERO);

        assertFalse(resp.transactionSuccess());
        assertTrue(logged.isEmpty());
    }

    @Test
    void deposit_unknownAccount_returnsFailure() {
        EconomyResponse resp = ops.deposit(UUID.randomUUID(), new BigDecimal("1.00"));

        assertFalse(resp.transactionSuccess());
        assertTrue(logged.isEmpty());
    }

    @Test
    void deposit_listenerMayMutateSameAccountBeforeApply() {
        AtomicBoolean nested = new AtomicBoolean();
        ops = buildOps(event -> {
            if (event instanceof BalanceChangeEvent balanceEvent
                    && balanceEvent.getReason() == BalanceChangeEvent.Reason.GIVE
                    && nested.compareAndSet(false, true)) {
                EconomyResponse nestedResp = ops.withdraw(aliceId, new BigDecimal("1.00"));
                assertTrue(nestedResp.transactionSuccess());
            }
        });

        EconomyResponse resp = ops.deposit(aliceId, new BigDecimal("3.00"));

        assertTrue(resp.transactionSuccess());
        assertEquals(0, new BigDecimal("12.00").compareTo(registry.getLiveRecord(aliceId).getBalance()));
    }

    @Test
    void deposit_frozenByListenerBeforeCommit_isRejected() {
        ops = buildOps(event -> {
            if (event instanceof BalanceChangeEvent balanceEvent
                    && balanceEvent.getReason() == BalanceChangeEvent.Reason.GIVE) {
                AccountRecord record = registry.getLiveRecord(aliceId);
                synchronized (record) {
                    record.setFrozen(true);
                }
            }
        });

        EconomyResponse resp = ops.deposit(aliceId, new BigDecimal("3.00"));

        assertFalse(resp.transactionSuccess());
        assertEquals("Account is frozen", resp.errorMessage);
        assertEquals(0, new BigDecimal("10.00").compareTo(registry.getLiveRecord(aliceId).getBalance()));
        assertTrue(logged.isEmpty());
        assertEquals(List.of(BalanceChangeEvent.class), dispatchedEvents.stream().map(Event::getClass).toList());
    }

    // ── withdraw ─────────────────────────────────────────────────────────────

    @Test
    void withdraw_updatesBalanceAndLogsTransaction() {
        EconomyResponse resp = ops.withdraw(aliceId, new BigDecimal("4.00"));

        assertTrue(resp.transactionSuccess());
        assertEquals(0, new BigDecimal("6.00").compareTo(registry.getLiveRecord(aliceId).getBalance()));
        assertEquals(1, logged.size());
        assertEquals(TransactionType.TAKE, logged.getFirst().getType());
    }

    @Test
    void withdraw_cancelledByEvent_doesNotMutateBalance() {
        ops = buildOps(event -> {
            if (event instanceof BalanceChangeEvent e) e.setCancelled(true);
        });

        EconomyResponse resp = ops.withdraw(aliceId, new BigDecimal("4.00"));

        assertFalse(resp.transactionSuccess());
        assertEquals(0, new BigDecimal("10.00").compareTo(registry.getLiveRecord(aliceId).getBalance()));
        assertTrue(logged.isEmpty());
    }

    @Test
    void withdraw_insufficientFunds_returnsFailure() {
        EconomyResponse resp = ops.withdraw(aliceId, new BigDecimal("99.00"));

        assertFalse(resp.transactionSuccess());
        assertEquals(0, new BigDecimal("10.00").compareTo(registry.getLiveRecord(aliceId).getBalance()));
        assertTrue(logged.isEmpty());
    }

    @Test
    void withdraw_frozenByListenerBeforeCommit_isRejected() {
        ops = buildOps(event -> {
            if (event instanceof BalanceChangeEvent balanceEvent
                    && balanceEvent.getReason() == BalanceChangeEvent.Reason.TAKE) {
                AccountRecord record = registry.getLiveRecord(aliceId);
                synchronized (record) {
                    record.setFrozen(true);
                }
            }
        });

        EconomyResponse resp = ops.withdraw(aliceId, new BigDecimal("4.00"));

        assertFalse(resp.transactionSuccess());
        assertEquals("Account is frozen", resp.errorMessage);
        assertEquals(0, new BigDecimal("10.00").compareTo(registry.getLiveRecord(aliceId).getBalance()));
        assertTrue(logged.isEmpty());
        assertEquals(List.of(BalanceChangeEvent.class), dispatchedEvents.stream().map(Event::getClass).toList());
    }

    // ── set ───────────────────────────────────────────────────────────────────

    @Test
    void set_updatesBalanceToExactValue() {
        EconomyResponse resp = ops.set(aliceId, new BigDecimal("7.00"));

        assertTrue(resp.transactionSuccess());
        assertEquals(0, new BigDecimal("7.00").compareTo(registry.getLiveRecord(aliceId).getBalance()));
        assertEquals(1, logged.size());
        assertEquals(TransactionType.SET, logged.getFirst().getType());
    }

    @Test
    void set_cancelledByEvent_doesNotMutateBalance() {
        ops = buildOps(event -> {
            if (event instanceof BalanceChangeEvent e) e.setCancelled(true);
        });

        EconomyResponse resp = ops.set(aliceId, new BigDecimal("7.00"));

        assertFalse(resp.transactionSuccess());
        assertEquals(0, new BigDecimal("10.00").compareTo(registry.getLiveRecord(aliceId).getBalance()));
        assertTrue(logged.isEmpty());
    }

    @Test
    void set_exceedsMaxBalance_returnsFailure() {
        config = configWith(0.0, 0, new BigDecimal("5.00"), 2);
        ops = buildOps(event -> { });

        EconomyResponse resp = ops.set(aliceId, new BigDecimal("6.00"));

        assertFalse(resp.transactionSuccess());
        assertEquals(0, new BigDecimal("10.00").compareTo(registry.getLiveRecord(aliceId).getBalance()));
    }

    @Test
    void set_negativeAmount_returnsFailure() {
        EconomyResponse resp = ops.set(aliceId, new BigDecimal("-1.00"));

        assertFalse(resp.transactionSuccess());
        assertTrue(logged.isEmpty());
    }

    @Test
    void set_zeroIsAllowed() {
        EconomyResponse resp = ops.set(aliceId, BigDecimal.ZERO);

        assertTrue(resp.transactionSuccess());
        assertEquals(0, BigDecimal.ZERO.compareTo(registry.getLiveRecord(aliceId).getBalance()));
    }

    // ── reset ─────────────────────────────────────────────────────────────────

    @Test
    void reset_restoresStartingBalance() {
        EconomyResponse resp = ops.reset(aliceId);

        assertTrue(resp.transactionSuccess());
        // starting balance in our test config is 5.00
        assertEquals(0, new BigDecimal("5.00").compareTo(registry.getLiveRecord(aliceId).getBalance()));
        assertEquals(1, logged.size());
        assertEquals(TransactionType.RESET, logged.getFirst().getType());
    }

    @Test
    void reset_cancelledByEvent_doesNotMutateBalance() {
        ops = buildOps(event -> {
            if (event instanceof BalanceChangeEvent e) e.setCancelled(true);
        });

        EconomyResponse resp = ops.reset(aliceId);

        assertFalse(resp.transactionSuccess());
        assertEquals(0, new BigDecimal("10.00").compareTo(registry.getLiveRecord(aliceId).getBalance()));
        assertTrue(logged.isEmpty());
    }

    // ── pay ───────────────────────────────────────────────────────────────────

    @Test
    void pay_successTransfersExactAmounts() {
        PayResult result = ops.pay(aliceId, bobId, new BigDecimal("4.00"));

        assertTrue(result.isSuccess());
        assertEquals(0, new BigDecimal("4.00").compareTo(result.getSent()));
        assertEquals(0, new BigDecimal("4.00").compareTo(result.getReceived()));
        assertEquals(0, BigDecimal.ZERO.compareTo(result.getTax()));
        assertEquals(0, new BigDecimal("6.00").compareTo(registry.getLiveRecord(aliceId).getBalance()));
        assertEquals(0, new BigDecimal("9.00").compareTo(registry.getLiveRecord(bobId).getBalance()));
        // Two transaction entries: PAY_SENT and PAY_RECEIVED
        assertEquals(2, logged.size());
        assertTrue(logged.stream().anyMatch(e -> e.getType() == TransactionType.PAY_SENT));
        assertTrue(logged.stream().anyMatch(e -> e.getType() == TransactionType.PAY_RECEIVED));
        assertEquals(List.of(PayEvent.class, PayCompletedEvent.class, BalanceChangedEvent.class, BalanceChangedEvent.class),
            dispatchedEvents.stream().map(Event::getClass).toList());
    }

    @Test
    void pay_withTaxDeductsTaxFromReceived() {
        config = configWith(10.0, 0, null, 2); // 10% tax
        ops = buildOps(event -> { });

        PayResult result = ops.pay(aliceId, bobId, new BigDecimal("4.00"));

        assertTrue(result.isSuccess());
        assertEquals(0, new BigDecimal("4.00").compareTo(result.getSent()));
        assertEquals(0, new BigDecimal("0.40").compareTo(result.getTax()));
        assertEquals(0, new BigDecimal("3.60").compareTo(result.getReceived()));
        assertEquals(0, new BigDecimal("6.00").compareTo(registry.getLiveRecord(aliceId).getBalance()));
        assertEquals(0, new BigDecimal("8.60").compareTo(registry.getLiveRecord(bobId).getBalance()));
    }

    @Test
    void pay_cancelledByPayEvent_doesNotMutateBalances() {
        ops = buildOps(event -> {
            if (event instanceof PayEvent e) e.setCancelled(true);
        });

        PayResult result = ops.pay(aliceId, bobId, new BigDecimal("4.00"));

        assertEquals(PayResult.Status.CANCELLED, result.getStatus());
        assertEquals(0, new BigDecimal("10.00").compareTo(registry.getLiveRecord(aliceId).getBalance()));
        assertEquals(0, new BigDecimal("5.00").compareTo(registry.getLiveRecord(bobId).getBalance()));
        assertTrue(logged.isEmpty());
        assertEquals(List.of(PayEvent.class), dispatchedEvents.stream().map(Event::getClass).toList());
    }

    @Test
    void pay_listenerMayMutateSenderBeforeApply() {
        AtomicBoolean nested = new AtomicBoolean();
        ops = buildOps(event -> {
            if (event instanceof PayEvent && nested.compareAndSet(false, true)) {
                EconomyResponse nestedResp = ops.deposit(aliceId, new BigDecimal("2.00"));
                assertTrue(nestedResp.transactionSuccess());
            }
        });

        PayResult result = ops.pay(aliceId, bobId, new BigDecimal("4.00"));

        assertTrue(result.isSuccess());
        assertEquals(0, new BigDecimal("8.00").compareTo(registry.getLiveRecord(aliceId).getBalance()));
        assertEquals(0, new BigDecimal("9.00").compareTo(registry.getLiveRecord(bobId).getBalance()));
    }

    @Test
    void pay_frozenByListenerBeforeCommit_isRejected() {
        ops = buildOps(event -> {
            if (event instanceof PayEvent) {
                AccountRecord senderRecord = registry.getLiveRecord(aliceId);
                synchronized (senderRecord) {
                    senderRecord.setFrozen(true);
                }
            }
        });

        PayResult result = ops.pay(aliceId, bobId, new BigDecimal("4.00"));

        assertEquals(PayResult.Status.FROZEN, result.getStatus());
        assertEquals(0, new BigDecimal("10.00").compareTo(registry.getLiveRecord(aliceId).getBalance()));
        assertEquals(0, new BigDecimal("5.00").compareTo(registry.getLiveRecord(bobId).getBalance()));
        assertTrue(logged.isEmpty());
        assertEquals(List.of(PayEvent.class), dispatchedEvents.stream().map(Event::getClass).toList());
    }

    @Test
    void pay_balanceLimitPreventsRecipientFromExceeding() {
        config = configWith(0.0, 0, new BigDecimal("6.00"), 2);
        ops = buildOps(event -> { });

        // Bob has 5.00, max is 6.00 — receiving 4.00 would push him to 9.00
        PayResult result = ops.pay(aliceId, bobId, new BigDecimal("4.00"));

        assertEquals(PayResult.Status.BALANCE_LIMIT, result.getStatus());
        assertEquals(0, new BigDecimal("10.00").compareTo(registry.getLiveRecord(aliceId).getBalance()));
        assertEquals(0, new BigDecimal("5.00").compareTo(registry.getLiveRecord(bobId).getBalance()));
        assertTrue(logged.isEmpty());
    }

    @Test
    void pay_cooldownBlocksPayBeforeCooldownExpires() {
        config = configWith(0.0, 60, null, 2); // 60 second cooldown
        ConcurrentHashMap<UUID, Long> cooldownMap = new ConcurrentHashMap<>();
        cooldownMap.put(aliceId, System.currentTimeMillis()); // just paid now
        ops = new EconomyOperations(registry, () -> config, cooldownMap, logged::add, event -> { });

        PayResult result = ops.pay(aliceId, bobId, new BigDecimal("1.00"));

        assertEquals(PayResult.Status.COOLDOWN, result.getStatus());
        assertTrue(result.getCooldownRemainingMs() > 0);
        assertTrue(logged.isEmpty());
    }

    @Test
    void pay_tooLowAmountIsRejected() {
        config = configWith(0.0, 0, null, 2, 0.10);
        ops = buildOps(event -> { });

        PayResult result = ops.pay(aliceId, bobId, new BigDecimal("0.05"));

        assertEquals(PayResult.Status.TOO_LOW, result.getStatus());
        assertEquals(0, BigDecimal.ZERO.compareTo(result.getSent()));
        assertEquals(0, new BigDecimal("0.10").compareTo(result.getMinimumAmount()));
        assertTrue(logged.isEmpty());
    }

    @Test
    void pay_amountRoundedToZeroIsInvalidWhenMinimumDisabled() {
        config = configWith(0.0, 0, null, 2, 0.0);
        ops = buildOps(event -> { });

        PayResult result = ops.pay(aliceId, bobId, new BigDecimal("0.001"));

        assertEquals(PayResult.Status.INVALID_AMOUNT, result.getStatus());
        assertTrue(logged.isEmpty());
    }

    @Test
    void pay_nonPositiveAmountIsInvalidAmount() {
        PayResult result = ops.pay(aliceId, bobId, BigDecimal.ZERO);

        assertEquals(PayResult.Status.INVALID_AMOUNT, result.getStatus());
    }

    @Test
    void pay_selfTransferIsRejected() {
        PayResult result = ops.pay(aliceId, aliceId, new BigDecimal("1.00"));

        assertEquals(PayResult.Status.SELF_TRANSFER, result.getStatus());
        assertEquals(0, new BigDecimal("10.00").compareTo(registry.getLiveRecord(aliceId).getBalance()));
        assertTrue(logged.isEmpty());
    }

    @Test
    void pay_insufficientFundsReturnFailure() {
        PayResult result = ops.pay(aliceId, bobId, new BigDecimal("100.00"));

        assertEquals(PayResult.Status.INSUFFICIENT_FUNDS, result.getStatus());
        assertEquals(0, new BigDecimal("10.00").compareTo(registry.getLiveRecord(aliceId).getBalance()));
        assertEquals(0, new BigDecimal("5.00").compareTo(registry.getLiveRecord(bobId).getBalance()));
        assertTrue(logged.isEmpty());
    }

    @Test
    void pay_unknownRecipient_returnsAccountNotFound() {
        PayResult result = ops.pay(aliceId, UUID.randomUUID(), new BigDecimal("1.00"));

        assertEquals(PayResult.Status.ACCOUNT_NOT_FOUND, result.getStatus());
        assertTrue(logged.isEmpty());
    }

    @Test
    void previewTransfer_reportsAllowedAmountsWithoutMutatingState() {
        config = configWith(10.0, 0, null, 2);
        ops = buildOps(event -> { });

        TransferPreviewResult result = ops.previewTransfer(aliceId, bobId, new BigDecimal("4.00"));

        assertEquals(TransferPreviewResult.Status.ALLOWED, result.status());
        assertEquals(0, new BigDecimal("4.00").compareTo(result.sent()));
        assertEquals(0, new BigDecimal("0.40").compareTo(result.tax()));
        assertEquals(0, new BigDecimal("3.60").compareTo(result.received()));
        assertFalse(result.hasMinimumAmount());
        assertNull(result.minimumAmount());
        assertEquals(0, new BigDecimal("10.00").compareTo(registry.getLiveRecord(aliceId).getBalance()));
        assertEquals(0, new BigDecimal("5.00").compareTo(registry.getLiveRecord(bobId).getBalance()));
        assertTrue(logged.isEmpty());
    }

    @Test
    void previewTransfer_reportsCooldownBeforeMutation() {
        config = configWith(0.0, 60, null, 2);
        ConcurrentHashMap<UUID, Long> cooldownMap = new ConcurrentHashMap<>();
        cooldownMap.put(aliceId, System.currentTimeMillis());
        ops = new EconomyOperations(registry, () -> config, cooldownMap, logged::add, event -> { });

        TransferPreviewResult result = ops.previewTransfer(aliceId, bobId, new BigDecimal("1.00"));

        assertEquals(TransferPreviewResult.Status.COOLDOWN, result.status());
        assertTrue(result.cooldownRemainingMs() > 0);
        assertEquals(0, new BigDecimal("1.00").compareTo(result.sent()));
        assertFalse(result.hasMinimumAmount());
        assertNull(result.minimumAmount());
        assertTrue(logged.isEmpty());
    }

    @Test
    void previewTransfer_reportsMinimumAmountWhenTooLow() {
        config = configWith(0.0, 0, null, 2, 0.10);
        ops = buildOps(event -> { });

        TransferPreviewResult result = ops.previewTransfer(aliceId, bobId, new BigDecimal("0.05"));

        assertEquals(TransferPreviewResult.Status.TOO_LOW, result.status());
        assertTrue(result.hasMinimumAmount());
        assertEquals(0, new BigDecimal("0.10").compareTo(result.minimumAmount()));
        assertTrue(logged.isEmpty());
    }

    @Test
    void previewTransfer_amountRoundedToZeroIsInvalidWhenMinimumDisabled() {
        config = configWith(0.0, 0, null, 2, 0.0);
        ops = buildOps(event -> { });

        TransferPreviewResult result = ops.previewTransfer(aliceId, bobId, new BigDecimal("0.001"));

        assertEquals(TransferPreviewResult.Status.INVALID_AMOUNT, result.status());
        assertTrue(logged.isEmpty());
    }

    // ── canDeposit / canWithdraw ───────────────────────────────────────────────

    @Test
    void canDeposit_withinLimitSucceeds() {
        config = configWith(0.0, 0, new BigDecimal("15.00"), 2);
        ops = buildOps(event -> { });

        BalanceCheckResult resp = ops.canDeposit(aliceId, new BigDecimal("3.00"));

        assertTrue(resp.isAllowed());
        assertEquals(0, new BigDecimal("13.00").compareTo(resp.resultingBalance()));
    }

    @Test
    void canDeposit_wouldExceedMaxBalanceFails() {
        config = configWith(0.0, 0, new BigDecimal("12.00"), 2);
        ops = buildOps(event -> { });

        // Alice has 10.00, max 12.00, depositing 5.00 would give 15.00 > 12.00
        BalanceCheckResult resp = ops.canDeposit(aliceId, new BigDecimal("5.00"));

        assertFalse(resp.isAllowed());
    }

    @Test
    void canDeposit_amountRoundedToZeroFails() {
        BalanceCheckResult resp = ops.canDeposit(aliceId, new BigDecimal("0.001"));

        assertFalse(resp.isAllowed());
        assertEquals(BalanceCheckResult.Status.INVALID_AMOUNT, resp.status());
    }

    @Test
    void canWithdraw_sufficientFundsSucceeds() {
        BalanceCheckResult resp = ops.canWithdraw(aliceId, new BigDecimal("8.00"));

        assertTrue(resp.isAllowed());
        assertEquals(0, new BigDecimal("2.00").compareTo(resp.resultingBalance()));
    }

    @Test
    void canWithdraw_insufficientFundsFails() {
        BalanceCheckResult resp = ops.canWithdraw(aliceId, new BigDecimal("15.00"));

        assertFalse(resp.isAllowed());
    }

    @Test
    void canWithdraw_amountRoundedToZeroFails() {
        BalanceCheckResult resp = ops.canWithdraw(aliceId, new BigDecimal("0.001"));

        assertFalse(resp.isAllowed());
        assertEquals(BalanceCheckResult.Status.INVALID_AMOUNT, resp.status());
    }

    @Test
    void deposit_amountRoundedToZeroFailsWithoutMutation() {
        EconomyResponse resp = ops.deposit(aliceId, new BigDecimal("0.001"));

        assertFalse(resp.transactionSuccess());
        assertEquals(0, new BigDecimal("10.00").compareTo(registry.getLiveRecord(aliceId).getBalance()));
        assertTrue(logged.isEmpty());
    }

    @Test
    void withdraw_amountRoundedToZeroFailsWithoutMutation() {
        EconomyResponse resp = ops.withdraw(aliceId, new BigDecimal("0.001"));

        assertFalse(resp.transactionSuccess());
        assertEquals(0, new BigDecimal("10.00").compareTo(registry.getLiveRecord(aliceId).getBalance()));
        assertTrue(logged.isEmpty());
    }

    @Test
    void canTransfer_amountRoundedToZeroIsInvalidWhenMinimumDisabled() {
        config = configWith(0.0, 0, null, 2, 0.0);
        ops = buildOps(event -> { });

        TransferCheckResult result = ops.canTransfer(aliceId, bobId, new BigDecimal("0.001"));

        assertEquals(TransferCheckResult.Status.INVALID_AMOUNT, result.status());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private EconomyOperations buildOps(Consumer<Event> listener) {
        return new EconomyOperations(registry, () -> config, new ConcurrentHashMap<>(),
                logged::add, event -> {
                    dispatchedEvents.add(event);
                    listener.accept(event);
                });
    }

    private AccountRecord account(UUID id, String name, BigDecimal balance) {
        return new AccountRecord(id, name, config.currencyId(), Map.of(config.currencyId(), balance), 1L, 1L);
    }

    private static EconomyConfigSnapshot configWith(double taxPercent, long cooldownSec,
                                                     BigDecimal maxBalance, int decimals) {
        return configWith(taxPercent, cooldownSec, maxBalance, decimals, 0.01);
    }

    private static EconomyConfigSnapshot configWith(double taxPercent, long cooldownSec,
                                                     BigDecimal maxBalance, int decimals,
                                                     double minAmount) {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("currency.id", "test");
        cfg.set("currency.name-singular", "Dollar");
        cfg.set("currency.name-plural", "Dollars");
        cfg.set("currency.decimal-digits", decimals);
        cfg.set("currency.starting-balance", 5.00);
        cfg.set("currency.max-balance", maxBalance != null ? maxBalance.doubleValue() : -1);
        cfg.set("pay.cooldown-seconds", cooldownSec);
        cfg.set("pay.tax-percent", taxPercent);
        cfg.set("pay.min-amount", minAmount);
        cfg.set("baltop.cache-ttl-seconds", 30);
        cfg.set("history.retention-days", -1);
        return EconomyConfigSnapshot.from(cfg);
    }
}
