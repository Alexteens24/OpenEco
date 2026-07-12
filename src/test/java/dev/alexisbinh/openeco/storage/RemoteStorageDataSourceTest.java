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

class RemoteStorageDataSourceTest {

    @Test
    void driverClassNameMatchesRemoteDialect() {
        assertEquals("com.mysql.cj.jdbc.Driver", RemoteStorageDataSource.driverClassName(DatabaseDialect.MYSQL));
        assertEquals("org.mariadb.jdbc.Driver", RemoteStorageDataSource.driverClassName(DatabaseDialect.MARIADB));
        assertEquals("org.postgresql.Driver", RemoteStorageDataSource.driverClassName(DatabaseDialect.POSTGRESQL));
    }

    @Test
    void mysqlUrlEnablesServerSideCursorFetchingOnlyForMysql() {
        String mysql = RemoteStorageDataSource.jdbcUrl(DatabaseDialect.MYSQL, "db", 3306, "openeco");
        String mariadb = RemoteStorageDataSource.jdbcUrl(DatabaseDialect.MARIADB, "db", 3306, "openeco");
        String postgres = RemoteStorageDataSource.jdbcUrl(DatabaseDialect.POSTGRESQL, "db", 5432, "openeco");

        assertTrue(mysql.contains("useCursorFetch=true"));
        assertFalse(mariadb.contains("useCursorFetch"));
        assertFalse(postgres.contains("useCursorFetch"));
    }
}
