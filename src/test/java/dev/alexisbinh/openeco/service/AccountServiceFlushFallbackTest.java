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
import dev.alexisbinh.openeco.storage.AccountRepository;
import dev.alexisbinh.openeco.storage.DatabaseDialect;
import dev.alexisbinh.openeco.storage.JdbcAccountRepository;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One account that cannot be written to storage must never stop the rest of the economy from
 * reaching the database.
 *
 * <p>Balances live as {@code DECIMAL(30,8)}, so an amount with 23 integer digits is accepted
 * in memory and then rejected by the driver. Because a batch write is a single transaction,
 * that rejection used to roll back every healthy account in the same flush and the autosave
 * loop would retry the identical batch on every cycle forever.
 */
class AccountServiceFlushFallbackTest {

    @TempDir
    Path tempDir;

    /** 23 integer digits: past the API guard, inside the in-memory balance, outside the column. */
    private static final BigDecimal UNPERSISTABLE = new BigDecimal("12345678901234567890123");

    private static final BigDecimal HEALTHY = new BigDecimal("500.00");

    @Test
    void unwritableAccountDoesNotBlockHealthyAccountsFromBeingPersisted() throws Exception {
        JdbcAccountRepository repository = new JdbcAccountRepository(DatabaseDialect.H2, tempDir.toString(), "flush-fallback");
        try {
            AccountService service = newService(repository);
            UUID healthyId = UUID.randomUUID();
            UUID blockedId = UUID.randomUUID();

            assertTrue(service.createAccount(healthyId, "Healthy"));
            assertTrue(service.createAccount(blockedId, "Blocked"));

            // Written straight into the live record on purpose: the storage layer must defend
            // itself even against callers that skipped the public API validation.
            setBalance(service, healthyId, HEALTHY);
            setBalance(service, blockedId, UNPERSISTABLE);

            assertFalse(service.flushDirty(), "flush must report failure while an account is unwritable");

            // The regression: before the per-record fallback the whole batch was rolled back,
            // so the healthy account was absent from the database too.
            assertEquals(0, HEALTHY.compareTo(persistedBalance(repository, healthyId).orElseThrow()),
                    "healthy account must still be persisted even though the batch had a bad record");
            assertTrue(persistedBalance(repository, blockedId).isEmpty(),
                    "the unwritable account must not be persisted");

            // Repairing the offending value unblocks the loop.
            setBalance(service, blockedId, new BigDecimal("10.00"));
            assertTrue(service.flushDirty(), "flush must report success once every account is writable");
            assertEquals(0, new BigDecimal("10.00").compareTo(persistedBalance(repository, blockedId).orElseThrow()));

            service.shutdown();
        } finally {
            repository.close();
        }
    }

    @Test
    void unwritableAccountStaysDirtySoItKeepsBeingRetried() throws Exception {
        JdbcAccountRepository repository = new JdbcAccountRepository(DatabaseDialect.H2, tempDir.toString(), "flush-retry");
        try {
            AccountService service = newService(repository);
            UUID blockedId = UUID.randomUUID();
            assertTrue(service.createAccount(blockedId, "Blocked"));
            setBalance(service, blockedId, UNPERSISTABLE);

            assertFalse(service.flushDirty());
            assertTrue(liveRecord(service, blockedId).isDirty(),
                    "a record that failed to persist must stay dirty so the next cycle retries it");

            service.shutdown();
        } finally {
            repository.close();
        }
    }

    @Test
    void healthyAccountsSurviveManyFlushCyclesAlongsideABlockedAccount() throws Exception {
        JdbcAccountRepository repository = new JdbcAccountRepository(DatabaseDialect.H2, tempDir.toString(), "flush-repeat");
        try {
            AccountService service = newService(repository);
            UUID healthyId = UUID.randomUUID();
            UUID blockedId = UUID.randomUUID();
            assertTrue(service.createAccount(healthyId, "Healthy"));
            assertTrue(service.createAccount(blockedId, "Blocked"));

            for (int cycle = 1; cycle <= 3; cycle++) {
                setBalance(service, healthyId, new BigDecimal(cycle * 100L + ".00"));
                setBalance(service, blockedId, UNPERSISTABLE);

                assertFalse(service.flushDirty(), "cycle " + cycle + " must still report failure");
                assertEquals(0, new BigDecimal(cycle * 100L + ".00").compareTo(persistedBalance(repository, healthyId).orElseThrow()),
                        "healthy balance from cycle " + cycle + " must be durable");
            }

            service.shutdown();
        } finally {
            repository.close();
        }
    }

    /**
     * A flush clears the dirty flag before it writes. A cross-server flush that ran meanwhile
     * saw a clean record and acked success although the autosave write had not landed yet.
     */
    @Test
    void singleAccountFlushWaitsForAnInFlightBulkFlush() throws Exception {
        CountDownLatch writeStarted = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        AtomicBoolean armed = new AtomicBoolean();
        JdbcAccountRepository repository = new JdbcAccountRepository(DatabaseDialect.H2, tempDir.toString(), "flush-concurrent") {
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
            service.flushDirty();

            setBalance(service, id, HEALTHY);
            armed.set(true);
            Future<Boolean> bulk = pool.submit(service::flushDirty);
            assertTrue(writeStarted.await(5, TimeUnit.SECONDS));

            Future<Boolean> single = pool.submit(() -> service.flushAccount(id));
            assertThrows(TimeoutException.class, () -> single.get(300, TimeUnit.MILLISECONDS),
                    "the single flush must wait while the bulk write is still in flight");

            releaseWrite.countDown();
            assertTrue(bulk.get(5, TimeUnit.SECONDS));
            assertTrue(single.get(5, TimeUnit.SECONDS));
            assertEquals(0, HEALTHY.compareTo(persistedBalance(repository, id).orElseThrow()));

            service.shutdown();
        } finally {
            releaseWrite.countDown();
            pool.shutdownNow();
            repository.close();
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static Optional<BigDecimal> persistedBalance(AccountRepository repository, UUID id) throws SQLException {
        return repository.loadAccount(id).map(AccountRecord::getBalance);
    }

    private static void setBalance(AccountService service, UUID id, BigDecimal amount) throws Exception {
        AccountRecord record = liveRecord(service, id);
        record.setBalance("openeco", amount);
        record.markDirty();
    }

    private static AccountRecord liveRecord(AccountService service, UUID id) throws Exception {
        Field registryField = AccountService.class.getDeclaredField("accountRegistry");
        registryField.setAccessible(true);
        Object registry = registryField.get(service);

        Method getLiveRecord = registry.getClass().getDeclaredMethod("getLiveRecord", UUID.class);
        getLiveRecord.setAccessible(true);
        return (AccountRecord) getLiveRecord.invoke(registry, id);
    }

    private static AccountService newService(AccountRepository repository) {
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
        return new AccountService(repository, Logger.getLogger("openeco-flush-fallback-test"), "openeco-test", config, event -> { });
    }
}