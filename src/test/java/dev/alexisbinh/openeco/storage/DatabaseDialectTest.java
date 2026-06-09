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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseDialectTest {

    @Test
    void mysqlDoesNotUseCreateIndexIfNotExists() {
        DatabaseDialect dialect = DatabaseDialect.MYSQL;
        assertFalse(dialect.supportsCreateIndexIfNotExists());
        assertFalse(dialect.createNameIndexSql().toUpperCase().contains("IF NOT EXISTS"));
        assertFalse(dialect.createTransactionIndexSql().toUpperCase().contains("IF NOT EXISTS"));
        assertEquals("idx_accounts_name_lower", dialect.accountsNameIndexName());
        assertTrue(dialect.createNameIndexSql().contains("LOWER(name)"));
    }

    @Test
    void mysqlUsesRowAliasUpsertSyntax() {
        String upsert = DatabaseDialect.MYSQL.upsertSql().toUpperCase();
        assertTrue(upsert.contains(" AS NEW "));
        assertFalse(upsert.contains("VALUES(NAME)"));
    }

    @Test
    void mariadbKeepsValuesUpsertAndSupportsCreateIndexIfNotExists() {
        DatabaseDialect dialect = DatabaseDialect.MARIADB;
        assertTrue(dialect.supportsCreateIndexIfNotExists());
        assertTrue(dialect.createNameIndexSql().toUpperCase().contains("IF NOT EXISTS"));
        assertTrue(dialect.createTransactionIndexSql().toUpperCase().contains("IF NOT EXISTS"));
        assertTrue(dialect.upsertSql().toUpperCase().contains("VALUES(NAME)"));
    }

    @Test
    void sqliteAndPostgresqlKeepCreateIndexIfNotExists() {
        for (DatabaseDialect dialect : new DatabaseDialect[] {DatabaseDialect.SQLITE, DatabaseDialect.POSTGRESQL}) {
            assertTrue(dialect.supportsCreateIndexIfNotExists(), dialect.name());
            assertTrue(dialect.createNameIndexSql().toUpperCase().contains("IF NOT EXISTS"), dialect.name());
            assertTrue(dialect.createTransactionIndexSql().toUpperCase().contains("IF NOT EXISTS"), dialect.name());
        }
    }
}
