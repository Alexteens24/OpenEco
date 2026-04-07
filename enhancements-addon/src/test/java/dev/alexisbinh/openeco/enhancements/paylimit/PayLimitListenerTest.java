package dev.alexisbinh.openeco.enhancements.paylimit;

import dev.alexisbinh.openeco.api.CurrencyInfo;
import dev.alexisbinh.openeco.api.EconomyRulesSnapshot;
import dev.alexisbinh.openeco.api.OpenEcoApi;
import dev.alexisbinh.openeco.event.PayCompletedEvent;
import dev.alexisbinh.openeco.event.PayEvent;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PayLimitListenerTest {

    @Mock
    private OpenEcoApi api;

    @Mock
    private JavaPlugin plugin;

    @Mock
    private Server server;

    @Mock
    private Player sender;

    private YamlConfiguration config;
    private PayLimitListener listener;
    private UUID senderId;
    private UUID recipientId;

    @BeforeEach
    void setUp() {
        config = new YamlConfiguration();
        config.set("pay-limit.enabled", true);
        config.set("pay-limit.max-amount", 10.0);
        config.set("pay-limit.window-seconds", 86400);
        config.set("pay-limit.message", "<red>limit <limit> <remaining>");

        senderId = UUID.randomUUID();
        recipientId = UUID.randomUUID();

        when(plugin.getConfig()).thenReturn(config);
        when(api.getRules()).thenReturn(rulesWith(2, null));

        listener = new PayLimitListener(api, plugin);
    }

    @Test
    void quotaOnlyAdvancesAfterConfirmedPay() {
        stubSenderChecks();

        PayEvent firstAttempt = payEvent(new BigDecimal("8.00"));

        listener.onPay(firstAttempt);

        assertFalse(firstAttempt.isCancelled());

        PayEvent secondAttempt = payEvent(new BigDecimal("4.00"));

        listener.onPay(secondAttempt);

        assertFalse(secondAttempt.isCancelled());

        listener.onPayCompleted(new PayCompletedEvent(
                senderId,
                recipientId,
                new BigDecimal("8.00"),
                new BigDecimal("8.00"),
                BigDecimal.ZERO,
                new BigDecimal("50.00"),
                new BigDecimal("42.00"),
                new BigDecimal("10.00"),
                new BigDecimal("18.00")));

        PayEvent blockedAttempt = payEvent(new BigDecimal("4.00"));

        listener.onPay(blockedAttempt);

        assertTrue(blockedAttempt.isCancelled());
    }

    @Test
    void quotaRespectsConfiguredFractionalDigits() {
        config.set("pay-limit.max-amount", 0.555);
        stubSenderChecks();
        when(api.getRules()).thenReturn(rulesWith(3, null));

        listener.onPayCompleted(new PayCompletedEvent(
                senderId,
                recipientId,
                new BigDecimal("0.555"),
                new BigDecimal("0.555"),
                BigDecimal.ZERO,
                new BigDecimal("1.000"),
                new BigDecimal("0.445"),
                BigDecimal.ZERO,
                new BigDecimal("0.555")));

        PayEvent blockedAttempt = payEvent(new BigDecimal("0.001"));

        listener.onPay(blockedAttempt);

        assertTrue(blockedAttempt.isCancelled());
    }

    @Test
    void unknownEventCurrencyFallsBackToDefaultRuleScale() {
        stubSenderChecks();
        when(api.getCurrencyInfo(anyString())).thenReturn(null);

        listener.onPayCompleted(new PayCompletedEvent(
                senderId,
                recipientId,
                new BigDecimal("10.00"),
                new BigDecimal("10.00"),
                BigDecimal.ZERO,
                new BigDecimal("50.00"),
                new BigDecimal("40.00"),
                BigDecimal.ZERO,
                new BigDecimal("10.00"),
                "bogus"));

        PayEvent blockedAttempt = payEvent(new BigDecimal("1.00"), "bogus");

        listener.onPay(blockedAttempt);

        assertTrue(blockedAttempt.isCancelled());
    }

    @Test
    void expiredUsageWindowsArePruned() {
        config.set("pay-limit.window-seconds", 1);

        listener.onPayCompleted(new PayCompletedEvent(
                senderId,
                recipientId,
                new BigDecimal("4.00"),
                new BigDecimal("4.00"),
                BigDecimal.ZERO,
                new BigDecimal("20.00"),
                new BigDecimal("16.00"),
                BigDecimal.ZERO,
                new BigDecimal("4.00")));

        assertEquals(1, listener.trackedWindowCount());

        listener.pruneExpiredEntries(System.currentTimeMillis() + 1_001L, 1_000L);

        assertEquals(0, listener.trackedWindowCount());
    }

    private PayEvent payEvent(BigDecimal amount) {
        return new PayEvent(senderId, recipientId, amount, BigDecimal.ZERO, amount);
    }

    private PayEvent payEvent(BigDecimal amount, String currencyId) {
        return new PayEvent(senderId, recipientId, amount, BigDecimal.ZERO, amount, currencyId);
    }

    private static EconomyRulesSnapshot rulesWith(int fractionalDigits, BigDecimal maxBalance) {
        return new EconomyRulesSnapshot(
                new CurrencyInfo("coins", "coin", "coins", fractionalDigits, BigDecimal.ZERO, maxBalance),
                0,
                BigDecimal.ZERO,
                null,
                0,
                0);
    }

    private void stubSenderChecks() {
        when(plugin.getServer()).thenReturn(server);
        when(server.getPlayer(senderId)).thenReturn(sender);
        when(sender.hasPermission("openeco.enhancements.bypass.paylimit")).thenReturn(false);
    }
}