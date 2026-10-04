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

package dev.alexisbinh.openeco.service;

import dev.alexisbinh.openeco.api.ExchangeResult;
import dev.alexisbinh.openeco.event.BalanceChangeEvent;
import dev.alexisbinh.openeco.model.AccountRecord;
import dev.alexisbinh.openeco.model.TransactionEntry;
import dev.alexisbinh.openeco.model.TransactionType;
import dev.alexisbinh.openeco.storage.AccountRepository;
import dev.alexisbinh.openeco.storage.DatabaseDialect;
import dev.alexisbinh.openeco.storage.JdbcAccountRepository;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A currency conversion must move both balances together or not at all.
 *
 * <p>The previous implementation called {@code withdraw} and then {@code deposit}, and on failure
 * compensated with a second {@code deposit}. That compensation is itself a deposit: the same
 * listener that rejected the original could reject it again, and the withdrawn amount would be
 * gone for good. The veto tests below are the ones that used to lose money.
 */
class CurrencyConversionTest {

    @TempDir
    Path tempDir;

    @Test
    void convertsBothBalancesInOneCall() throws Exception {
        withService((service, repository) -> {
            UUID id = seed(service, new BigDecimal("100.00"), new BigDecimal("10.00"));

            ExchangeResult result = service.convertCurrency(id, "openeco", "gems",
                    new BigDecimal("40.00"), new BigDecimal("8.00"));

            assertTrue(result.isSuccess(), () -> "status=" + result.status());
            assertEquals(0, new BigDecimal("60.00").compareTo(service.getBalance(id, "openeco")));
            assertEquals(0, new BigDecimal("18.00").compareTo(service.getBalance(id, "gems")));
            assertEquals(0, new BigDecimal("60.00").compareTo(result.fromBalanceAfter()));
            assertEquals(0, new BigDecimal("18.00").compareTo(result.toBalanceAfter()));
        });
    }

    @Test
    void recordsBothLegsInHistory() throws Exception {
        UUID[] holder = new UUID[1];
        withService(event -> { },
                (service, repository) -> {
            UUID id = seed(service, new BigDecimal("100.00"), new BigDecimal("10.00"));
            holder[0] = id;
            service.convertCurrency(id, "openeco", "gems", new BigDecimal("40.00"), new BigDecimal("8.00"));
        }, (service, repository) -> {
            // The transaction batcher is asynchronous, so history is only complete once the
            // service has drained on shutdown. The 3-argument overload scopes history to the
            // default currency, so each leg has to be read with its own currency.
            List<TransactionType> debited = service.getTransactions(holder[0], "openeco", 1, 10)
                    .stream().map(TransactionEntry::getType).toList();
            List<TransactionType> credited = service.getTransactions(holder[0], "gems", 1, 10)
                    .stream().map(TransactionEntry::getType).toList();

            assertTrue(debited.contains(TransactionType.EXCHANGE_OUT), () -> "openeco=" + debited);
            assertTrue(credited.contains(TransactionType.EXCHANGE_IN), () -> "gems=" + credited);
        });
    }

    @Test
    void persistedThroughTheNormalFlushPath() throws Exception {
        withService((service, repository) -> {
            UUID id = seed(service, new BigDecimal("100.00"), new BigDecimal("10.00"));

            service.convertCurrency(id, "openeco", "gems", new BigDecimal("40.00"), new BigDecimal("8.00"));
            assertTrue(service.flushDirty());

            AccountRecord stored = repository.loadAccount(id).orElseThrow();
            assertEquals(0, new BigDecimal("60.00").compareTo(stored.getBalance("openeco")));
            assertEquals(0, new BigDecimal("18.00").compareTo(stored.getBalance("gems")));
        });
    }

