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

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.alexisbinh.openeco.model.AccountRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "OPEN_ECO_REMOTE_TESTS", matches = "true")
class RemoteAccountScanIntegrationTest {

    @Test
    void streamsAccountsOnAllRemoteDialectsAndRestoresConnections() throws Exception {
        verifyDialect(DatabaseDialect.MYSQL,
                "jdbc:mysql://127.0.0.1:13306/openeco?useSSL=false&allowPublicKeyRetrieval=true&useCursorFetch=true",
                "com.mysql.cj.jdbc.Driver", "root", "test");
        verifyDialect(DatabaseDialect.MARIADB,
                "jdbc:mariadb://127.0.0.1:13307/openeco",
                "org.mariadb.jdbc.Driver", "root", "test");
        verifyDialect(DatabaseDialect.POSTGRESQL,
                "jdbc:postgresql://127.0.0.1:15432/openeco",
                "org.postgresql.Driver", "postgres", "test");
    }

    private static void verifyDialect(DatabaseDialect dialect, String jdbcUrl, String driver,
                                      String username, String password) throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setDriverClassName(driver);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(2);
        HikariDataSource dataSource = new HikariDataSource(config);
        JdbcAccountRepository repository = new JdbcAccountRepository(dataSource, dialect, "openeco");
        try {
            int count = 1_001;
            ArrayList<AccountRecord> writeBatch = new ArrayList<>(250);
            for (int i = 0; i < count; i++) {
                writeBatch.add(new AccountRecord(
                        new UUID(i, i * 31L),
                        "Player" + i,
                        "openeco",
                        Map.of("openeco", BigDecimal.valueOf(i), "gems", BigDecimal.valueOf(i * 2L)),
                        1L,
                        i + 1L));
                if (writeBatch.size() == 250) {
                    repository.upsertBatch(writeBatch);
                    writeBatch.clear();
                }
            }
            if (!writeBatch.isEmpty()) repository.upsertBatch(writeBatch);

            assertThrows(SQLException.class, () -> repository.loadBatches(1, batch -> {
                throw new SQLException("expected consumer failure");
            }));

            List<Integer> batchSizes = new ArrayList<>();
            List<AccountRecord> loaded = new ArrayList<>();
            repository.loadBatches(137, batch -> {
                batchSizes.add(batch.size());
                loaded.addAll(batch);
            });

            assertEquals(count, loaded.size(), dialect.name());
            assertTrue(batchSizes.stream().allMatch(size -> size <= 137), dialect.name());
            AccountRecord last = loaded.getLast();
            assertEquals(0, last.getBalance("gems")
                    .compareTo(BigDecimal.valueOf((count - 1L) * 2L)), dialect.name());
            assertTrue(repository.loadAccount(last.getId()).isPresent(), dialect.name());
            try (var connection = dataSource.getConnection()) {
                assertTrue(connection.getAutoCommit(), dialect.name());
            }
        } finally {
            repository.close();
        }
    }
}
