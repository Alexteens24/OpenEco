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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The default currency id reaches the schema layer. It must flow through parameters, never
 * through DDL text, so quotes or backslashes cannot break (or extend) the schema statement.
 */
class CurrencyIdHardeningTest {

    @TempDir
    Path tempDir;

    @Test
    void hostileDefaultCurrencyIdCannotBreakSchemaOrBackfill() throws Exception {
        String hostile = "x'--\\";
        JdbcAccountRepository repository = new JdbcAccountRepository(
                DatabaseDialect.H2, tempDir.toString(), "hostile-currency", hostile);
        try {
            UUID id = UUID.randomUUID();
            repository.upsertBatch(List.of(new AccountRecord(
                    id, "Player", hostile, Map.of(hostile, BigDecimal.ONE), 1L, 1L)));
            repository.insertTransactionsBatch(List.of(new TransactionEntry(
                    TransactionType.GIVE, null, id,
                    BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, 1_000L, null, null, null)));

            List<TransactionEntry> rows = repository.loadTransactions(10, 0);
            assertEquals(1, rows.size());
            assertEquals(hostile, rows.getFirst().getCurrencyId(),
                    "the null currency must be backfilled with the configured default through parameters");
        } finally {
            repository.close();
        }
    }
}