    /**
     * The regression.
     *
     * <p>A listener that refuses to credit this account - a protection plugin reacting to the
     * account being flagged, say - used to destroy money. The old implementation withdrew, the
     * credit was refused, and the compensating deposit was a credit too, so it was refused as
     * well: the withdrawn amount was gone and only a log line recorded it.
     *
     * <p>Both legs are now offered before anything is written, so a veto leaves the account
     * exactly as it was.
     */
    @Test
    void aListenerRefusingEveryCreditLeavesBothBalancesIntact() throws Exception {
        List<BalanceChangeEvent> seen = new ArrayList<>();
        // Armed only after seeding, otherwise the setup deposits are refused too.
        boolean[] armed = { false };
        EventDispatcher dispatcher = event -> {
            if (armed[0] && event instanceof BalanceChangeEvent change) {
                seen.add(change);
                if (change.getNewBalance().compareTo(change.getOldBalance()) > 0) {
                    change.setCancelled(true);
                }
            }
        };

        withService(dispatcher, (service, repository) -> {
            UUID id = seed(service, new BigDecimal("100.00"), new BigDecimal("10.00"));
            armed[0] = true;

            ExchangeResult result = service.convertCurrency(id, "openeco", "gems",
                    new BigDecimal("40.00"), new BigDecimal("8.00"));

            // Assert the invariant before the status: the balance is what players care about,
            // and a failure here shows the amount that would have been destroyed.
            assertEquals(0, new BigDecimal("100.00").compareTo(service.getBalance(id, "openeco")),
                    "the debited amount must be intact when the credit is refused");
            assertEquals(0, new BigDecimal("10.00").compareTo(service.getBalance(id, "gems")));
            assertEquals(ExchangeResult.Status.CANCELLED, result.status());
            assertNull(result.fromBalanceAfter(), "a vetoed conversion reports no balances");
            assertNull(result.toBalanceAfter());
            assertTrue(seen.size() >= 2, "both legs should have been offered to listeners");
        });
    }

    @Test
    void aListenerRefusingEveryDebitLeavesBothBalancesIntact() throws Exception {
        boolean[] armed = { false };
        EventDispatcher dispatcher = event -> {
            if (armed[0] && event instanceof BalanceChangeEvent change
                    && change.getNewBalance().compareTo(change.getOldBalance()) < 0) {
                change.setCancelled(true);
            }
        };

        withService(dispatcher, (service, repository) -> {
            UUID id = seed(service, new BigDecimal("100.00"), new BigDecimal("10.00"));
            armed[0] = true;

            ExchangeResult result = service.convertCurrency(id, "openeco", "gems",
                    new BigDecimal("40.00"), new BigDecimal("8.00"));

            assertEquals(ExchangeResult.Status.CANCELLED, result.status());
            assertEquals(0, new BigDecimal("100.00").compareTo(service.getBalance(id, "openeco")));
            assertEquals(0, new BigDecimal("10.00").compareTo(service.getBalance(id, "gems")));
        });
    }

    @Test
    void rejectsInsufficientFundsWithoutDebiting() throws Exception {
        withService((service, repository) -> {
            UUID id = seed(service, new BigDecimal("5.00"), new BigDecimal("0.00"));

            ExchangeResult result = service.convertCurrency(id, "openeco", "gems",
                    new BigDecimal("40.00"), new BigDecimal("8.00"));

            assertEquals(ExchangeResult.Status.INSUFFICIENT_FUNDS, result.status());
            assertEquals(0, new BigDecimal("5.00").compareTo(service.getBalance(id, "openeco")));
            assertEquals(0, BigDecimal.ZERO.compareTo(service.getBalance(id, "gems")));
        });
    }

    @Test
    void rejectsWhenTheTargetBalanceLimitWouldBeBreached() throws Exception {
        withService((service, repository) -> {
            UUID id = seed(service, new BigDecimal("100.00"), new BigDecimal("4999.00"));

            ExchangeResult result = service.convertCurrency(id, "openeco", "gems",
                    new BigDecimal("40.00"), new BigDecimal("8.00"));

            assertEquals(ExchangeResult.Status.BALANCE_LIMIT, result.status());
            assertEquals(0, new BigDecimal("100.00").compareTo(service.getBalance(id, "openeco")),
                    "a rejected conversion must not debit the source");
        });
    }

    @Test
    void rejectsFrozenAccounts() throws Exception {
        withService((service, repository) -> {
            UUID id = seed(service, new BigDecimal("100.00"), new BigDecimal("10.00"));
            assertTrue(service.freezeAccount(id));

            ExchangeResult result = service.convertCurrency(id, "openeco", "gems",
                    new BigDecimal("40.00"), new BigDecimal("8.00"));

            assertEquals(ExchangeResult.Status.FROZEN, result.status());
            assertEquals(0, new BigDecimal("100.00").compareTo(service.getBalance(id, "openeco")));
        });
    }

    @Test
    void rejectsNonPositiveLegs() throws Exception {
        withService((service, repository) -> {
            UUID id = seed(service, new BigDecimal("100.00"), new BigDecimal("10.00"));

            assertEquals(ExchangeResult.Status.INVALID_AMOUNT,
                    service.convertCurrency(id, "openeco", "gems", BigDecimal.ZERO, BigDecimal.ONE).status());
            assertEquals(ExchangeResult.Status.INVALID_AMOUNT,
                    service.convertCurrency(id, "openeco", "gems", BigDecimal.ONE, BigDecimal.ZERO).status());
            assertEquals(ExchangeResult.Status.SAME_CURRENCY,
                    service.convertCurrency(id, "openeco", "openeco", BigDecimal.ONE, BigDecimal.ONE).status());
            assertEquals(ExchangeResult.Status.UNKNOWN_CURRENCY,
                    service.convertCurrency(id, "openeco", "nope", BigDecimal.ONE, BigDecimal.ONE).status());
            assertEquals(0, new BigDecimal("100.00").compareTo(service.getBalance(id, "openeco")));
        });
    }

