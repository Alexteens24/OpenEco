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

package dev.alexisbinh.openeco.storage;

import dev.alexisbinh.openeco.model.AccountRecord;
import dev.alexisbinh.openeco.model.TransactionEntry;
import dev.alexisbinh.openeco.model.TransactionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Two backend servers can share one remote database. History timestamps are computed with a
 * MAX(ts) read-modify-write, so without a database-level lock both servers can compute the same
 * timestamp and write non-monotonic history.
 */
class ConcurrentHistoryTimestampTest {

    @TempDir
    Path tempDir;

    @Test
    void concurrentWritersProduceDistinctMonotonicTimestamps() throws Exception {
        JdbcAccountRepository first = new JdbcAccountRepository(DatabaseDialect.H2, tempDir.toString(), "history-ts");
        JdbcAccountRepository second = new JdbcAccountRepository(DatabaseDialect.H2, tempDir.toString(), "history-ts");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            UUID id = UUID.randomUUID();
            first.upsertBatch(List.of(new AccountRecord(
                    id, "Player", "openeco", Map.of("openeco", BigDecimal.TEN), 1L, 1L)));

            int threads = 4;
            int perThread = 25;
            CyclicBarrier barrier = new CyclicBarrier(threads);
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                JdbcAccountRepository repository = (t % 2 == 0) ? first : second;
                futures.add(pool.submit(() -> {
                    barrier.await();
                    for (int i = 0; i < perThread; i++) {
                        repository.insertTransaction(new TransactionEntry(
                                TransactionType.GIVE, null, id,
                                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE,
                                1_000L, null, null, "openeco"));
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }

            List<TransactionEntry> rows = first.loadTransactions(1_000, 0);
            Set<Long> timestamps = rows.stream()
                    .map(TransactionEntry::getTimestamp)
                    .collect(Collectors.toSet());
            assertEquals(threads * perThread, rows.size());
            assertEquals(threads * perThread, timestamps.size(),
                    "every history row must have a unique timestamp even with concurrent writers");
        } finally {
            pool.shutdownNow();
            first.close();
            second.close();
        }
    }
}
