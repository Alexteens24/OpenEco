package dev.alexisbinh.openeco.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public enum DatabaseDialect {

    SQLITE {
        @Override
        public String getJdbcUrl(String dataFolder, String filename) {
            return "jdbc:sqlite:" + dataFolder + "/" + filename;
        }

        @Override
        public void applyTuning(Connection conn) throws SQLException {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
                stmt.execute("PRAGMA synchronous=NORMAL");
                stmt.execute("PRAGMA foreign_keys=ON");
            }
        }

        @Override
        public String upsertSql() {
            return "INSERT INTO accounts(id,name,balance,created_at,updated_at,frozen) VALUES(?,?,?,?,?,?) "
                 + "ON CONFLICT(id) DO UPDATE SET "
                 + "name=excluded.name, balance=excluded.balance, updated_at=excluded.updated_at, frozen=excluded.frozen";
        }

        @Override
        public String balanceUpsertSql() {
            return "INSERT INTO account_balances(account_id,currency_id,balance,updated_at) VALUES(?,?,?,?) "
                 + "ON CONFLICT(account_id,currency_id) DO UPDATE SET "
                 + "balance=excluded.balance, updated_at=excluded.updated_at";
        }
    },

    H2 {
        @Override
        public String getJdbcUrl(String dataFolder, String filename) {
            return "jdbc:h2:" + dataFolder + "/" + filename + ";DB_CLOSE_ON_EXIT=FALSE";
        }

        @Override
        public void applyTuning(Connection conn) throws SQLException {
            // No special tuning needed for embedded H2
        }

        @Override
        public String upsertSql() {
            // H2 proprietary MERGE shorthand
            return "MERGE INTO accounts(id,name,balance,created_at,updated_at,frozen) KEY(id) VALUES(?,?,?,?,?,?)";
        }

        @Override
        public String balanceUpsertSql() {
            return "MERGE INTO account_balances(account_id,currency_id,balance,updated_at) KEY(account_id,currency_id) VALUES(?,?,?,?)";
        }
    };

    public abstract String getJdbcUrl(String dataFolder, String filename);

    public abstract void applyTuning(Connection conn) throws SQLException;

    public abstract String upsertSql();

    public abstract String balanceUpsertSql();

    public static DatabaseDialect fromConfig(String value) {
        if (value == null) return SQLITE;
        return switch (value.toLowerCase()) {
            case "h2" -> H2;
            default -> SQLITE;
        };
    }
}
