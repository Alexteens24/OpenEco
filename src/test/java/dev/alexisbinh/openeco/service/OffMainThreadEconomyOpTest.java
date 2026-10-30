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
import dev.alexisbinh.openeco.storage.DatabaseDialect;
import dev.alexisbinh.openeco.storage.JdbcAccountRepository;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.Event;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Economy events must run on one designated thread (the primary thread in production), and the
 * account monitors must only be acquired on that same thread. If pay/convertCurrency acquire the
 * monitors on an async caller and then block for the designated thread to run the event, a
 * listener that touches either account deadlocks the pair for the full dispatch timeout.
 */
class OffMainThreadEconomyOpTest {

    @TempDir
    Path tempDir;

    @Test
    void payCalledFromAnAsyncThreadDoesNotInvertLocksWithTheDispatchThread() throws Exception {
        JdbcAccountRepository repository = new JdbcAccountRepository(DatabaseDialect.H2, tempDir.toString(), "offmain-pay");
        MarshallingDispatcher dispatcher = new MarshallingDispatcher();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        AtomicReference<AccountService> serviceRef = new AtomicReference<>();
        try {
            AccountService service = newService(repository, dispatcher);
            serviceRef.set(service);

            UUID from = UUID.randomUUID();
            UUID to = UUID.randomUUID();
            assertTrue(service.createAccount(from, "From"));
            assertTrue(service.createAccount(to, "To"));
            service.deposit(from, new BigDecimal("100.00"));

            // Simulates a main-thread listener that reads both accounts during the PayEvent.
            // With the paying thread holding the monitors and waiting for this thread, this
            // blocks until the dispatch timeout; with the hand-off it is a reentrant read.
            dispatcher.listener = event -> {
                if (event instanceof PayEvent) {
                    serviceRef.get().getBalance(from);
                    serviceRef.get().getBalance(to);
                }
            };

            Future<PayResult> result = caller.submit(() -> service.pay(from, to, new BigDecimal("10.00")));
            PayResult payResult = result.get(10, TimeUnit.SECONDS);
            assertTrue(payResult.isSuccess(), () -> "pay failed: " + payResult.getStatus());
            assertEquals(0, new BigDecimal("90.00").compareTo(service.getBalance(from)));
            assertEquals(0, new BigDecimal("10.00").compareTo(service.getBalance(to)));
            service.shutdown();
        } finally {
            caller.shutdownNow();
            dispatcher.shutdown();
            repository.close();
        }
    }

    /**
     * Stands in for {@code BukkitEventDispatcher}: events and operations run on one dedicated
     * thread; anything arriving from another thread is marshalled there and waited for.
     */
    private static final class MarshallingDispatcher implements EventDispatcher {

        private final ExecutorService dispatchThread;
        private final AtomicReference<String> dispatchThreadName = new AtomicReference<>();
        private volatile Consumer<Event> listener = event -> { };

        MarshallingDispatcher() {
            this.dispatchThread = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "verify-dispatch-thread");
                dispatchThreadName.set(thread.getName());
                return thread;
            });
        }

        @Override
        public void dispatch(Event event) {
            call(() -> {
                listener.accept(event);
                return null;
            });
        }

        @Override
        public <T> T runOnDispatchThread(Callable<T> task) {
            return call(task);
        }

        private <T> T call(Callable<T> task) {
            if (Thread.currentThread().getName().equals(dispatchThreadName.get())) {
                try {
                    return task.call();
                } catch (RuntimeException | Error error) {
                    throw error;
                } catch (Exception error) {
                    throw new IllegalStateException("dispatch task failed", error);
                }
            }
            Future<T> future = dispatchThread.submit(task);
            try {
                return future.get(5, TimeUnit.SECONDS);
            } catch (TimeoutException error) {
                throw new IllegalStateException(
                        "deadlock: the dispatch thread could not finish while another thread held an account monitor",
                        error);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for the dispatch thread", error);
            } catch (java.util.concurrent.ExecutionException error) {
                Throwable cause = error.getCause();
                if (cause instanceof RuntimeException runtimeException) throw runtimeException;
                if (cause instanceof Error fatalError) throw fatalError;
                throw new IllegalStateException("dispatch task failed", cause);
            }
        }

        void shutdown() {
            dispatchThread.shutdownNow();
        }
    }

    private static AccountService newService(JdbcAccountRepository repository, EventDispatcher dispatcher) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("currencies.default", "openeco");
        config.set("currencies.definitions.openeco.name-singular", "Dollar");
        config.set("currencies.definitions.openeco.name-plural", "Dollars");
        config.set("currencies.definitions.openeco.decimal-digits", 2);
        config.set("currencies.definitions.openeco.starting-balance", 0);
        config.set("currencies.definitions.openeco.max-balance", -1);
        config.set("pay.cooldown-seconds", 0);
        config.set("pay.tax-percent", 0);
        config.set("pay.min-amount", 0.01);
        config.set("baltop.refresh-interval-seconds", 30);
        config.set("history.retention-days", -1);
        return new AccountService(repository, Logger.getLogger("openeco-offmain-test"), "openeco-test", config, dispatcher);
    }
}
