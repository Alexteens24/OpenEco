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

import dev.alexisbinh.openeco.model.AccountRecord;
import dev.alexisbinh.openeco.storage.DatabaseDialect;
import dev.alexisbinh.openeco.storage.JdbcAccountRepository;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A cross-server refresh reads storage and then applies the snapshot to the live record.
 * Because a flush clears the dirty flag before it writes, a refresh that read the pre-flush
 * value used to pass the dirty check and silently overwrite the newer balance — the exact
 * lost-update the flush lock exists to prevent.
 */
class AccountServiceRefreshFlushRaceTest {

    @TempDir
    Path tempDir;

    @Test
    void refreshWaitsForAnInFlightFlushInsteadOfApplyingAStaleSnapshot() throws Exception {
        CountDownLatch writeStarted = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        AtomicBoolean armed = new AtomicBoolean();
        JdbcAccountRepository repository = new JdbcAccountRepository(DatabaseDialect.H2, tempDir.toString(), "refresh-race") {
            @Override
            public void upsertBatch(Collection<AccountRecord> records) throws SQLException {
                if (armed.compareAndSet(true, false)) {
                    writeStarted.countDown();
                    try {
                        releaseWrite.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                super.upsertBatch(records);
            }
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            AccountService service = newService(repository);
            UUID id = UUID.randomUUID();
            assertTrue(service.createAccount(id, "Player"));
            service.set(id, new BigDecimal("100.00"));
            assertTrue(service.flushDirty());
            assertEquals(0, new BigDecimal("100.00").compareTo(persisted(repository, id)));

            service.set(id, new BigDecimal("150.00"));

            armed.set(true);
            Future<Boolean> bulk = pool.submit(service::flushDirty);
            assertTrue(writeStarted.await(5, TimeUnit.SECONDS), "bulk write must be in flight");

            Future<AccountService.RefreshStatus> refresh = pool.submit(() -> service.refreshAccount(id));
            assertThrows(TimeoutException.class, () -> refresh.get(300, TimeUnit.MILLISECONDS),
                    "refresh must wait for the in-flight flush instead of reading a stale snapshot");

            releaseWrite.countDown();
            assertTrue(bulk.get(5, TimeUnit.SECONDS));
            assertEquals(AccountService.RefreshStatus.REFRESHED, refresh.get(5, TimeUnit.SECONDS));

            assertEquals(0, new BigDecimal("150.00").compareTo(service.getBalance(id)),
                    "the live balance must keep the newer value");
            assertEquals(0, new BigDecimal("150.00").compareTo(persisted(repository, id)),
                    "storage and memory must agree after the race");
            service.shutdown();
        } finally {
            releaseWrite.countDown();
            pool.shutdownNow();
            repository.close();
        }
    }

    private static Optional<BigDecimal> persistedBalance(JdbcAccountRepository repository, UUID id) throws SQLException {
        return repository.loadAccount(id).map(AccountRecord::getBalance);
    }

    private static BigDecimal persisted(JdbcAccountRepository repository, UUID id) throws SQLException {
        return persistedBalance(repository, id).orElseThrow();
    }

    private static AccountService newService(JdbcAccountRepository repository) {
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
        return new AccountService(repository, Logger.getLogger("openeco-refresh-race-test"), "openeco-test", config, event -> { });
    }
}
