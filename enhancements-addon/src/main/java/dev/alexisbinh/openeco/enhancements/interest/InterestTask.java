/*
 * Copyright 2026 alexisbinh
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.alexisbinh.openeco.enhancements.interest;

import dev.alexisbinh.openeco.api.BalanceChangeResult;
import dev.alexisbinh.openeco.api.CurrencyInfo;
import dev.alexisbinh.openeco.api.OpenEcoApi;
import dev.alexisbinh.openeco.api.OpenEcoAsyncApi;
import dev.alexisbinh.openeco.api.ClusterJobCoordinator;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/**
 * Iterates all accounts and credits interest based on the configured rate and interval.
 * Runs on Folia/Paper's async scheduler.
 */
public class InterestTask implements Runnable {

    private final OpenEcoApi api;
    private final JavaPlugin plugin;
    private final Logger log;
    private final ClusterJobCoordinator coordinator;
    private final OpenEcoAsyncApi asyncApi;
    private final LongSupplier nanoTime;

    public InterestTask(OpenEcoApi api, JavaPlugin plugin) {
        this(api, plugin, null, null);
    }

    public InterestTask(OpenEcoApi api, JavaPlugin plugin, ClusterJobCoordinator coordinator) {
        this(api, plugin, coordinator, null);
    }

    public InterestTask(OpenEcoApi api, JavaPlugin plugin, ClusterJobCoordinator coordinator,
                        OpenEcoAsyncApi asyncApi) {
        this(api, plugin, coordinator, asyncApi, System::nanoTime);
    }

    InterestTask(OpenEcoApi api, JavaPlugin plugin, ClusterJobCoordinator coordinator,
                 OpenEcoAsyncApi asyncApi, LongSupplier nanoTime) {
        this.api = api;
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.coordinator = coordinator;
        this.asyncApi = asyncApi;
        this.nanoTime = nanoTime;
    }

