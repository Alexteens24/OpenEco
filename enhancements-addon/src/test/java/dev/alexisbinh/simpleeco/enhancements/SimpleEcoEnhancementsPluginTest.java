package dev.alexisbinh.simpleeco.enhancements;

import dev.alexisbinh.simpleeco.api.CurrencyInfo;
import dev.alexisbinh.simpleeco.api.EconomyRulesSnapshot;
import dev.alexisbinh.simpleeco.api.SimpleEcoApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimpleEcoEnhancementsPluginTest {

    @Mock
    private SimpleEcoApi api;

    @Mock
    private Logger logger;

    @Test
    void warnsWhenPermCapTierExceedsGlobalLimit() {
        when(api.getRules()).thenReturn(new EconomyRulesSnapshot(
                new CurrencyInfo("coins", "coin", "coins", 2, BigDecimal.ZERO, new BigDecimal("100.00")),
                0,
                BigDecimal.ZERO,
                null,
                0,
                0));

        SimpleEcoEnhancementsPlugin.warnIfPermCapExceedsGlobalLimit(
                List.of(Map.of("permission", "simpleeco.cap.mvp", "cap", 250.0)),
                api,
                logger);

        verify(logger).warning("perm-cap tier 'simpleeco.cap.mvp' configures 250.00 above SimpleEco global max-balance 100.00; core will still enforce the global limit.");
    }

    @Test
    void doesNotWarnWhenGlobalLimitIsUnlimited() {
        when(api.getRules()).thenReturn(new EconomyRulesSnapshot(
                new CurrencyInfo("coins", "coin", "coins", 2, BigDecimal.ZERO, null),
                0,
                BigDecimal.ZERO,
                null,
                0,
                0));

        SimpleEcoEnhancementsPlugin.warnIfPermCapExceedsGlobalLimit(
                List.of(Map.of("permission", "simpleeco.cap.mvp", "cap", 250.0)),
                api,
                logger);

        verify(logger, never()).warning(org.mockito.ArgumentMatchers.anyString());
    }
}