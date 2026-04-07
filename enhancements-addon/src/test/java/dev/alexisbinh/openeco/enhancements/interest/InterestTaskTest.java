package dev.alexisbinh.openeco.enhancements.interest;

import dev.alexisbinh.openeco.api.BalanceChangeResult;
import dev.alexisbinh.openeco.api.CurrencyInfo;
import dev.alexisbinh.openeco.api.EconomyRulesSnapshot;
import dev.alexisbinh.openeco.api.OpenEcoApi;
import dev.alexisbinh.openeco.api.TransactionKind;
import dev.alexisbinh.openeco.api.TransactionMetadata;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InterestTaskTest {

    @Mock
    private OpenEcoApi api;

    @Mock
    private JavaPlugin plugin;

    private YamlConfiguration config;
    private UUID accountId;
    private InterestTask task;

    @BeforeEach
    void setUp() {
        config = new YamlConfiguration();
        config.set("interest.rate", 5.0);
        config.set("interest.interval-seconds", 31557600L);
        config.set("interest.min-balance", 0.0);
        config.set("interest.max-per-interval", 0.0);

        accountId = UUID.randomUUID();

        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("interest-test"));
        when(api.getRules()).thenReturn(new EconomyRulesSnapshot(
                new CurrencyInfo("coins", "coin", "coins", 2, BigDecimal.ZERO, null),
                0,
                BigDecimal.ZERO,
                null,
                0,
                0));
        when(api.getUUIDNameMap()).thenReturn(Map.of(accountId, "Alice"));
        when(api.getBalance(accountId)).thenReturn(new BigDecimal("100.00"));
        when(api.deposit(eq(accountId), eq(new BigDecimal("5.00"))))
                .thenReturn(new BalanceChangeResult(
                        BalanceChangeResult.Status.SUCCESS,
                        new BigDecimal("5.00"),
                        new BigDecimal("100.00"),
                        new BigDecimal("105.00")));

        task = new InterestTask(api, plugin);
    }

    @Test
    void successfulInterestDepositDoesNotWriteDuplicateCustomHistory() {
        task.run();

        verify(api).deposit(accountId, new BigDecimal("5.00"));
        verify(api, never()).logCustomTransaction(
            eq(accountId),
            any(BigDecimal.class),
            any(TransactionKind.class),
            any(TransactionMetadata.class));
    }
}