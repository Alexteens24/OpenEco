package dev.alexisbinh.simpleeco.service;

import dev.alexisbinh.simpleeco.event.BalanceChangeEvent;
import dev.alexisbinh.simpleeco.event.PayEvent;
import dev.alexisbinh.simpleeco.model.AccountRecord;
import dev.alexisbinh.simpleeco.model.PayResult;
import dev.alexisbinh.simpleeco.model.TransactionEntry;
import dev.alexisbinh.simpleeco.model.TransactionType;
import net.milkbowl.vault2.economy.EconomyResponse;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

class EconomyOperationsTest {

    private AccountRegistry registry;
    private EconomyConfigSnapshot config;
    private List<TransactionEntry> logged;
    private int leaderboardInvalidations;
    private EconomyOperations ops;

    private UUID aliceId;
    private UUID bobId;

    @BeforeEach
    void setUp() {
        registry = new AccountRegistry();
        logged = new ArrayList<>();
        leaderboardInvalidations = 0;

        aliceId = UUID.randomUUID();
        bobId = UUID.randomUUID();

        registry.create(new AccountRecord(aliceId, "Alice", new BigDecimal("10.00"), 1L, 1L));
        registry.create(new AccountRecord(bobId, "Bob", new BigDecimal("5.00"), 1L, 1L));

        config = configWith(0.0, 0, null, 2);
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
        assertEquals(1, leaderboardInvalidations);
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
        assertEquals(0, leaderboardInvalidations);
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

    // ── withdraw ─────────────────────────────────────────────────────────────

    @Test
    void withdraw_updatesBalanceAndLogsTransaction() {
        EconomyResponse resp = ops.withdraw(aliceId, new BigDecimal("4.00"));

        assertTrue(resp.transactionSuccess());
        assertEquals(0, new BigDecimal("6.00").compareTo(registry.getLiveRecord(aliceId).getBalance()));
        assertEquals(1, logged.size());
        assertEquals(TransactionType.TAKE, logged.getFirst().getType());
        assertEquals(1, leaderboardInvalidations);
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
        ops = new EconomyOperations(registry, () -> config, cooldownMap, logged::add,
                () -> leaderboardInvalidations++, event -> { });

        PayResult result = ops.pay(aliceId, bobId, new BigDecimal("1.00"));

        assertEquals(PayResult.Status.COOLDOWN, result.getStatus());
        assertTrue(result.getCooldownRemainingMs() > 0);
        assertTrue(logged.isEmpty());
    }

    @Test
    void pay_tooLowAmountIsRejected() {
        config = configWith(0.0, 0, null, 2);
        // Default min-amount is 0.01; paying 0.001 (scales to 0.00) should be rejected
        ops = buildOps(event -> { });

        PayResult result = ops.pay(aliceId, bobId, new BigDecimal("0.001"));

        assertEquals(PayResult.Status.TOO_LOW, result.getStatus());
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

    // ── canDeposit / canWithdraw ───────────────────────────────────────────────

    @Test
    void canDeposit_withinLimitSucceeds() {
        config = configWith(0.0, 0, new BigDecimal("15.00"), 2);
        ops = buildOps(event -> { });

        EconomyResponse resp = ops.canDeposit(aliceId, new BigDecimal("3.00"));

        assertTrue(resp.transactionSuccess());
        assertEquals(0, new BigDecimal("13.00").compareTo(resp.balance));
    }

    @Test
    void canDeposit_wouldExceedMaxBalanceFails() {
        config = configWith(0.0, 0, new BigDecimal("12.00"), 2);
        ops = buildOps(event -> { });

        // Alice has 10.00, max 12.00, depositing 5.00 would give 15.00 > 12.00
        EconomyResponse resp = ops.canDeposit(aliceId, new BigDecimal("5.00"));

        assertFalse(resp.transactionSuccess());
    }

    @Test
    void canWithdraw_sufficientFundsSucceeds() {
        EconomyResponse resp = ops.canWithdraw(aliceId, new BigDecimal("8.00"));

        assertTrue(resp.transactionSuccess());
        assertEquals(0, new BigDecimal("2.00").compareTo(resp.balance));
    }

    @Test
    void canWithdraw_insufficientFundsFails() {
        EconomyResponse resp = ops.canWithdraw(aliceId, new BigDecimal("15.00"));

        assertFalse(resp.transactionSuccess());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private EconomyOperations buildOps(EventDispatcher dispatcher) {
        return new EconomyOperations(registry, () -> config, new ConcurrentHashMap<>(),
                logged::add, () -> leaderboardInvalidations++, dispatcher);
    }

    private static EconomyConfigSnapshot configWith(double taxPercent, long cooldownSec,
                                                     BigDecimal maxBalance, int decimals) {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("currency.id", "test");
        cfg.set("currency.name-singular", "Dollar");
        cfg.set("currency.name-plural", "Dollars");
        cfg.set("currency.decimal-digits", decimals);
        cfg.set("currency.starting-balance", 5.00);
        cfg.set("currency.max-balance", maxBalance != null ? maxBalance.doubleValue() : -1);
        cfg.set("pay.cooldown-seconds", cooldownSec);
        cfg.set("pay.tax-percent", taxPercent);
        cfg.set("pay.min-amount", 0.01);
        cfg.set("baltop.cache-ttl-seconds", 30);
        cfg.set("history.retention-days", -1);
        return EconomyConfigSnapshot.from(cfg);
    }
}
