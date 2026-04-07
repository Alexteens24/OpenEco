package dev.alexisbinh.openeco.storage;

import java.util.Locale;

public enum DatabaseDialect {

    SQLITE {
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

        @Override
        public String createNameIndexSql() {
            return "CREATE INDEX IF NOT EXISTS idx_accounts_name_lower ON accounts(LOWER(name))";
        }

        @Override
        public String getJdbcUrl(String dataFolder, String filename) {
            return "jdbc:sqlite:" + dataFolder + "/" + filename;
        }
    },

    H2 {
        @Override
        public String upsertSql() {
            return "MERGE INTO accounts(id,name,balance,created_at,updated_at,frozen) KEY(id) VALUES(?,?,?,?,?,?)";
        }

        @Override
        public String balanceUpsertSql() {
            return "MERGE INTO account_balances(account_id,currency_id,balance,updated_at) KEY(account_id,currency_id) VALUES(?,?,?,?)";
        }

        @Override
        public String createNameIndexSql() {
            return "CREATE INDEX IF NOT EXISTS idx_accounts_name ON accounts(name)";
        }

        @Override
        public String getJdbcUrl(String dataFolder, String filename) {
            return "jdbc:h2:" + dataFolder + "/" + filename + ";DB_CLOSE_ON_EXIT=FALSE";
        }
    },

    MYSQL {
        @Override
        public String upsertSql() {
            return "INSERT INTO accounts(id,name,balance,created_at,updated_at,frozen) VALUES(?,?,?,?,?,?) "
                 + "ON DUPLICATE KEY UPDATE "
                 + "name=VALUES(name), balance=VALUES(balance), updated_at=VALUES(updated_at), frozen=VALUES(frozen)";
        }

        @Override
        public String balanceUpsertSql() {
            return "INSERT INTO account_balances(account_id,currency_id,balance,updated_at) VALUES(?,?,?,?) "
                 + "ON DUPLICATE KEY UPDATE "
                 + "balance=VALUES(balance), updated_at=VALUES(updated_at)";
        }

        @Override
        public String createNameIndexSql() {
            return "CREATE INDEX IF NOT EXISTS idx_accounts_name ON accounts(name)";
        }
    },

    MARIADB {
        @Override
        public String upsertSql() {
            return "INSERT INTO accounts(id,name,balance,created_at,updated_at,frozen) VALUES(?,?,?,?,?,?) "
                 + "ON DUPLICATE KEY UPDATE "
                 + "name=VALUES(name), balance=VALUES(balance), updated_at=VALUES(updated_at), frozen=VALUES(frozen)";
        }

        @Override
        public String balanceUpsertSql() {
            return "INSERT INTO account_balances(account_id,currency_id,balance,updated_at) VALUES(?,?,?,?) "
                 + "ON DUPLICATE KEY UPDATE "
                 + "balance=VALUES(balance), updated_at=VALUES(updated_at)";
        }

        @Override
        public String createNameIndexSql() {
            return "CREATE INDEX IF NOT EXISTS idx_accounts_name ON accounts(name)";
        }
    },

    POSTGRESQL {
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

        @Override
        public String createNameIndexSql() {
            return "CREATE INDEX IF NOT EXISTS idx_accounts_name_lower ON accounts(lower(name))";
        }
    };

    public abstract String upsertSql();

    public abstract String balanceUpsertSql();

    public abstract String createNameIndexSql();

    /**
     * Returns the JDBC URL for local file-based backends (SQLite, H2).
     * Not supported for remote backends.
     */
    public String getJdbcUrl(String dataFolder, String filename) {
        throw new UnsupportedOperationException(name() + " is a remote backend; use HikariDataSource instead");
    }

    /** True for local file-based backends (SQLite, H2). */
    public boolean isLocal() {
        return this == SQLITE || this == H2;
    }

    public static DatabaseDialect fromConfig(String value) {
        if (value == null) return SQLITE;
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "h2" -> H2;
            case "mysql" -> MYSQL;
            case "mariadb" -> MARIADB;
            case "postgresql", "postgres" -> POSTGRESQL;
            default -> SQLITE;
        };
    }
}