    /**
     * Concurrent conversions of the same account must never lose or duplicate value: the
     * account lock is held across both balance writes.
     */
    @Test
    void concurrentConversionsKeepTheTotalConserved() throws Exception {
        withService((service, repository) -> {
            UUID id = seed(service, new BigDecimal("100.00"), new BigDecimal("0.00"));

            int threads = 4;
            int perThread = 25;
            var succeeded = new java.util.concurrent.atomic.AtomicInteger();
            var executor = java.util.concurrent.Executors.newFixedThreadPool(threads);
            var start = new java.util.concurrent.CountDownLatch(1);
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        if (service.convertCurrency(id, "openeco", "gems",
                                new BigDecimal("1.00"), new BigDecimal("1.00")).isSuccess()) {
                            succeeded.incrementAndGet();
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) {
                future.get(30, java.util.concurrent.TimeUnit.SECONDS);
            }
            executor.shutdown();

            int conversions = succeeded.get();
            assertEquals(0, new BigDecimal(100 - conversions).compareTo(service.getBalance(id, "openeco")),
                    "every successful conversion must have removed exactly 1.00");
            assertEquals(0, new BigDecimal(conversions).compareTo(service.getBalance(id, "gems")),
                    "every successful conversion must have credited exactly 1.00");
            // The real invariant: the two balances started at 100 and 0, so their sum must
            // still be 100. That catches both a lost debit and a duplicated credit.
            assertEquals(0, new BigDecimal("100.00").compareTo(
                            service.getBalance(id, "openeco").add(service.getBalance(id, "gems"))),
                    "converting between currencies must neither lose nor duplicate value");
        });
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private interface Check {
        void run(AccountService service, AccountRepository repository) throws Exception;
    }

    private static UUID seed(AccountService service, BigDecimal openeco, BigDecimal gems) {
        UUID id = UUID.randomUUID();
        assertTrue(service.createAccount(id, "Trader"));
        assertTrue(service.set(id, openeco).transactionSuccess());
        if (gems.compareTo(BigDecimal.ZERO) > 0) {
            assertTrue(service.set(id, "gems", gems).transactionSuccess());
        }
        return id;
    }

    private void withService(Check check) throws Exception {
        withService(event -> { }, check, null);
    }

    private void withService(Check check, Check afterShutdown) throws Exception {
        withService(event -> { }, check, afterShutdown);
    }

    private void withService(EventDispatcher dispatcher, Check check) throws Exception {
        withService(dispatcher, check, null);
    }

    private void withService(EventDispatcher dispatcher, Check check, Check afterShutdown) throws Exception {
        JdbcAccountRepository repository =
                new JdbcAccountRepository(DatabaseDialect.H2, tempDir.toString(), "conversion-test");
        try {
            AccountService service = new AccountService(repository,
                    Logger.getLogger("openeco-conversion-test"),
                    "openeco-test",
                    twoCurrencyConfig(),
                    dispatcher);
            try {
                check.run(service, repository);
            } finally {
                service.shutdown();
            }
            if (afterShutdown != null) {
                afterShutdown.run(service, repository);
            }
        } finally {
            repository.close();
        }
    }

    private static YamlConfiguration twoCurrencyConfig() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("currencies.default", "openeco");
        config.set("currencies.definitions.openeco.name-singular", "Dollar");
        config.set("currencies.definitions.openeco.name-plural", "Dollars");
        config.set("currencies.definitions.openeco.decimal-digits", 2);
        config.set("currencies.definitions.openeco.starting-balance", 0.0);
        config.set("currencies.definitions.openeco.max-balance", -1);
        config.set("currencies.definitions.gems.name-singular", "Gem");
        config.set("currencies.definitions.gems.name-plural", "Gems");
        config.set("currencies.definitions.gems.decimal-digits", 0);
        config.set("currencies.definitions.gems.starting-balance", 0.0);
        config.set("currencies.definitions.gems.max-balance", 5000);
        config.set("pay.cooldown-seconds", 0);
        config.set("pay.tax-percent", 0);
        config.set("pay.min-amount", 0.01);
        config.set("baltop.refresh-interval-seconds", 30);
        config.set("history.retention-days", -1);
        return config;
    }
}