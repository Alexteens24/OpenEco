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

package dev.alexisbinh.openeco.migrator.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The migrator reads other plugins' databases and must leave them exactly as it found them.
 */
class SqliteSupportReadOnlyTest {

    @TempDir
    Path tempDir;

    private Path createJournalModeDatabase() throws Exception {
        Path db = tempDir.resolve("legacy.db");
        try (Connection setup = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = setup.createStatement()) {
            statement.execute("CREATE TABLE tne_accounts (id TEXT, name TEXT, money REAL)");
            statement.execute("INSERT INTO tne_accounts VALUES ('a', 'Alice', 100)");
            // journal_mode is what a read-write open would silently upgrade to WAL.
            statement.execute("PRAGMA journal_mode=DELETE");
        }
        return db;
    }

    private static String journalMode(Path db) throws SQLException {
        try (Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA journal_mode")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    @Test
    void readsDataSuccessfully() throws Exception {
        Path db = createJournalModeDatabase();

        try (Connection conn = SqliteSupport.open(db)) {
            assertTrue(SqliteSupport.tableExists(conn, "tne_accounts"));
            assertFalse(SqliteSupport.tableExists(conn, "does_not_exist"));
        }
    }

    /** A write through the returned handle must be refused by SQLite itself. */
    @Test
    void refusesWrites() throws Exception {
        Path db = createJournalModeDatabase();

        try (Connection conn = SqliteSupport.open(db)) {
            SQLException error = assertThrows(SQLException.class, () -> {
                try (Statement statement = conn.createStatement()) {
                    statement.execute("UPDATE tne_accounts SET money = 0");
                }
            });
            assertTrue(error.getMessage().toLowerCase().contains("readonly")
                            || error.getMessage().toLowerCase().contains("read-only")
                            || error.getMessage().toLowerCase().contains("query_only"),
                    "unexpected error: " + error.getMessage());
        }
    }

    @Test
    void refusesDdlAndDmlIncludingDeleteAndInsert() throws Exception {
        Path db = createJournalModeDatabase();

        try (Connection conn = SqliteSupport.open(db)) {
            for (String statement : new String[]{
                    "INSERT INTO tne_accounts VALUES ('b', 'Bob', 1)",
                    "DELETE FROM tne_accounts",
                    "CREATE TABLE other (id TEXT)",
                    "DROP TABLE tne_accounts"}) {
                assertThrows(SQLException.class, () -> {
                    try (Statement s = conn.createStatement()) {
                        s.execute(statement);
                    }
                }, "must be refused: " + statement);
            }
        }
    }

    /**
     * The regression: a plain read-write connection may run WAL recovery or upgrade the journal
     * mode on open, which writes to a database the owning server still has open.
     */
    @Test
    void doesNotAlterTheForeignDatabaseOnOpen() throws Exception {
        Path db = createJournalModeDatabase();
        assertEquals("delete", journalMode(db).toLowerCase());
        byte[] before = sha256(db);

        try (Connection conn = SqliteSupport.open(db)) {
            assertTrue(SqliteSupport.tableExists(conn, "tne_accounts"));
        }

        assertEquals("delete", journalMode(db).toLowerCase(), "journal mode must be untouched");
        assertArrayEquals(before, sha256(db), "opening the database must not rewrite it");
        assertFalse(Files.exists(tempDir.resolve("legacy.db-wal")),
                "no WAL sidecar may be created for a database that is not in WAL mode");
    }

    @Test
    void repeatedOpensLeaveTheFileByteIdentical() throws Exception {
        Path db = createJournalModeDatabase();
        byte[] before = sha256(db);

        for (int i = 0; i < 5; i++) {
            try (Connection conn = SqliteSupport.open(db)) {
                SqliteSupport.listTables(conn);
            }
        }

        assertArrayEquals(before, sha256(db));
    }

    /** A table name must be data, never SQL. */
    @Test
    void tableNameCannotAlterTheQuery() throws Exception {
        Path db = createJournalModeDatabase();

        try (Connection conn = SqliteSupport.open(db)) {
            assertFalse(SqliteSupport.tableExists(conn, "tne_accounts' OR '1'='1"));
            assertFalse(SqliteSupport.tableExists(conn, "' OR 1=1 --"));
        }
    }

    private static byte[] sha256(Path file) throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}