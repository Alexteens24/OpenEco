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

import dev.alexisbinh.openeco.event.PayEvent;
import dev.alexisbinh.openeco.model.PayResult;
import dev.alexisbinh.openeco.storage.AccountRepository;
import dev.alexisbinh.openeco.storage.DatabaseDialect;
import dev.alexisbinh.openeco.storage.JdbcAccountRepository;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A payment must be offered to listeners while the account lock is held.
 *
 * <p>Before, the event was dispatched before the lock was taken. Two payments to the same
 * recipient could then both be inside the listener at once, each seeing a balance that the
 * other was about to invalidate — which is exactly how a per-recipient cap or a pay limit could
 * be exceeded by a wide margin.
 */
class PayEventDispatchTest {

    @TempDir
    Path tempDir;

    /** Classic mutual-exclusion probe: the counter must never be observed above one. */
    @Test
    void listenersAreNeverEnteredConcurrentlyForTheSameAccounts() throws Exception {
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger overlaps = new AtomicInteger();

        EventDispatcher dispatcher = event -> {
            if (!(event instanceof PayEvent)) {
                return;
            }
            if (inside.incrementAndGet() > 1) {
                overlaps.incrementAndGet();
            }
            try {
                // Widen the window a real listener would have: a database lookup, a web API call,
                // a permission check. Without the account lock this is where two threads meet.
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inside.decrementAndGet();
            }
        };

        withService(dispatcher, (service, repository) -> {
            UUID payer = UUID.randomUUID();
            UUID payee = UUID.randomUUID();
            assertTrue(service.createAccount(payer, "Payer"));
            assertTrue(service.createAccount(payee, "Payee"));
            assertTrue(service.set(payer, new BigDecimal("1000.00")).transactionSuccess());
            assertTrue(service.set(payee, BigDecimal.ZERO).transactionSuccess());

            ExecutorService pool = Executors.newFixedThreadPool(8);
            Future<?>[] futures = new Future<?>[8];
            for (int i = 0; i < futures.length; i++) {
                futures[i] = pool.submit(() -> {
                    for (int j = 0; j < 10; j++) {
                        service.pay(payer, payee, new BigDecimal("1.00"));
                    }
                });
            }
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
            pool.shutdown();

            assertEquals(0, overlaps.get(),
                    "the pay event must be dispatched under the account lock, never in parallel");
            assertEquals(0, new BigDecimal("1000.00").compareTo(
                            service.getBalance(payer, "openeco").add(service.getBalance(payee, "openeco"))),
                    "concurrent payments must conserve value");
        });
    }

    @Test
    void listenerIsOfferedEachPaymentExactlyOnce() throws Exception {
        AtomicInteger seen = new AtomicInteger();
        withService(event -> {
            if (event instanceof PayEvent) {
                seen.incrementAndGet();
            }
        }, (service, repository) -> {
            UUID payer = UUID.randomUUID();
            UUID payee = UUID.randomUUID();
            assertTrue(service.createAccount(payer, "Payer"));
            assertTrue(service.createAccount(payee, "Payee"));
            assertTrue(service.set(payer, new BigDecimal("100.00")).transactionSuccess());
            assertTrue(service.set(payee, BigDecimal.ZERO).transactionSuccess());

            for (int i = 0; i < 5; i++) {
                assertTrue(service.pay(payer, payee, new BigDecimal("10.00")).isSuccess());
            }
            assertEquals(5, seen.get(), "one event per successful payment, no duplicates");

            // The event is dispatched before the funds check so a listener can still veto or
            // observe the attempt, which is the pre-existing contract. What must never happen is
            // a duplicate event for one payment, or an event for a payment that cannot start.
            PayResult tooLarge = service.pay(payer, payee, new BigDecimal("99999.00"));
            assertFalse(tooLarge.isSuccess(), "the payer cannot cover this");
            assertEquals(6, seen.get(), "the attempt is offered once, even though it is refused");

            assertTrue(service.freezeAccount(payer));
            assertFalse(service.pay(payer, payee, new BigDecimal("1.00")).isSuccess());
            assertEquals(6, seen.get(), "a frozen account must not reach listeners at all");
        });
    }

    /**
     * A listener is still allowed to change state while it runs, and that change must be
     * honoured: a protection plugin that freezes the sender from inside its own veto check has
     * to stop the payment that is currently in flight. That only works because the frozen state
     * is re-checked after the event is dispatched.
     */
    @Test
    void listenerCanStillFreezeTheAccountMidEvent() throws Exception {
        AtomicReference<AccountService> serviceRef = new AtomicReference<>();
        AtomicInteger seen = new AtomicInteger();

        EventDispatcher dispatcher = event -> {
            if (event instanceof PayEvent pay) {
                // Freeze the sender while handling the second payment.
                if (seen.incrementAndGet() == 2) {
                    serviceRef.get().freezeAccount(pay.getFromId());
                }
            }
        };

        withService(dispatcher, (service, repository) -> {
            serviceRef.set(service);
            UUID payer = UUID.randomUUID();
            UUID payee = UUID.randomUUID();
            assertTrue(service.createAccount(payer, "Payer"));
            assertTrue(service.createAccount(payee, "Payee"));
            assertTrue(service.set(payer, new BigDecimal("100.00")).transactionSuccess());
            assertTrue(service.set(payee, BigDecimal.ZERO).transactionSuccess());

            assertTrue(service.pay(payer, payee, new BigDecimal("10.00")).isSuccess());
            assertEquals(0, new BigDecimal("90.00").compareTo(service.getBalance(payer, "openeco")));

            // The second payment reaches the listener, which freezes the sender. The frozen
            // state is read again after dispatch, so this payment must be refused.
            PayResult second = service.pay(payer, payee, new BigDecimal("10.00"));
            assertFalse(second.isSuccess(), "freezing during the event must stop the payment");
            assertEquals(PayResult.Status.FROZEN, second.getStatus());
            assertEquals(0, new BigDecimal("90.00").compareTo(service.getBalance(payer, "openeco")),
                    "the refused payment must not have moved any money");
            assertEquals(0, new BigDecimal("10.00").compareTo(service.getBalance(payee, "openeco")));
            assertTrue(service.isFrozen(payer));
        });
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private interface Check {
        void run(AccountService service, AccountRepository repository) throws Exception;
    }

    private void withService(EventDispatcher dispatcher, Check check) throws Exception {
        JdbcAccountRepository repository =
                new JdbcAccountRepository(DatabaseDialect.H2, tempDir.toString(), "pay-event-test");
        try {
            AccountService service = new AccountService(repository,
                    Logger.getLogger("openeco-pay-event-test"), "openeco-test", config(), dispatcher);
            check.run(service, repository);
            service.shutdown();
        } finally {
            repository.close();
        }
    }

    private static YamlConfiguration config() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("currencies.default", "openeco");
        config.set("currencies.definitions.openeco.name-singular", "Dollar");
        config.set("currencies.definitions.openeco.name-plural", "Dollars");
        config.set("currencies.definitions.openeco.decimal-digits", 2);
        config.set("currencies.definitions.openeco.starting-balance", 0.0);
        config.set("currencies.definitions.openeco.max-balance", -1);
        config.set("pay.cooldown-seconds", 0);
        config.set("pay.tax-percent", 0);
        config.set("pay.min-amount", 0.01);
        config.set("baltop.refresh-interval-seconds", 30);
        config.set("history.retention-days", -1);
        return config;
    }
}