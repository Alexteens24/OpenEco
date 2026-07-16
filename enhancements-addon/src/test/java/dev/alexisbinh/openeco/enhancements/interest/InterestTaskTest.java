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
import dev.alexisbinh.openeco.api.EconomyRulesSnapshot;
import dev.alexisbinh.openeco.api.OpenEcoApi;
import dev.alexisbinh.openeco.api.OpenEcoAsyncApi;
import dev.alexisbinh.openeco.api.ClusterJobCoordinator;
import dev.alexisbinh.openeco.api.TransactionKind;
import dev.alexisbinh.openeco.api.TransactionMetadata;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InterestTaskTest {

    @Mock
    private OpenEcoApi api;

    @Mock
    private JavaPlugin plugin;

    @Mock
    private Server server;

    @Mock
    private GlobalRegionScheduler globalRegionScheduler;

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
        when(plugin.getServer()).thenReturn(server);
        when(server.getGlobalRegionScheduler()).thenReturn(globalRegionScheduler);
        doAnswer(invocation -> {
            invocation.<java.util.function.Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask>>getArgument(1)
                .accept(null);
            return null;
        }).when(globalRegionScheduler).run(eq(plugin), any());
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

        verify(globalRegionScheduler).run(eq(plugin), any());
        verify(api).deposit(accountId, new BigDecimal("5.00"));
        verify(api, never()).logCustomTransaction(
            eq(accountId),
            any(BigDecimal.class),
            any(TransactionKind.class),
            any(TransactionMetadata.class));
    }

    @Test
    void multiWriterRetriesReuseDeterministicPerAccountOperationId() {
        OpenEcoAsyncApi asyncApi = org.mockito.Mockito.mock(OpenEcoAsyncApi.class);
        when(asyncApi.findAppliedDeposit(any(UUID.class)))
                .thenReturn(CompletableFuture.completedFuture(java.util.Optional.empty()));
        when(asyncApi.depositOnce(any(UUID.class), eq(accountId), eq("coins"), eq(new BigDecimal("5.00"))))
                .thenReturn(CompletableFuture.completedFuture(true));
        InterestTask multiWriterTask = new InterestTask(api, plugin, null, asyncApi);

        multiWriterTask.run();
        multiWriterTask.run();

        ArgumentCaptor<UUID> operationIds = ArgumentCaptor.forClass(UUID.class);
        verify(asyncApi, org.mockito.Mockito.times(2)).depositOnce(
                operationIds.capture(), eq(accountId), eq("coins"), eq(new BigDecimal("5.00")));
        org.junit.jupiter.api.Assertions.assertEquals(
                operationIds.getAllValues().get(0), operationIds.getAllValues().get(1));
        verify(api, never()).deposit(any(UUID.class), any(BigDecimal.class));
    }

    @Test
    void retryUsesOriginallyAppliedAmountAfterBalanceChanged() {
        OpenEcoAsyncApi asyncApi = org.mockito.Mockito.mock(OpenEcoAsyncApi.class);
        when(api.getBalance(accountId)).thenReturn(
                new BigDecimal("100.00"), new BigDecimal("105.00"));
        when(asyncApi.findAppliedDeposit(any(UUID.class))).thenReturn(
                CompletableFuture.completedFuture(java.util.Optional.empty()),
                CompletableFuture.completedFuture(java.util.Optional.of(
                        new OpenEcoAsyncApi.AppliedDeposit(accountId, "coins", new BigDecimal("5.00")))));
        when(asyncApi.depositOnce(any(UUID.class), eq(accountId), eq("coins"), eq(new BigDecimal("5.00"))))
                .thenReturn(CompletableFuture.completedFuture(true));
        InterestTask multiWriterTask = new InterestTask(api, plugin, null, asyncApi);

        multiWriterTask.run();
        multiWriterTask.run();

        verify(asyncApi, org.mockito.Mockito.times(2)).depositOnce(
                any(UUID.class), eq(accountId), eq("coins"), eq(new BigDecimal("5.00")));
    }

    @Test
    void failedAccountKeepsLeaseRetryableAndNextAttemptCompletes() {
        UUID retryAccountId = UUID.randomUUID();
        when(api.getUUIDNameMap()).thenReturn(Map.of(accountId, "Alice", retryAccountId, "Bob"));
        when(api.getBalance(retryAccountId)).thenReturn(new BigDecimal("100.00"));

        OpenEcoAsyncApi asyncApi = org.mockito.Mockito.mock(OpenEcoAsyncApi.class);
        when(asyncApi.findAppliedDeposit(any(UUID.class)))
                .thenReturn(CompletableFuture.completedFuture(java.util.Optional.empty()));
        when(asyncApi.depositOnce(any(UUID.class), eq(accountId), eq("coins"), eq(new BigDecimal("5.00"))))
                .thenReturn(CompletableFuture.completedFuture(true));
        AtomicInteger retryAttempts = new AtomicInteger();
        when(asyncApi.depositOnce(any(UUID.class), eq(retryAccountId), eq("coins"), eq(new BigDecimal("5.00"))))
                .thenAnswer(ignored -> retryAttempts.incrementAndGet() == 1
                        ? CompletableFuture.failedFuture(new IllegalStateException("temporary database failure"))
                        : CompletableFuture.completedFuture(true));

        ClusterJobCoordinator coordinator = org.mockito.Mockito.mock(ClusterJobCoordinator.class);
        ClusterJobCoordinator.Lease firstLease = org.mockito.Mockito.mock(ClusterJobCoordinator.Lease.class);
        ClusterJobCoordinator.Lease retryLease = org.mockito.Mockito.mock(ClusterJobCoordinator.Lease.class);
        when(firstLease.renew()).thenReturn(true);
        when(retryLease.renew()).thenReturn(true);
        when(coordinator.tryAcquire(eq("interest"), any(String.class), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(java.util.Optional.of(firstLease), java.util.Optional.of(retryLease));
        InterestTask multiWriterTask = new InterestTask(api, plugin, coordinator, asyncApi);

        multiWriterTask.run();
        verify(firstLease, never()).complete();

        multiWriterTask.run();
        verify(retryLease).complete();
        verify(asyncApi, org.mockito.Mockito.times(2)).depositOnce(
                any(UUID.class), eq(retryAccountId), eq("coins"), eq(new BigDecimal("5.00")));
    }

    @Test
    void leaseIsNotRenewedForEveryAccountBeforeHeartbeatIsDue() {
        Map<UUID, String> accounts = IntStream.range(0, 1_000).boxed().collect(
                java.util.stream.Collectors.toMap(ignored -> UUID.randomUUID(), index -> "Player" + index));
        when(api.getUUIDNameMap()).thenReturn(accounts);
        when(api.getBalance(any(UUID.class))).thenReturn(new BigDecimal("100.00"));

        OpenEcoAsyncApi asyncApi = org.mockito.Mockito.mock(OpenEcoAsyncApi.class);
        when(asyncApi.findAppliedDeposit(any(UUID.class)))
                .thenReturn(CompletableFuture.completedFuture(java.util.Optional.empty()));
        when(asyncApi.depositOnce(any(UUID.class), any(UUID.class), eq("coins"), eq(new BigDecimal("5.00"))))
                .thenReturn(CompletableFuture.completedFuture(true));
        ClusterJobCoordinator coordinator = org.mockito.Mockito.mock(ClusterJobCoordinator.class);
        ClusterJobCoordinator.Lease lease = org.mockito.Mockito.mock(ClusterJobCoordinator.Lease.class);
        when(coordinator.tryAcquire(eq("interest"), any(String.class), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(java.util.Optional.of(lease));

        new InterestTask(api, plugin, coordinator, asyncApi, () -> 1L).run();

        verify(lease, never()).renew();
        verify(lease).complete();
    }
}