    @Override
    public void run() {
        long intervalSeconds = plugin.getConfig().getLong("interest.interval-seconds", 3600L);
        long intervalMs = Math.max(1_000L, intervalSeconds * 1_000L);
        long authoritativeNow = coordinator == null
                ? System.currentTimeMillis() : coordinator.currentTimeMillis();
        String runId = Long.toString(authoritativeNow / intervalMs);
        long leaseMs = retryIntervalMs(intervalMs);
        java.util.Optional<ClusterJobCoordinator.Lease> acquired = coordinator == null
                ? java.util.Optional.empty() : coordinator.tryAcquire("interest", runId,
                leaseMs);
        if (coordinator != null && acquired.isEmpty()) return;
        long leaseAcquiredAtNanos = nanoTime.getAsLong();
        if (asyncApi != null) {
            try {
                runInterestCycle(null, runId, acquired.orElse(null), leaseMs, leaseAcquiredAtNanos);
                acquired.ifPresent(ClusterJobCoordinator.Lease::complete);
            } catch (RuntimeException e) {
                log.warning("[Interest] Cycle failed; lease will expire for retry: " + e.getMessage());
            }
        } else {
            plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {
                runInterestCycle(task, runId, acquired.orElse(null), leaseMs, leaseAcquiredAtNanos);
                acquired.ifPresent(ClusterJobCoordinator.Lease::complete);
            });
        }
    }

    public static long retryIntervalMs(long intervalMs) {
        return Math.max(1_000L, Math.min(60_000L, Math.max(1L, intervalMs / 4L)));
    }

    private void runInterestCycle(ScheduledTask task, String runId,
                                  ClusterJobCoordinator.Lease lease, long leaseMs,
                                  long leaseAcquiredAtNanos) {
        FileConfiguration config = plugin.getConfig();
        double rate = config.getDouble("interest.rate", 5.0);
        long intervalSeconds = config.getLong("interest.interval-seconds", 3600);
        String configuredCurrencyId = config.getString("interest.currency");
        boolean explicitCurrency = configuredCurrencyId != null && !configuredCurrencyId.isBlank();
        String currencyId = explicitCurrency ? configuredCurrencyId : api.getRules().currency().id();
        CurrencyInfo currency = explicitCurrency ? api.getCurrencyInfo(currencyId) : api.getRules().currency();
        if (explicitCurrency && currency == null) {
            plugin.getLogger().warning("[Interest] Unknown configured currency '" + currencyId + "'; skipping interest cycle.");
            return;
        }
        int fractionalDigits = currency.fractionalDigits();
        BigDecimal minBalance = BigDecimal.valueOf(config.getDouble("interest.min-balance", 0))
            .setScale(fractionalDigits, RoundingMode.HALF_UP);
        BigDecimal maxPerInterval = BigDecimal.valueOf(config.getDouble("interest.max-per-interval", 0))
            .setScale(fractionalDigits, RoundingMode.HALF_UP);

        if (rate <= 0 || intervalSeconds <= 0) return;

        // Factor = rate% / 100 / (seconds per year / interval)
        double secondsPerYear = 365.25 * 24 * 3600;
        BigDecimal factor = BigDecimal.valueOf(rate / 100.0 / (secondsPerYear / intervalSeconds));

        int credited = 0;
        int skipped = 0;
        RuntimeException firstFailure = null;
        long renewIntervalNanos = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
                Math.max(1_000L, leaseMs / 3L));
        long nextLeaseRenewalNanos = leaseAcquiredAtNanos + renewIntervalNanos;

        Map<UUID, String> accounts = api.getUUIDNameMap();
        for (UUID id : accounts.keySet()) {
            long nowNanos = nanoTime.getAsLong();
            if (lease != null && nowNanos - nextLeaseRenewalNanos >= 0L) {
                if (!lease.renew()) {
                    throw new IllegalStateException("Cluster job lease ownership was lost");
                }
                nextLeaseRenewalNanos = nowNanos + renewIntervalNanos;
            }
            try {
                credited += processAccount(id, currencyId, explicitCurrency, factor,
                        minBalance, maxPerInterval, fractionalDigits, runId) ? 1 : 0;
            } catch (Exception e) {
                log.warning("[Interest] Error processing account " + id + ": " + e.getMessage());
                skipped++;
                if (firstFailure == null) {
                    firstFailure = e instanceof RuntimeException runtime
                            ? runtime : new IllegalStateException(e);
                }
            }
        }
        if (firstFailure != null) {
            log.warning("[Interest] Cycle incomplete — credited: " + credited
                    + ", errors: " + skipped + "; lease will remain retryable.");
            throw new IllegalStateException("Interest payout failed for " + skipped + " account(s)", firstFailure);
        }
        log.info("[Interest] Cycle complete — credited: " + credited + ", skipped: " + skipped);
    }

    private boolean processAccount(UUID id, String currencyId, boolean explicitCurrency, BigDecimal factor, BigDecimal minBalance,
                                   BigDecimal maxPerInterval, int fractionalDigits, String runId) {
        UUID operationId = asyncApi == null ? null : UUID.nameUUIDFromBytes(
                ("openeco:interest:" + runId + ':' + id + ':' + currencyId)
                        .getBytes(StandardCharsets.UTF_8));
        if (asyncApi != null) {
            java.util.Optional<OpenEcoAsyncApi.AppliedDeposit> previous =
                    asyncApi.findAppliedDeposit(operationId).toCompletableFuture().join();
            if (previous.isPresent()) {
                OpenEcoAsyncApi.AppliedDeposit applied = previous.get();
                if (!applied.accountId().equals(id)
                        || !applied.currencyId().equalsIgnoreCase(currencyId)) {
                    throw new IllegalStateException("Interest operation ID payload conflict");
                }
                boolean repeated = asyncApi.depositOnce(
                        operationId, id, currencyId, applied.amount()).toCompletableFuture().join();
                if (!repeated) throw new IllegalStateException("Idempotent interest retry was rejected");
                return true;
            }
        }
        BigDecimal balance = explicitCurrency ? api.getBalance(id, currencyId) : api.getBalance(id);
        if (balance.compareTo(minBalance) < 0) return false;

        BigDecimal interest = balance.multiply(factor)
                .setScale(fractionalDigits, RoundingMode.HALF_UP);
        if (interest.compareTo(BigDecimal.ZERO) <= 0) return false;

        if (maxPerInterval.compareTo(BigDecimal.ZERO) > 0
                && interest.compareTo(maxPerInterval) > 0) {
            interest = maxPerInterval;
        }

        if (asyncApi != null) {
            boolean applied = asyncApi.depositOnce(operationId, id, currencyId, interest)
                    .toCompletableFuture().join();
            if (!applied) throw new IllegalStateException("Idempotent interest deposit was rejected");
            return true;
        }
        BalanceChangeResult result = explicitCurrency
                ? api.deposit(id, currencyId, interest) : api.deposit(id, interest);
        return result.isSuccess();
    }
}
