package dev.alexisbinh.openeco.enhancements;

import dev.alexisbinh.openeco.api.CurrencyInfo;
import dev.alexisbinh.openeco.api.OpenEcoApi;
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
class OpenEcoEnhancementsPluginTest {

    @Mock
    private OpenEcoApi api;

    @Mock
    private Logger logger;

    @Test
    void warnsWhenPermCapTierExceedsGlobalLimit() {
        when(api.getCurrencies()).thenReturn(List.of(
                new CurrencyInfo("coins", "coin", "coins", 2, BigDecimal.ZERO, new BigDecimal("100.00"))));

        OpenEcoEnhancementsPlugin.warnIfPermCapExceedsGlobalLimit(
                List.of(Map.of("permission", "openeco.cap.mvp", "cap", 250.0)),
                api,
                logger);

        verify(logger).warning("perm-cap tier 'openeco.cap.mvp' configures 250.00 above OpenEco max-balance 100.00 for currency 'coins'; core will still enforce the currency limit.");
    }

    @Test
    void doesNotWarnWhenGlobalLimitIsUnlimited() {
        when(api.getCurrencies()).thenReturn(List.of(
                new CurrencyInfo("coins", "coin", "coins", 2, BigDecimal.ZERO, null)));

        OpenEcoEnhancementsPlugin.warnIfPermCapExceedsGlobalLimit(
                List.of(Map.of("permission", "openeco.cap.mvp", "cap", 250.0)),
                api,
                logger);

        verify(logger, never()).warning(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void warnsPerCurrencyWhenTierExceedsOnlySpecificCurrencyLimit() {
        when(api.getCurrencies()).thenReturn(List.of(
                new CurrencyInfo("coins", "coin", "coins", 2, BigDecimal.ZERO, new BigDecimal("500.00")),
                new CurrencyInfo("gems", "gem", "gems", 0, BigDecimal.ZERO, new BigDecimal("50"))));

        OpenEcoEnhancementsPlugin.warnIfPermCapExceedsGlobalLimit(
                List.of(Map.of("permission", "openeco.cap.vip", "cap", 60.0)),
                api,
                logger);

        verify(logger).warning("perm-cap tier 'openeco.cap.vip' configures 60 above OpenEco max-balance 50 for currency 'gems'; core will still enforce the currency limit.");
        verify(logger, never()).warning("perm-cap tier 'openeco.cap.vip' configures 60.00 above OpenEco max-balance 500.00 for currency 'coins'; core will still enforce the currency limit.");
    }
}