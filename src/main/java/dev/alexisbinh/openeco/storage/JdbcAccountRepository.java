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
import dev.alexisbinh.openeco.model.TransactionEntry;
import dev.alexisbinh.openeco.model.TransactionType;
import dev.alexisbinh.openeco.service.LeaderboardEntry;
import dev.alexisbinh.openeco.service.LeaderboardView;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.sql.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class JdbcAccountRepository implements AccountRepository, MultiWriterRepository {

    private static final int ACCOUNT_SCAN_FETCH_SIZE = 1_000;
    private static final String ACCOUNT_SCAN_SQL = """
            SELECT a.id AS account_id,
                   a.name AS account_name,
                   a.created_at AS account_created_at,
                   a.updated_at AS account_updated_at,
                   a.frozen AS account_frozen,
                   a.version AS account_version,
                   b.currency_id AS balance_currency_id,
                   b.balance AS balance_value,
                   b.updated_at AS balance_updated_at
              FROM accounts a
              LEFT JOIN account_balances b ON b.account_id = a.id
             ORDER BY a.id, b.currency_id
            """;

    private final HikariDataSource dataSource;
    private final DatabaseDialect dialect;
    private final String defaultCurrencyId;

    // ── Local (SQLite / H2) constructors — backward compatible ──────────────

    public JdbcAccountRepository(DatabaseDialect dialect, String dataFolder, String filename) throws SQLException {
        this(dialect, dataFolder, filename, "openeco");
    }

    public JdbcAccountRepository(DatabaseDialect dialect, String dataFolder, String filename,
                                 String defaultCurrencyId) throws SQLException {
        this(buildLocalDataSource(dialect, dataFolder, filename), dialect, defaultCurrencyId);
    }

    // ── Remote (MySQL / MariaDB / PostgreSQL) constructor ───────────────────

    public JdbcAccountRepository(HikariDataSource dataSource, DatabaseDialect dialect,
                                 String defaultCurrencyId) throws SQLException {
        this.dataSource = dataSource;
        this.dialect = dialect;
        this.defaultCurrencyId = normalizeCurrencyId(defaultCurrencyId);
        try {
            createSchema();
        } catch (SQLException e) {
            dataSource.close();
            throw e;
        }
    }

    private static HikariDataSource buildLocalDataSource(DatabaseDialect dialect,
                                                         String dataFolder, String filename) {
        HikariConfig cfg = new HikariConfig();
        switch (dialect) {
            case SQLITE -> {
                cfg.setJdbcUrl("jdbc:sqlite:" + dataFolder + "/" + filename);
                cfg.addDataSourceProperty("journal_mode", "WAL");
                cfg.addDataSourceProperty("synchronous", "NORMAL");
                cfg.addDataSourceProperty("foreign_keys", "ON");
            }
            case H2 -> cfg.setJdbcUrl("jdbc:h2:" + dataFolder + "/" + filename + ";DB_CLOSE_ON_EXIT=FALSE");
            default -> throw new IllegalArgumentException("Not a local dialect: " + dialect);
        }
        cfg.setMaximumPoolSize(1);
        cfg.setMinimumIdle(1);
        cfg.setPoolName("OpenEco-" + dialect.name());
        return new HikariDataSource(cfg);
    }

    // ── Schema ────────────────────────────────────────────────────────────────

    private void createSchema() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS accounts (
                        id         VARCHAR(36)   NOT NULL PRIMARY KEY,
                        name       VARCHAR(16)   NOT NULL,
                        normalized_name VARCHAR(16),
                        balance    DECIMAL(30,8) NOT NULL DEFAULT 0,
                        created_at BIGINT        NOT NULL,
                        updated_at BIGINT        NOT NULL
                    )
                    """);
                createIndexIfMissing(conn, stmt, "accounts", dialect.accountsNameIndexName(),
                        dialect.createNameIndexSql());
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS account_balances (
                        account_id  VARCHAR(36)   NOT NULL,
                        currency_id VARCHAR(32)   NOT NULL,
                        balance     DECIMAL(30,8) NOT NULL DEFAULT 0,
                        updated_at  BIGINT        NOT NULL,
                        PRIMARY KEY (account_id, currency_id)
                    )
                    """);
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS transactions (
                        type           VARCHAR(16)   NOT NULL,
                        counterpart_id VARCHAR(36),
                        target_id      VARCHAR(36)   NOT NULL,
                        amount         DECIMAL(30,8) NOT NULL,
                        balance_before DECIMAL(30,8) NOT NULL,
                        balance_after  DECIMAL(30,8) NOT NULL,
                        ts             BIGINT        NOT NULL
                    )
                    """);
                ensureColumn(conn, stmt, "transactions", "source", "VARCHAR(64)");
                ensureColumn(conn, stmt, "transactions", "note", "VARCHAR(255)");
                ensureColumn(conn, stmt, "transactions", "currency_id",
                        "VARCHAR(32) NOT NULL DEFAULT '" + sqlLiteral(defaultCurrencyId) + "'");
                ensureColumn(conn, stmt, "accounts", "frozen", "BOOLEAN NOT NULL DEFAULT FALSE");
                ensureColumn(conn, stmt, "accounts", "version", "BIGINT NOT NULL DEFAULT 0");
                ensureColumn(conn, stmt, "accounts", "normalized_name", "VARCHAR(16)");
                stmt.executeUpdate("UPDATE accounts SET normalized_name=LOWER(TRIM(name)) "
                        + "WHERE normalized_name IS NULL OR normalized_name=''");
                failOnDuplicateNormalizedNames(conn);
                createIndexIfMissing(conn, stmt, "accounts", "ux_accounts_normalized_name",
                        uniqueNormalizedNameIndexSql());
                ensureColumn(conn, stmt, "transactions", "operation_id", "VARCHAR(36)");
                createMultiWriterSchema(stmt);
                createIndexIfMissing(conn, stmt, "transactions", "idx_tx_target_ts",
                        dialect.createTransactionIndexSql());
                backfillDefaultBalances(conn);
                backfillTransactionCurrencies(conn);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    private void createMultiWriterSchema(Statement stmt) throws SQLException {
        String sequenceColumn = switch (dialect) {
            case SQLITE -> "INTEGER PRIMARY KEY AUTOINCREMENT";
            case H2 -> "BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY";
            case POSTGRESQL -> "BIGSERIAL PRIMARY KEY";
            case MYSQL, MARIADB -> "BIGINT AUTO_INCREMENT PRIMARY KEY";
        };
        stmt.execute("""
                CREATE TABLE IF NOT EXISTS economy_operations (
                    operation_id VARCHAR(36) NOT NULL PRIMARY KEY,
                    kind VARCHAR(32) NOT NULL,
                    created_at BIGINT NOT NULL
                )
                """);
        stmt.execute("CREATE TABLE IF NOT EXISTS account_changes ("
                + "seq " + sequenceColumn + ","
                + "account_id VARCHAR(36) NOT NULL,"
                + "account_version BIGINT NOT NULL,"
                + "change_kind VARCHAR(16) NOT NULL,"
                + "operation_id VARCHAR(36) NOT NULL,"
                + "changed_at BIGINT NOT NULL)");
        stmt.execute("""
                CREATE TABLE IF NOT EXISTS account_versions (
                    account_id VARCHAR(36) NOT NULL PRIMARY KEY,
                    version BIGINT NOT NULL
                )
                """);
        stmt.execute("""
                CREATE TABLE IF NOT EXISTS account_pay_state (
                    account_id VARCHAR(36) NOT NULL PRIMARY KEY,
                    last_pay_at BIGINT NOT NULL
                )
                """);
        stmt.execute("""
                CREATE TABLE IF NOT EXISTS economy_policy_usage (
                    provider_id VARCHAR(64) NOT NULL,
                    subject_id VARCHAR(36) NOT NULL,
                    currency_id VARCHAR(32) NOT NULL,
                    operation_id VARCHAR(36) NOT NULL,
                    amount DECIMAL(30,8) NOT NULL,
                    occurred_at BIGINT NOT NULL,
                    PRIMARY KEY (provider_id, operation_id)
                )
                """);
        stmt.execute("""
                CREATE TABLE IF NOT EXISTS economy_cluster_jobs (
                    job_id VARCHAR(64) NOT NULL,
                    run_id VARCHAR(96) NOT NULL,
                    lease_owner VARCHAR(36) NOT NULL,
                    lease_until BIGINT NOT NULL,
                    completed BOOLEAN NOT NULL DEFAULT FALSE,
                    PRIMARY KEY (job_id, run_id)
                )
                """);
        createIndexIfMissingForMultiWriter(stmt, "idx_account_changes_changed",
                "CREATE INDEX idx_account_changes_changed ON account_changes(changed_at)");
        if (!indexExists(stmt.getConnection(), "economy_policy_usage", "idx_policy_usage_window")) {
            stmt.execute("CREATE INDEX idx_policy_usage_window ON economy_policy_usage(provider_id,subject_id,currency_id,occurred_at)");
        }
        backfillAccountVersions(stmt.getConnection());
    }

    private void backfillAccountVersions(Connection conn) throws SQLException {
        try (PreparedStatement count = conn.prepareStatement("SELECT COUNT(*) FROM account_versions");
             ResultSet rs = count.executeQuery()) {
            if (rs.next() && rs.getLong(1) > 0L) return;
        }
        String sql = "SELECT account_id,MAX(version_value) FROM ("
                + "SELECT id AS account_id,version AS version_value FROM accounts "
                + "UNION ALL SELECT account_id,account_version AS version_value FROM account_changes"
                + ") stored_versions GROUP BY account_id";
        try (PreparedStatement select = conn.prepareStatement(sql);
             ResultSet rs = select.executeQuery();
             PreparedStatement upsert = conn.prepareStatement(accountVersionBackfillSql())) {
            while (rs.next()) {
                String accountId = rs.getString(1);
                long version = rs.getLong(2);
                upsert.setString(1, accountId);
                upsert.setLong(2, version);
                upsert.executeUpdate();
            }
        }
    }

    private String accountVersionBackfillSql() {
        return switch (dialect) {
            case SQLITE, POSTGRESQL -> "INSERT INTO account_versions(account_id,version) VALUES(?,?) "
                    + "ON CONFLICT(account_id) DO UPDATE SET version=CASE "
                    + "WHEN excluded.version>account_versions.version THEN excluded.version "
                    + "ELSE account_versions.version END";
            case MYSQL, MARIADB -> "INSERT INTO account_versions(account_id,version) VALUES(?,?) "
                    + "ON DUPLICATE KEY UPDATE version=GREATEST(version,VALUES(version))";
            case H2 -> "MERGE INTO account_versions(account_id,version) KEY(account_id) VALUES(?,?)";
        };
    }

    private void createIndexIfMissingForMultiWriter(Statement stmt, String name, String sql) throws SQLException {
        if (!indexExists(stmt.getConnection(), "account_changes", name)) {
            stmt.execute(sql);
        }
    }

    private String uniqueNormalizedNameIndexSql() {
        return switch (dialect) {
            case MYSQL -> "CREATE UNIQUE INDEX ux_accounts_normalized_name ON accounts(normalized_name)";
            default -> "CREATE UNIQUE INDEX IF NOT EXISTS ux_accounts_normalized_name ON accounts(normalized_name)";
        };
    }

    private void failOnDuplicateNormalizedNames(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT normalized_name,COUNT(*) AS duplicate_count FROM accounts "
                        + "GROUP BY normalized_name HAVING COUNT(*) > 1");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                throw new SQLException("Duplicate stored account name '" + rs.getString("normalized_name")
                        + "' prevents case-insensitive name indexing (" + rs.getLong("duplicate_count")
                        + " accounts). Resolve the duplicate before starting OpenEco.");
            }
        }
    }

    private void createIndexIfMissing(Connection conn, Statement stmt, String tableName,
                                      String indexName, String createIndexSql) throws SQLException {
        if (!dialect.supportsCreateIndexIfNotExists() && indexExists(conn, tableName, indexName)) {
            return;
        }
        stmt.execute(createIndexSql);
    }

    private boolean indexExists(Connection conn, String tableName, String indexName) throws SQLException {
        DatabaseMetaData metaData = conn.getMetaData();
        return hasIndex(metaData, tableName, indexName)
                || hasIndex(metaData, tableName.toUpperCase(Locale.ROOT), indexName.toUpperCase(Locale.ROOT))
                || hasIndex(metaData, tableName.toLowerCase(Locale.ROOT), indexName.toLowerCase(Locale.ROOT));
    }

    private static boolean hasIndex(DatabaseMetaData metaData, String tableName, String indexName) throws SQLException {
        try (ResultSet rs = metaData.getIndexInfo(null, null, tableName, false, false)) {
            while (rs.next()) {
                String found = rs.getString("INDEX_NAME");
                if (found != null && found.equalsIgnoreCase(indexName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void ensureColumn(Connection conn, Statement stmt, String tableName,
                               String columnName, String definition) throws SQLException {
        if (columnExists(conn, tableName, columnName)) {
            return;
        }
        stmt.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + definition);
    }

    private boolean columnExists(Connection conn, String tableName, String columnName) throws SQLException {
        DatabaseMetaData metaData = conn.getMetaData();
        return hasColumn(metaData, tableName, columnName)
                || hasColumn(metaData, tableName.toUpperCase(Locale.ROOT), columnName.toUpperCase(Locale.ROOT))
                || hasColumn(metaData, tableName.toLowerCase(Locale.ROOT), columnName.toLowerCase(Locale.ROOT));
    }

    private static boolean hasColumn(DatabaseMetaData metaData, String tableName, String columnName) throws SQLException {
        try (ResultSet rs = metaData.getColumns(null, null, tableName, columnName)) {
            return rs.next();
        }
    }

    // ── AccountRepository ─────────────────────────────────────────────────────

    @Override
    public void loadBatches(int batchSize, AccountBatchConsumer consumer) throws SQLException {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be greater than 0");
        }
        try (Connection conn = dataSource.getConnection()) {
            scanAccountRows(conn, batchSize, consumer);
        }
    }

    private void scanAccountRows(Connection conn, int batchSize, AccountBatchConsumer consumer) throws SQLException {
        boolean postgresTransaction = dialect == DatabaseDialect.POSTGRESQL;
        boolean restoreAutoCommit = postgresTransaction && conn.getAutoCommit();
        boolean h2Lazy = dialect == DatabaseDialect.H2;
        SQLException primaryFailure = null;

        try {
            if (restoreAutoCommit) {
                conn.setAutoCommit(false);
            }
            if (h2Lazy) {
                setH2LazyQueryExecution(conn, true);
            }
            scanAccountRowsConfigured(conn, batchSize, consumer);
        } catch (SQLException e) {
            primaryFailure = e;
            throw e;
        } finally {
            SQLException cleanupFailure = null;
            if (postgresTransaction) {
                try {
                    conn.rollback();
                } catch (SQLException e) {
                    cleanupFailure = e;
                }
                if (restoreAutoCommit) {
                    try {
                        conn.setAutoCommit(true);
                    } catch (SQLException e) {
                        if (cleanupFailure == null) cleanupFailure = e;
                        else cleanupFailure.addSuppressed(e);
                    }
                }
            }
            if (h2Lazy) {
                try {
                    setH2LazyQueryExecution(conn, false);
                } catch (SQLException e) {
                    if (cleanupFailure == null) cleanupFailure = e;
                    else cleanupFailure.addSuppressed(e);
                }
            }
            if (cleanupFailure != null) {
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure);
                else throw cleanupFailure;
            }
        }
    }

    private void scanAccountRowsConfigured(
            Connection conn, int batchSize, AccountBatchConsumer consumer) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                ACCOUNT_SCAN_SQL, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            if (dialect != DatabaseDialect.SQLITE) {
                ps.setFetchSize(ACCOUNT_SCAN_FETCH_SIZE);
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<AccountRecord> batch = new ArrayList<>(batchSize);
                UUID currentId = null;
                PersistedAccountRow currentAccount = null;
                Map<String, PersistedBalanceRow> currentBalances = new LinkedHashMap<>();

                while (rs.next()) {
                    UUID rowId = UUID.fromString(rs.getString("account_id"));
                    if (currentId != null && !currentId.equals(rowId)) {
                        batch.add(buildScannedRecord(currentId, currentAccount, currentBalances));
                        if (batch.size() == batchSize) {
                            consumer.accept(List.copyOf(batch));
                            batch.clear();
                        }
                        currentBalances = new LinkedHashMap<>();
                    }
                    if (!rowId.equals(currentId)) {
                        currentId = rowId;
                        currentAccount = new PersistedAccountRow(
                                rs.getString("account_name"),
                                rs.getLong("account_created_at"),
                                rs.getLong("account_updated_at"),
                                rs.getBoolean("account_frozen"),
                                rs.getLong("account_version"));
                    }

                    String rawCurrencyId = rs.getString("balance_currency_id");
                    if (rawCurrencyId != null) {
                        String currencyId = normalizePersistedCurrencyId(rawCurrencyId);
                        PersistedBalanceRow candidate = new PersistedBalanceRow(
                                currencyId,
                                rs.getBigDecimal("balance_value"),
                                rs.getLong("balance_updated_at"));
                        String lookupKey = normalizeCurrencyLookupKey(currencyId);
                        PersistedBalanceRow existing = currentBalances.get(lookupKey);
                        if (existing == null || candidate.updatedAt() >= existing.updatedAt()) {
                            currentBalances.put(lookupKey, candidate);
                        }
                    }
                }

                if (currentId != null) {
                    batch.add(buildScannedRecord(currentId, currentAccount, currentBalances));
                }
                if (!batch.isEmpty()) {
                    consumer.accept(List.copyOf(batch));
                }
            }
        }
    }

    private AccountRecord buildScannedRecord(UUID id, PersistedAccountRow account,
                                             Map<String, PersistedBalanceRow> balanceRows) {
        Map<String, BigDecimal> balances = new LinkedHashMap<>();
        for (PersistedBalanceRow row : balanceRows.values()) {
            balances.put(row.currencyId(), row.balance());
        }
        if (balances.isEmpty()) {
            balances.put(defaultCurrencyId, BigDecimal.ZERO);
        }
        AccountRecord record = new AccountRecord(
                id, account.name(), defaultCurrencyId, balances, account.createdAt(), account.updatedAt(), account.version());
        record.setFrozen(account.frozen());
        record.clearDirty();
        return record;
    }

    private static void setH2LazyQueryExecution(Connection conn, boolean enabled) throws SQLException {
        try (Statement statement = conn.createStatement()) {
            statement.execute("SET LAZY_QUERY_EXECUTION " + (enabled ? "TRUE" : "FALSE"));
        }
    }

    @Override
    public Optional<AccountRecord> loadAccount(UUID id) throws SQLException {
        return loadPointAccount("a.id=?", id.toString());
    }

    @Override
    public Optional<AccountRecord> loadAccountByName(String normalizedName) throws SQLException {
        return loadPointAccount("a.normalized_name=?", normalizeAccountName(normalizedName));
    }

    private Optional<AccountRecord> loadPointAccount(String predicate, String value) throws SQLException {
        String sql = "SELECT a.id AS account_id,a.name AS account_name,"
                + "a.created_at AS account_created_at,a.updated_at AS account_updated_at,"
                + "a.frozen AS account_frozen,a.version AS account_version,b.currency_id AS balance_currency_id,"
                + "b.balance AS balance_value,b.updated_at AS balance_updated_at "
                + "FROM accounts a LEFT JOIN account_balances b ON b.account_id=a.id "
                + "WHERE " + predicate + " ORDER BY b.currency_id";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                UUID id = UUID.fromString(rs.getString("account_id"));
                PersistedAccountRow account = new PersistedAccountRow(
                        rs.getString("account_name"),
                        rs.getLong("account_created_at"),
                        rs.getLong("account_updated_at"),
                        rs.getBoolean("account_frozen"),
                        rs.getLong("account_version"));
                Map<String, PersistedBalanceRow> balances = new LinkedHashMap<>();
                do {
                    addPersistedBalance(rs, balances);
                } while (rs.next());
                return Optional.of(buildScannedRecord(id, account, balances));
            }
        }
    }

    private void addPersistedBalance(
            ResultSet rs, Map<String, PersistedBalanceRow> balances) throws SQLException {
        String rawCurrencyId = rs.getString("balance_currency_id");
        if (rawCurrencyId == null) return;
        String currencyId = normalizePersistedCurrencyId(rawCurrencyId);
        PersistedBalanceRow candidate = new PersistedBalanceRow(
                currencyId,
                rs.getBigDecimal("balance_value"),
                rs.getLong("balance_updated_at"));
        String lookupKey = normalizeCurrencyLookupKey(currencyId);
        PersistedBalanceRow existing = balances.get(lookupKey);
        if (existing == null || candidate.updatedAt() >= existing.updatedAt()) {
            balances.put(lookupKey, candidate);
        }
    }

    @Override
    public Map<UUID, String> loadNameMap() throws SQLException {
        Map<UUID, String> names = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT id,name FROM accounts ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) names.put(UUID.fromString(rs.getString("id")), rs.getString("name"));
        }
        return Map.copyOf(names);
    }

    @Override
    public Map<UUID, String> loadNames(Collection<UUID> accountIds) throws SQLException {
        if (accountIds.isEmpty()) return Map.of();
        List<UUID> ids = List.copyOf(accountIds);
        Map<UUID, String> names = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection()) {
            for (int start = 0; start < ids.size(); start += 500) {
                List<UUID> batch = ids.subList(start, Math.min(ids.size(), start + 500));
                String placeholders = String.join(",", java.util.Collections.nCopies(batch.size(), "?"));
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT id,name FROM accounts WHERE id IN (" + placeholders + ')')) {
                    for (int i = 0; i < batch.size(); i++) ps.setString(i + 1, batch.get(i).toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) names.put(UUID.fromString(rs.getString("id")), rs.getString("name"));
                    }
                }
            }
        }
        return Map.copyOf(names);
    }

    @Override
    public int countAccounts() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM accounts");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    @Override
    public boolean isNameClaimedByAnother(UUID accountId, String normalizedName) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id FROM accounts WHERE normalized_name=? AND id<>?")) {
            ps.setString(1, normalizeAccountName(normalizedName));
            ps.setString(2, accountId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    @Override
    public boolean insertAccount(AccountRecord record) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement accountPs = conn.prepareStatement(
                    "INSERT INTO accounts(id,name,normalized_name,balance,created_at,updated_at,frozen) VALUES(?,?,?,?,?,?,?)");
                 PreparedStatement balancePs = conn.prepareStatement(
                    "INSERT INTO account_balances(account_id,currency_id,balance,updated_at) VALUES(?,?,?,?)")) {
                bindAccount(accountPs, record);
                accountPs.executeUpdate();
                for (Map.Entry<String, BigDecimal> entry : record.getBalancesSnapshot().entrySet()) {
                    balancePs.setString(1, record.getId().toString());
                    balancePs.setString(2, entry.getKey());
                    balancePs.setBigDecimal(3, entry.getValue());
                    balancePs.setLong(4, record.getUpdatedAt());
                    balancePs.addBatch();
                }
                balancePs.executeBatch();
                conn.commit();
                return true;
            } catch (SQLIntegrityConstraintViolationException e) {
                conn.rollback();
                return false;
            } catch (SQLException e) {
                conn.rollback();
                if (isConstraintViolation(e)) return false;
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    @Override
    public boolean renameAccount(UUID accountId, String newName, long updatedAt) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE accounts SET name=?,normalized_name=?,updated_at=? WHERE id=?")) {
            ps.setString(1, newName);
            ps.setString(2, normalizeAccountName(newName));
            ps.setLong(3, updatedAt);
            ps.setString(4, accountId.toString());
            try {
                return ps.executeUpdate() == 1;
            } catch (SQLIntegrityConstraintViolationException e) {
                return false;
            } catch (SQLException e) {
                if (isConstraintViolation(e)) return false;
                throw e;
            }
        }
    }

    @Override
    public LeaderboardView loadLeaderboardPage(String currencyId, int offset, int limit) throws SQLException {
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.max(0, limit);
        List<LeaderboardEntry> entries = new ArrayList<>(safeLimit);
        String sql = "SELECT a.id,a.name,COALESCE(b.balance,0) AS leaderboard_balance "
                + "FROM accounts a LEFT JOIN ("
                + "SELECT account_id,balance FROM ("
                + "SELECT account_id,balance,ROW_NUMBER() OVER ("
                + "PARTITION BY account_id,LOWER(currency_id) ORDER BY updated_at DESC,currency_id"
                + ") AS currency_position FROM account_balances WHERE LOWER(currency_id)=LOWER(?)"
                + ") currency_balances WHERE currency_position=1"
                + ") b ON b.account_id=a.id "
                + "ORDER BY leaderboard_balance DESC,LOWER(a.name),a.id LIMIT ? OFFSET ?";
        try (Connection conn = dataSource.getConnection()) {
            boolean originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                int total = countAccounts(conn);
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, currencyId);
                    ps.setInt(2, safeLimit);
                    ps.setInt(3, safeOffset);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            entries.add(new LeaderboardEntry(UUID.fromString(rs.getString("id")),
                                    rs.getString("name"), rs.getBigDecimal("leaderboard_balance")));
                        }
                    }
                }
                conn.commit();
                return new LeaderboardView(total, entries);
            } catch (SQLException error) {
                conn.rollback();
                throw error;
            } finally {
                conn.setAutoCommit(originalAutoCommit);
            }
        }
    }

    @Override
    public int loadLeaderboardRank(String currencyId, UUID accountId) throws SQLException {
        String sql = "SELECT ranked_position FROM ("
                + "SELECT a.id,ROW_NUMBER() OVER (ORDER BY COALESCE(b.balance,0) DESC,LOWER(a.name),a.id) AS ranked_position "
                + "FROM accounts a LEFT JOIN ("
                + "SELECT account_id,balance FROM ("
                + "SELECT account_id,balance,ROW_NUMBER() OVER ("
                + "PARTITION BY account_id,LOWER(currency_id) ORDER BY updated_at DESC,currency_id"
                + ") AS currency_position FROM account_balances WHERE LOWER(currency_id)=LOWER(?)"
                + ") currency_balances WHERE currency_position=1"
                + ") b ON b.account_id=a.id"
                + ") ranked WHERE id=?";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, currencyId);
            ps.setString(2, accountId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    private static int countAccounts(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM accounts");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    @Override
    public void upsertBatch(Collection<AccountRecord> records) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement accountPs = conn.prepareStatement(dialect.upsertSql());
                 PreparedStatement deleteBalancesPs = conn.prepareStatement(
                         "DELETE FROM account_balances WHERE account_id=?");
                 PreparedStatement insertBalancePs = conn.prepareStatement(
                         "INSERT INTO account_balances(account_id,currency_id,balance,updated_at) VALUES(?,?,?,?)")) {
                for (AccountRecord r : records) {
                    bindAccount(accountPs, r);
                    accountPs.addBatch();

                    deleteBalancesPs.setString(1, r.getId().toString());
                    deleteBalancesPs.addBatch();
                }

                accountPs.executeBatch();
                deleteBalancesPs.executeBatch();

                for (AccountRecord r : records) {
                    for (Map.Entry<String, BigDecimal> balanceEntry : r.getBalancesSnapshot().entrySet()) {
                        insertBalancePs.setString(1, r.getId().toString());
                        insertBalancePs.setString(2, balanceEntry.getKey());
                        insertBalancePs.setBigDecimal(3, balanceEntry.getValue());
                        insertBalancePs.setLong(4, r.getUpdatedAt());
                        insertBalancePs.addBatch();
                    }
                }
                insertBalancePs.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    // ── Multi-writer authoritative operations ────────────────────────────────

    @Override
    public BalanceMutationResult mutateBalance(BalanceMutationRequest request) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            beginAuthoritativeTransaction(conn);
            try {
                LockedAccount locked = lockAccount(conn, request.accountId());
                if (locked == null) {
                    conn.rollback();
                    return new BalanceMutationResult(MutationStatus.ACCOUNT_NOT_FOUND, request.amount(),
                            BigDecimal.ZERO, BigDecimal.ZERO, null);
                }
                BigDecimal before = locked.balance(request.currencyId());
                if (operationExists(conn, request.operationId())) {
                    conn.rollback();
                    return new BalanceMutationResult(MutationStatus.ALREADY_APPLIED, request.amount(),
                            before, before, locked.toRecord());
                }
                if (locked.frozen()) {
                    conn.rollback();
                    return new BalanceMutationResult(MutationStatus.FROZEN, request.amount(), before, before, locked.toRecord());
                }
                BigDecimal after = switch (request.kind()) {
                    case DEPOSIT -> before.add(request.amount());
                    case WITHDRAW -> before.subtract(request.amount());
                    case SET -> request.amount();
                };
                if (after.signum() < 0) {
                    conn.rollback();
                    return new BalanceMutationResult(MutationStatus.INSUFFICIENT_FUNDS, request.amount(), before, before, locked.toRecord());
                }
                if (request.maxBalance() != null && after.compareTo(request.maxBalance()) > 0) {
                    conn.rollback();
                    return new BalanceMutationResult(MutationStatus.BALANCE_LIMIT, request.amount(), before, before, locked.toRecord());
                }

                long version = advanceAccountVersion(conn, request.accountId(), locked.version());
                upsertBalance(conn, request.accountId(), request.currencyId(), after, request.timestamp());
                updateAccountMetadata(conn, locked, request.currencyId(), after, request.timestamp(), version);
                insertTransaction(conn, request.operationId(), request.transactionType(), null,
                        request.accountId(), request.amount(), before, after, request.timestamp(),
                        request.source(), request.note(), request.currencyId());
                recordOperation(conn, request.operationId(), "BALANCE", request.timestamp());
                insertChange(conn, request.operationId(), request.accountId(), version, ChangeKind.UPSERT, request.timestamp());
                LockedAccount updated = locked.withBalance(request.currencyId(), after, request.timestamp(), version);
                conn.commit();
                return new BalanceMutationResult(MutationStatus.SUCCESS, request.amount(), before, after, updated.toRecord());
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                restoreAutoCommit(conn);
            }
        }
    }

    @Override
    public TransferMutationResult transfer(TransferMutationRequest request) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            beginAuthoritativeTransaction(conn);
            try {
                UUID firstId = request.fromId().compareTo(request.toId()) < 0 ? request.fromId() : request.toId();
                UUID secondId = firstId.equals(request.fromId()) ? request.toId() : request.fromId();
                LockedAccount first = lockAccount(conn, firstId);
                LockedAccount second = lockAccount(conn, secondId);
                LockedAccount from = firstId.equals(request.fromId()) ? first : second;
                LockedAccount to = firstId.equals(request.fromId()) ? second : first;
                if (from == null || to == null) {
                    conn.rollback();
                    return transferFailure(request, MutationStatus.ACCOUNT_NOT_FOUND, 0L, from, to);
                }
                if (from.frozen() || to.frozen()) {
                    conn.rollback();
                    return transferFailure(request, MutationStatus.FROZEN, 0L, from, to);
                }
                if (request.applyCooldown() && request.cooldownMs() > 0) {
                    long lastPayAt = loadLastPayAt(conn, request.fromId());
                    long remaining = request.cooldownMs() - (request.timestamp() - lastPayAt);
                    if (remaining > 0) {
                        conn.rollback();
                        return transferFailure(request, MutationStatus.COOLDOWN, remaining, from, to);
                    }
                }
                for (RollingPolicyConstraint constraint : request.rollingPolicies()) {
                    BigDecimal used = loadPolicyUsage(conn, constraint.providerId(), request.fromId(),
                            request.currencyId(), request.timestamp() - constraint.windowMs());
                    if (used.add(request.sent()).compareTo(constraint.maximumAmount()) > 0) {
                        conn.rollback();
                        return transferFailure(request, MutationStatus.POLICY_REJECTED, 0L, from, to);
                    }
                }
                BigDecimal fromBefore = from.balance(request.currencyId());
                BigDecimal toBefore = to.balance(request.currencyId());
                if (fromBefore.compareTo(request.sent()) < 0) {
                    conn.rollback();
                    return transferFailure(request, MutationStatus.INSUFFICIENT_FUNDS, 0L, from, to);
                }
                BigDecimal fromAfter = fromBefore.subtract(request.sent());
                BigDecimal toAfter = toBefore.add(request.received());
                if (request.recipientMaxBalance() != null && toAfter.compareTo(request.recipientMaxBalance()) > 0) {
                    conn.rollback();
                    return transferFailure(request, MutationStatus.BALANCE_LIMIT, 0L, from, to);
                }

                long firstVersion = advanceAccountVersion(conn, firstId, first.version());
                long secondVersion = advanceAccountVersion(conn, secondId, second.version());
                long fromVersion = firstId.equals(request.fromId()) ? firstVersion : secondVersion;
                long toVersion = firstId.equals(request.toId()) ? firstVersion : secondVersion;
                upsertBalance(conn, request.fromId(), request.currencyId(), fromAfter, request.timestamp());
                upsertBalance(conn, request.toId(), request.currencyId(), toAfter, request.timestamp());
                updateAccountMetadata(conn, from, request.currencyId(), fromAfter, request.timestamp(), fromVersion);
                updateAccountMetadata(conn, to, request.currencyId(), toAfter, request.timestamp(), toVersion);
                if (request.applyCooldown()) upsertLastPayAt(conn, request.fromId(), request.timestamp());
                for (RollingPolicyConstraint constraint : request.rollingPolicies()) {
                    insertPolicyUsage(conn, constraint.providerId(), request.fromId(), request.currencyId(),
                            request.operationId(), request.sent(), request.timestamp());
                }
                insertTransaction(conn, request.operationId(), TransactionType.PAY_SENT, request.toId(), request.fromId(),
                        request.sent(), fromBefore, fromAfter, request.timestamp(), null, null, request.currencyId());
                insertTransaction(conn, request.operationId(), TransactionType.PAY_RECEIVED, request.fromId(), request.toId(),
                        request.received(), toBefore, toAfter, request.timestamp(), null, null, request.currencyId());
                recordOperation(conn, request.operationId(), request.applyCooldown() ? "PAY" : "TRANSFER", request.timestamp());
                insertChange(conn, request.operationId(), request.fromId(), fromVersion, ChangeKind.UPSERT, request.timestamp());
                insertChange(conn, request.operationId(), request.toId(), toVersion, ChangeKind.UPSERT, request.timestamp());
                LockedAccount updatedFrom = from.withBalance(request.currencyId(), fromAfter, request.timestamp(), fromVersion);
                LockedAccount updatedTo = to.withBalance(request.currencyId(), toAfter, request.timestamp(), toVersion);
                conn.commit();
                return new TransferMutationResult(MutationStatus.SUCCESS, request.sent(), request.received(), request.tax(),
                        0L, updatedFrom.toRecord(), updatedTo.toRecord());
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                restoreAutoCommit(conn);
            }
        }
    }

    @Override
    public ExchangeMutationResult exchange(ExchangeMutationRequest request) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            beginAuthoritativeTransaction(conn);
            try {
                LockedAccount account = lockAccount(conn, request.accountId());
                if (account == null) {
                    conn.rollback();
                    return new ExchangeMutationResult(MutationStatus.ACCOUNT_NOT_FOUND,
                            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null);
                }
                BigDecimal fromBefore = account.balance(request.fromCurrencyId());
                BigDecimal toBefore = account.balance(request.toCurrencyId());
                if (account.frozen()) {
                    conn.rollback();
                    return new ExchangeMutationResult(MutationStatus.FROZEN, fromBefore, fromBefore, toBefore, toBefore, account.toRecord());
                }
                if (fromBefore.compareTo(request.fromAmount()) < 0) {
                    conn.rollback();
                    return new ExchangeMutationResult(MutationStatus.INSUFFICIENT_FUNDS, fromBefore, fromBefore, toBefore, toBefore, account.toRecord());
                }
                BigDecimal fromAfter = fromBefore.subtract(request.fromAmount());
                BigDecimal toAfter = toBefore.add(request.toAmount());
                if (request.targetMaxBalance() != null && toAfter.compareTo(request.targetMaxBalance()) > 0) {
                    conn.rollback();
                    return new ExchangeMutationResult(MutationStatus.BALANCE_LIMIT, fromBefore, fromBefore, toBefore, toBefore, account.toRecord());
                }
                long version = advanceAccountVersion(conn, request.accountId(), account.version());
                upsertBalance(conn, request.accountId(), request.fromCurrencyId(), fromAfter, request.timestamp());
                upsertBalance(conn, request.accountId(), request.toCurrencyId(), toAfter, request.timestamp());
                updateAccountMetadata(conn, account, request.fromCurrencyId(), fromAfter, request.timestamp(), version);
                insertTransaction(conn, request.operationId(), TransactionType.TAKE, null, request.accountId(),
                        request.fromAmount(), fromBefore, fromAfter, request.timestamp(), request.source(),
                        "exchange:" + request.fromCurrencyId() + "->" + request.toCurrencyId(), request.fromCurrencyId());
                insertTransaction(conn, request.operationId(), TransactionType.GIVE, null, request.accountId(),
                        request.toAmount(), toBefore, toAfter, request.timestamp(), request.source(),
                        "exchange:" + request.fromCurrencyId() + "->" + request.toCurrencyId(), request.toCurrencyId());
                recordOperation(conn, request.operationId(), "EXCHANGE", request.timestamp());
                insertChange(conn, request.operationId(), request.accountId(), version, ChangeKind.UPSERT, request.timestamp());
                LockedAccount updated = account.withBalance(request.fromCurrencyId(), fromAfter, request.timestamp(), version)
                        .withBalance(request.toCurrencyId(), toAfter, request.timestamp(), version);
                conn.commit();
                return new ExchangeMutationResult(MutationStatus.SUCCESS, fromBefore, fromAfter, toBefore, toAfter, updated.toRecord());
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                restoreAutoCommit(conn);
            }
        }
    }

    @Override
    public AccountWriteResult createAccount(UUID operationId, UUID accountId, String name,
                                            Map<String, BigDecimal> balances, String primaryCurrencyId,
                                            long timestamp) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            beginAuthoritativeTransaction(conn);
            try {
                if (lockAccount(conn, accountId) != null) {
                    conn.rollback();
                    return new AccountWriteResult(MutationStatus.ALREADY_EXISTS, loadAccount(accountId).orElse(null));
                }
                long version = advanceAccountVersion(conn, accountId, 0L);
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO accounts(id,name,normalized_name,balance,created_at,updated_at,frozen,version) VALUES(?,?,?,?,?,?,?,?)")) {
                    ps.setString(1, accountId.toString());
                    ps.setString(2, name);
                    ps.setString(3, normalizeAccountName(name));
                    ps.setBigDecimal(4, balances.getOrDefault(primaryCurrencyId, BigDecimal.ZERO));
                    ps.setLong(5, timestamp);
                    ps.setLong(6, timestamp);
                    ps.setBoolean(7, false);
                    ps.setLong(8, version);
                    ps.executeUpdate();
                }
                for (Map.Entry<String, BigDecimal> balance : balances.entrySet()) {
                    upsertBalance(conn, accountId, balance.getKey(), balance.getValue(), timestamp);
                }
                recordOperation(conn, operationId, "ACCOUNT_CREATE", timestamp);
                insertChange(conn, operationId, accountId, version, ChangeKind.UPSERT, timestamp);
                AccountRecord created = new AccountRecord(
                        accountId, name, primaryCurrencyId, balances, timestamp, timestamp, version);
                conn.commit();
                return new AccountWriteResult(MutationStatus.SUCCESS, created);
            } catch (SQLException e) {
                conn.rollback();
                if (isConstraintViolation(e)) {
                    Optional<AccountRecord> existing = loadAccount(accountId);
                    return existing.isPresent()
                            ? new AccountWriteResult(MutationStatus.ALREADY_EXISTS, existing.get())
                            : new AccountWriteResult(MutationStatus.NAME_IN_USE, null);
                }
                throw e;
            } finally {
                restoreAutoCommit(conn);
            }
        }
    }

    @Override
    public AccountWriteResult renameAccount(UUID operationId, UUID accountId, String newName,
                                            long timestamp) throws SQLException {
        return mutateAccountMetadata(operationId, accountId, timestamp, "ACCOUNT_RENAME", (conn, account, version) -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE accounts SET name=?,normalized_name=?,updated_at=?,version=? WHERE id=?")) {
                ps.setString(1, newName);
                ps.setString(2, normalizeAccountName(newName));
                ps.setLong(3, timestamp);
                ps.setLong(4, version);
                ps.setString(5, accountId.toString());
                ps.executeUpdate();
            }
            return account.withName(newName, timestamp, version);
        });
    }

    @Override
    public AccountWriteResult setFrozen(UUID operationId, UUID accountId, boolean frozen,
                                        long timestamp) throws SQLException {
        return mutateAccountMetadata(operationId, accountId, timestamp, frozen ? "FREEZE" : "UNFREEZE", (conn, account, version) -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE accounts SET frozen=?,updated_at=?,version=? WHERE id=?")) {
                ps.setBoolean(1, frozen);
                ps.setLong(2, timestamp);
                ps.setLong(3, version);
                ps.setString(4, accountId.toString());
                ps.executeUpdate();
            }
            return account.withFrozen(frozen, timestamp, version);
        });
    }

    @Override
    public AccountWriteResult deleteAccount(UUID operationId, UUID accountId, long timestamp) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            beginAuthoritativeTransaction(conn);
            try {
                LockedAccount account = lockAccount(conn, accountId);
                if (account == null) {
                    conn.rollback();
                    return new AccountWriteResult(MutationStatus.ACCOUNT_NOT_FOUND, null);
                }
                try (PreparedStatement tx = conn.prepareStatement("DELETE FROM transactions WHERE target_id=?");
                     PreparedStatement balances = conn.prepareStatement("DELETE FROM account_balances WHERE account_id=?");
                     PreparedStatement pay = conn.prepareStatement("DELETE FROM account_pay_state WHERE account_id=?");
                     PreparedStatement acc = conn.prepareStatement("DELETE FROM accounts WHERE id=?")) {
                    for (PreparedStatement ps : List.of(tx, balances, pay, acc)) ps.setString(1, accountId.toString());
                    tx.executeUpdate(); balances.executeUpdate(); pay.executeUpdate(); acc.executeUpdate();
                }
                long tombstoneVersion = advanceAccountVersion(conn, accountId, account.version());
                recordOperation(conn, operationId, "ACCOUNT_DELETE", timestamp);
                insertChange(conn, operationId, accountId, tombstoneVersion, ChangeKind.DELETE, timestamp);
                conn.commit();
                return new AccountWriteResult(MutationStatus.SUCCESS,
                        account.withVersion(tombstoneVersion).toRecord());
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                restoreAutoCommit(conn);
            }
        }
    }

    @Override
    public long currentChangeSequence() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT MAX(seq) FROM account_changes");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    @Override
    public List<AccountChange> loadChangesAfter(long sequence, int limit) throws SQLException {
        List<AccountChange> changes = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT seq,account_id,account_version,change_kind FROM account_changes WHERE seq>? ORDER BY seq ASC LIMIT ?")) {
            ps.setLong(1, sequence);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    changes.add(new AccountChange(rs.getLong(1), UUID.fromString(rs.getString(2)),
                            rs.getLong(3), ChangeKind.valueOf(rs.getString(4))));
                }
            }
        }
        return changes;
    }

    @Override
    public Map<UUID, Optional<AccountRecord>> loadAccounts(Iterable<UUID> ids) throws SQLException {
        Map<UUID, Optional<AccountRecord>> result = new LinkedHashMap<>();
        for (UUID id : ids) {
            if (id != null) result.putIfAbsent(id, Optional.empty());
        }
        if (result.isEmpty()) return result;
        List<UUID> accountIds = List.copyOf(result.keySet());
        try (Connection conn = dataSource.getConnection()) {
            for (int start = 0; start < accountIds.size(); start += 500) {
                loadAccountChunk(conn,
                        accountIds.subList(start, Math.min(accountIds.size(), start + 500)), result);
            }
        }
        return result;
    }

    private void loadAccountChunk(Connection conn, List<UUID> accountIds,
                                  Map<UUID, Optional<AccountRecord>> result) throws SQLException {
        String placeholders = String.join(",", java.util.Collections.nCopies(accountIds.size(), "?"));
        String sql = "SELECT a.id AS account_id,a.name AS account_name,"
                + "a.created_at AS account_created_at,a.updated_at AS account_updated_at,"
                + "a.frozen AS account_frozen,a.version AS account_version,"
                + "b.currency_id AS balance_currency_id,b.balance AS balance_value,b.updated_at AS balance_updated_at "
                + "FROM accounts a LEFT JOIN account_balances b ON b.account_id=a.id "
                + "WHERE a.id IN (" + placeholders + ") ORDER BY a.id,b.currency_id";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < accountIds.size(); i++) {
                ps.setString(i + 1, accountIds.get(i).toString());
            }
            try (ResultSet rs = ps.executeQuery()) {
                UUID currentId = null;
                PersistedAccountRow currentAccount = null;
                Map<String, PersistedBalanceRow> balances = new LinkedHashMap<>();
                while (rs.next()) {
                    UUID rowId = UUID.fromString(rs.getString("account_id"));
                    if (currentId != null && !currentId.equals(rowId)) {
                        result.put(currentId, Optional.of(buildScannedRecord(
                                currentId, currentAccount, balances)));
                        balances = new LinkedHashMap<>();
                    }
                    if (!rowId.equals(currentId)) {
                        currentId = rowId;
                        currentAccount = new PersistedAccountRow(
                                rs.getString("account_name"),
                                rs.getLong("account_created_at"),
                                rs.getLong("account_updated_at"),
                                rs.getBoolean("account_frozen"),
                                rs.getLong("account_version"));
                    }
                    addPersistedBalance(rs, balances);
                }
                if (currentId != null) {
                    result.put(currentId, Optional.of(buildScannedRecord(
                            currentId, currentAccount, balances)));
                }
            }
        }
    }

    @Override
    public int pruneAccountChanges(long cutoffMs) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            long latestSequence;
            try (Statement statement = conn.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT MAX(seq) FROM account_changes")) {
                latestSequence = rs.next() ? rs.getLong(1) : 0L;
            }
            if (latestSequence <= 0L) return 0;
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM account_changes WHERE changed_at<? AND seq<?")) {
                ps.setLong(1, cutoffMs);
                ps.setLong(2, latestSequence);
                return ps.executeUpdate();
            }
        }
    }

    @Override
    public boolean tryAcquireJobLease(String jobId, String runId, String ownerId,
                                      long now, long leaseUntil) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            beginAuthoritativeTransaction(conn);
            try {
                try (PreparedStatement select = conn.prepareStatement(
                        "SELECT lease_until,completed FROM economy_cluster_jobs WHERE job_id=? AND run_id=? FOR UPDATE")) {
                    select.setString(1, jobId);
                    select.setString(2, runId);
                    try (ResultSet rs = select.executeQuery()) {
                        if (rs.next()) {
                            if (rs.getBoolean(2) || rs.getLong(1) > now) {
                                conn.rollback();
                                return false;
                            }
                            try (PreparedStatement update = conn.prepareStatement(
                                    "UPDATE economy_cluster_jobs SET lease_owner=?,lease_until=? WHERE job_id=? AND run_id=?")) {
                                update.setString(1, ownerId); update.setLong(2, leaseUntil);
                                update.setString(3, jobId); update.setString(4, runId); update.executeUpdate();
                            }
                            conn.commit();
                            return true;
                        }
                    }
                }
                try (PreparedStatement insert = conn.prepareStatement(
                        "INSERT INTO economy_cluster_jobs(job_id,run_id,lease_owner,lease_until,completed) VALUES(?,?,?,?,?)")) {
                    insert.setString(1, jobId); insert.setString(2, runId); insert.setString(3, ownerId);
                    insert.setLong(4, leaseUntil); insert.setBoolean(5, false); insert.executeUpdate();
                }
                conn.commit();
                return true;
            } catch (SQLException e) {
                conn.rollback();
                if (isConstraintViolation(e)) return false;
                throw e;
            } finally { restoreAutoCommit(conn); }
        }
    }

    @Override
    public void completeJobLease(String jobId, String runId, String ownerId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE economy_cluster_jobs SET completed=? WHERE job_id=? AND run_id=? AND lease_owner=?")) {
            ps.setBoolean(1, true); ps.setString(2, jobId); ps.setString(3, runId); ps.setString(4, ownerId);
            ps.executeUpdate();
        }
    }

    private AccountWriteResult mutateAccountMetadata(UUID operationId, UUID accountId, long timestamp,
                                                     String kind, AccountMetadataMutation mutation) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            beginAuthoritativeTransaction(conn);
            try {
                LockedAccount account = lockAccount(conn, accountId);
                if (account == null) {
                    conn.rollback();
                    return new AccountWriteResult(MutationStatus.ACCOUNT_NOT_FOUND, null);
                }
                long version = advanceAccountVersion(conn, accountId, account.version());
                LockedAccount updated = mutation.apply(conn, account, version);
                recordOperation(conn, operationId, kind, timestamp);
                insertChange(conn, operationId, accountId, version, ChangeKind.UPSERT, timestamp);
                conn.commit();
                return new AccountWriteResult(MutationStatus.SUCCESS, updated.toRecord());
            } catch (SQLException e) {
                conn.rollback();
                if (isConstraintViolation(e)) return new AccountWriteResult(MutationStatus.NAME_IN_USE, null);
                throw e;
            } finally {
                restoreAutoCommit(conn);
            }
        }
    }

    private void beginAuthoritativeTransaction(Connection conn) throws SQLException {
        conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        conn.setAutoCommit(false);
    }

    private static void restoreAutoCommit(Connection conn) throws SQLException {
        conn.setAutoCommit(true);
    }

    private LockedAccount lockAccount(Connection conn, UUID id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT name,created_at,updated_at,frozen,version FROM accounts WHERE id=? FOR UPDATE")) {
            ps.setString(1, id.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new LockedAccount(id, rs.getString(1), defaultCurrencyId, rs.getLong(2), rs.getLong(3),
                        rs.getBoolean(4), rs.getLong(5), loadBalances(conn, id));
            }
        }
    }

    private long advanceAccountVersion(Connection conn, UUID accountId, long currentVersion) throws SQLException {
        long storedVersion = -1L;
        try (PreparedStatement select = conn.prepareStatement(
                "SELECT version FROM account_versions WHERE account_id=? FOR UPDATE")) {
            select.setString(1, accountId.toString());
            try (ResultSet rs = select.executeQuery()) {
                if (rs.next()) storedVersion = rs.getLong(1);
            }
        }
        if (storedVersion < 0L) {
            try (PreparedStatement insert = conn.prepareStatement(
                    "INSERT INTO account_versions(account_id,version) VALUES(?,?)")) {
                insert.setString(1, accountId.toString());
                insert.setLong(2, Math.max(0L, currentVersion));
                insert.executeUpdate();
                storedVersion = Math.max(0L, currentVersion);
            }
        }
        long version = Math.max(storedVersion, currentVersion) + 1L;
        try (PreparedStatement update = conn.prepareStatement(
                "UPDATE account_versions SET version=? WHERE account_id=?")) {
            update.setLong(1, version);
            update.setString(2, accountId.toString());
            update.executeUpdate();
        }
        return version;
    }

    private Map<String, BigDecimal> loadBalances(Connection conn, UUID accountId) throws SQLException {
        Map<String, PersistedBalanceRow> balanceRows = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT currency_id,balance,updated_at FROM account_balances WHERE account_id=?")) {
            ps.setString(1, accountId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String currencyId = normalizePersistedCurrencyId(rs.getString("currency_id"));
                    PersistedBalanceRow candidate = new PersistedBalanceRow(
                            currencyId, rs.getBigDecimal("balance"), rs.getLong("updated_at"));
                    String lookupKey = normalizeCurrencyLookupKey(currencyId);
                    PersistedBalanceRow existing = balanceRows.get(lookupKey);
                    if (existing == null || candidate.updatedAt() >= existing.updatedAt()) {
                        balanceRows.put(lookupKey, candidate);
                    }
                }
            }
        }

        Map<String, BigDecimal> balances = new LinkedHashMap<>();
        for (PersistedBalanceRow row : balanceRows.values()) {
            balances.put(row.currencyId(), row.balance());
        }
        return balances;
    }

    private void upsertBalance(Connection conn, UUID id, String currencyId, BigDecimal balance, long timestamp) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(dialect.balanceUpsertSql())) {
            ps.setString(1, id.toString());
            ps.setString(2, currencyId);
            ps.setBigDecimal(3, balance);
            ps.setLong(4, timestamp);
            ps.executeUpdate();
        }
    }

    private void updateAccountMetadata(Connection conn, LockedAccount account, String currencyId,
                                       BigDecimal balance, long timestamp, long version) throws SQLException {
        boolean primary = defaultCurrencyId.equalsIgnoreCase(currencyId);
        String sql = primary
                ? "UPDATE accounts SET balance=?,updated_at=?,version=? WHERE id=?"
                : "UPDATE accounts SET updated_at=?,version=? WHERE id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            if (primary) ps.setBigDecimal(i++, balance);
            ps.setLong(i++, timestamp);
            ps.setLong(i++, version);
            ps.setString(i, account.id().toString());
            ps.executeUpdate();
        }
    }

    private void insertTransaction(Connection conn, UUID operationId, TransactionType type, @Nullable UUID counterpart,
                                   UUID target, BigDecimal amount, BigDecimal before, BigDecimal after, long timestamp,
                                   @Nullable String source, @Nullable String note, String currencyId) throws SQLException {
        long persistedTimestamp = resolvePersistedTimestamp(conn, target, timestamp);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO transactions(type,counterpart_id,target_id,amount,balance_before,balance_after,ts,source,note,currency_id,operation_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, type.name());
            if (counterpart == null) ps.setNull(2, Types.VARCHAR); else ps.setString(2, counterpart.toString());
            ps.setString(3, target.toString());
            ps.setBigDecimal(4, amount);
            ps.setBigDecimal(5, before);
            ps.setBigDecimal(6, after);
            ps.setLong(7, persistedTimestamp);
            if (source == null) ps.setNull(8, Types.VARCHAR); else ps.setString(8, source);
            if (note == null) ps.setNull(9, Types.VARCHAR); else ps.setString(9, note);
            ps.setString(10, currencyId);
            ps.setString(11, operationId.toString());
            ps.executeUpdate();
        }
    }

    private void recordOperation(Connection conn, UUID operationId, String kind, long timestamp) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO economy_operations(operation_id,kind,created_at) VALUES(?,?,?)")) {
            ps.setString(1, operationId.toString());
            ps.setString(2, kind);
            ps.setLong(3, timestamp);
            ps.executeUpdate();
        }
    }

    private boolean operationExists(Connection conn, UUID operationId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM economy_operations WHERE operation_id=?")) {
            ps.setString(1, operationId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void insertChange(Connection conn, UUID operationId, UUID accountId, long version,
                              ChangeKind kind, long timestamp) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO account_changes(account_id,account_version,change_kind,operation_id,changed_at) VALUES(?,?,?,?,?)")) {
            ps.setString(1, accountId.toString());
            ps.setLong(2, version);
            ps.setString(3, kind.name());
            ps.setString(4, operationId.toString());
            ps.setLong(5, timestamp);
            ps.executeUpdate();
        }
    }

    private long loadLastPayAt(Connection conn, UUID id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT last_pay_at FROM account_pay_state WHERE account_id=?")) {
            ps.setString(1, id.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private void upsertLastPayAt(Connection conn, UUID id, long timestamp) throws SQLException {
        int updated;
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE account_pay_state SET last_pay_at=? WHERE account_id=?")) {
            ps.setLong(1, timestamp);
            ps.setString(2, id.toString());
            updated = ps.executeUpdate();
        }
        if (updated == 0) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO account_pay_state(account_id,last_pay_at) VALUES(?,?)")) {
                ps.setString(1, id.toString());
                ps.setLong(2, timestamp);
                ps.executeUpdate();
            }
        }
    }

    private BigDecimal loadPolicyUsage(Connection conn, String providerId, UUID subjectId,
                                       String currencyId, long cutoff) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT SUM(amount) FROM economy_policy_usage WHERE provider_id=? AND subject_id=? AND currency_id=? AND occurred_at>=?")) {
            ps.setString(1, providerId);
            ps.setString(2, subjectId.toString());
            ps.setString(3, currencyId);
            ps.setLong(4, cutoff);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return BigDecimal.ZERO;
                BigDecimal value = rs.getBigDecimal(1);
                return value == null ? BigDecimal.ZERO : value;
            }
        }
    }

    private void insertPolicyUsage(Connection conn, String providerId, UUID subjectId, String currencyId,
                                   UUID operationId, BigDecimal amount, long timestamp) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO economy_policy_usage(provider_id,subject_id,currency_id,operation_id,amount,occurred_at) VALUES(?,?,?,?,?,?)")) {
            ps.setString(1, providerId);
            ps.setString(2, subjectId.toString());
            ps.setString(3, currencyId);
            ps.setString(4, operationId.toString());
            ps.setBigDecimal(5, amount);
            ps.setLong(6, timestamp);
            ps.executeUpdate();
        }
    }

    private TransferMutationResult transferFailure(TransferMutationRequest request, MutationStatus status,
                                                   long remaining, @Nullable LockedAccount from,
                                                   @Nullable LockedAccount to) {
        return new TransferMutationResult(status, request.sent(), request.received(), request.tax(), remaining,
                from == null ? null : from.toRecord(), to == null ? null : to.toRecord());
    }

    @FunctionalInterface
    private interface AccountMetadataMutation {
        LockedAccount apply(Connection conn, LockedAccount account, long version) throws SQLException;
    }

    private record LockedAccount(UUID id, String name, String primaryCurrencyId, long createdAt, long updatedAt,
                                 boolean frozen, long version, Map<String, BigDecimal> balances) {
        BigDecimal balance(String currencyId) {
            return balances.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(currencyId))
                    .map(Map.Entry::getValue).findFirst().orElse(BigDecimal.ZERO);
        }

        LockedAccount withBalance(String currencyId, BigDecimal balance, long timestamp, long newVersion) {
            Map<String, BigDecimal> updated = new LinkedHashMap<>(balances);
            updated.keySet().removeIf(key -> key.equalsIgnoreCase(currencyId));
            updated.put(currencyId, balance);
            return new LockedAccount(id, name, primaryCurrencyId, createdAt, timestamp, frozen, newVersion, updated);
        }

        LockedAccount withName(String newName, long timestamp, long newVersion) {
            return new LockedAccount(id, newName, primaryCurrencyId, createdAt, timestamp, frozen, newVersion, balances);
        }

        LockedAccount withFrozen(boolean newFrozen, long timestamp, long newVersion) {
            return new LockedAccount(id, name, primaryCurrencyId, createdAt, timestamp, newFrozen, newVersion, balances);
        }

        LockedAccount withVersion(long newVersion) {
            return new LockedAccount(id, name, primaryCurrencyId, createdAt, updatedAt, frozen, newVersion, balances);
        }

        AccountRecord toRecord() {
            AccountRecord record = new AccountRecord(id, name, primaryCurrencyId, balances, createdAt, updatedAt, version);
            record.setFrozen(frozen);
            record.clearDirty();
            return record;
        }
    }

    private static void bindAccount(PreparedStatement ps, AccountRecord record) throws SQLException {
        ps.setString(1, record.getId().toString());
        ps.setString(2, record.getLastKnownName());
        ps.setString(3, normalizeAccountName(record.getLastKnownName()));
        ps.setBigDecimal(4, record.getBalance());
        ps.setLong(5, record.getCreatedAt());
        ps.setLong(6, record.getUpdatedAt());
        ps.setBoolean(7, record.isFrozen());
    }

    private static String normalizeAccountName(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isConstraintViolation(SQLException exception) {
        String state = exception.getSQLState();
        return (state != null && state.startsWith("23"))
                || exception.getErrorCode() == 19; // SQLite SQLITE_CONSTRAINT
    }

    @Override
    public void delete(UUID accountId) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement deleteTransactions = conn.prepareStatement(
                         "DELETE FROM transactions WHERE target_id=?");
                 PreparedStatement deleteBalances = conn.prepareStatement(
                     "DELETE FROM account_balances WHERE account_id=?");
                 PreparedStatement deleteAccount = conn.prepareStatement(
                         "DELETE FROM accounts WHERE id=?")) {
                deleteTransactions.setString(1, accountId.toString());
                deleteTransactions.executeUpdate();

                deleteBalances.setString(1, accountId.toString());
                deleteBalances.executeUpdate();

                deleteAccount.setString(1, accountId.toString());
                deleteAccount.executeUpdate();

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    @Override
    public void close() throws SQLException {
        dataSource.close();
    }

    public DatabaseDialect dialect() {
        return dialect;
    }

    public int countTransactions() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM transactions")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    public void clearAllData() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM transactions");
                stmt.execute("DELETE FROM account_balances");
                stmt.execute("DELETE FROM accounts");
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    public List<TransactionEntry> loadTransactions(int limit, long offset) throws SQLException {
        String sql = "SELECT type,counterpart_id,target_id,amount,balance_before,balance_after,ts,source,note,currency_id "
                + "FROM transactions ORDER BY ts ASC LIMIT ? OFFSET ?";
        List<TransactionEntry> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            ps.setLong(2, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapRow(rs));
                }
            }
        }
        return result;
    }

    public void insertTransactionsBatch(List<TransactionEntry> entries) throws SQLException {
        if (entries.isEmpty()) {
            return;
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO transactions(type,counterpart_id,target_id,amount,balance_before,balance_after,ts,source,note,currency_id) "
                             + "VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            for (TransactionEntry entry : entries) {
                ps.setString(1, entry.getType().name());
                if (entry.getCounterpartId() != null) {
                    ps.setString(2, entry.getCounterpartId().toString());
                } else {
                    ps.setNull(2, Types.VARCHAR);
                }
                ps.setString(3, entry.getTargetId().toString());
                ps.setBigDecimal(4, entry.getAmount());
                ps.setBigDecimal(5, entry.getBalanceBefore());
                ps.setBigDecimal(6, entry.getBalanceAfter());
                ps.setLong(7, entry.getTimestamp());
                if (entry.getSource() != null) {
                    ps.setString(8, entry.getSource());
                } else {
                    ps.setNull(8, Types.VARCHAR);
                }
                if (entry.getNote() != null) {
                    ps.setString(9, entry.getNote());
                } else {
                    ps.setNull(9, Types.VARCHAR);
                }
                ps.setString(10, normalizePersistedCurrencyId(
                        entry.getCurrencyId() != null ? entry.getCurrencyId() : defaultCurrencyId));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // ── TransactionRepository ─────────────────────────────────────────────────

    @Override
    public synchronized void insertTransaction(TransactionEntry entry) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            long persistedTimestamp = resolvePersistedTimestamp(conn, entry.getTargetId(), entry.getTimestamp());
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO transactions(type,counterpart_id,target_id,amount,balance_before,balance_after,ts,source,note,currency_id) "
                  + "VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, entry.getType().name());
            if (entry.getCounterpartId() != null) {
                ps.setString(2, entry.getCounterpartId().toString());
            } else {
                ps.setNull(2, Types.VARCHAR);
            }
            ps.setString(3, entry.getTargetId().toString());
            ps.setBigDecimal(4, entry.getAmount());
            ps.setBigDecimal(5, entry.getBalanceBefore());
            ps.setBigDecimal(6, entry.getBalanceAfter());
            ps.setLong(7, persistedTimestamp);
            if (entry.getSource() != null) {
                ps.setString(8, entry.getSource());
            } else {
                ps.setNull(8, Types.VARCHAR);
            }
            if (entry.getNote() != null) {
                ps.setString(9, entry.getNote());
            } else {
                ps.setNull(9, Types.VARCHAR);
            }
                ps.setString(10, normalizePersistedCurrencyId(
                        entry.getCurrencyId() != null ? entry.getCurrencyId() : defaultCurrencyId));
                ps.executeUpdate();
            }
        }
    }

    private void backfillDefaultBalances(Connection conn) throws SQLException {
        try (PreparedStatement selectAccounts = conn.prepareStatement(
                     "SELECT id,balance,updated_at FROM accounts");
             PreparedStatement hasAnyBalance = conn.prepareStatement(
                     "SELECT 1 FROM account_balances WHERE account_id=?");
             PreparedStatement insertBalance = conn.prepareStatement(
                     "INSERT INTO account_balances(account_id,currency_id,balance,updated_at) VALUES(?,?,?,?)")) {
            try (ResultSet rs = selectAccounts.executeQuery()) {
                while (rs.next()) {
                    String accountId = rs.getString("id");
                    hasAnyBalance.setString(1, accountId);
                    try (ResultSet existing = hasAnyBalance.executeQuery()) {
                        if (existing.next()) {
                            continue;
                        }
                    }

                    insertBalance.setString(1, accountId);
                    insertBalance.setString(2, defaultCurrencyId);
                    insertBalance.setBigDecimal(3, rs.getBigDecimal("balance"));
                    insertBalance.setLong(4, rs.getLong("updated_at"));
                    insertBalance.addBatch();
                }
            }
            insertBalance.executeBatch();
        }
    }

    private void backfillTransactionCurrencies(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE transactions SET currency_id=? WHERE currency_id IS NULL OR TRIM(currency_id) = ''")) {
            ps.setString(1, defaultCurrencyId);
            ps.executeUpdate();
        }
    }

    @Override
    public List<TransactionEntry> getTransactions(UUID targetId, int limit, int offset)
            throws SQLException {
        return getTransactions(targetId, limit, offset, null, 0L, Long.MAX_VALUE, null);
    }

    @Override
    public List<TransactionEntry> getTransactions(UUID targetId, int limit, int offset,
            @Nullable String currencyId) throws SQLException {
        return getTransactions(targetId, limit, offset, null, 0L, Long.MAX_VALUE, currencyId);
    }

    @Override
    public int countTransactions(UUID targetId) throws SQLException {
        return countTransactions(targetId, null, 0L, Long.MAX_VALUE, null);
    }

    @Override
    public int countTransactions(UUID targetId, @Nullable String currencyId) throws SQLException {
        return countTransactions(targetId, null, 0L, Long.MAX_VALUE, currencyId);
    }

    @Override
    public List<TransactionEntry> getTransactions(UUID targetId, int limit, int offset,
            @Nullable TransactionType type, long fromMs, long toMs) throws SQLException {
        return getTransactions(targetId, limit, offset, type, fromMs, toMs, null);
    }

    @Override
    public List<TransactionEntry> getTransactions(UUID targetId, int limit, int offset,
            @Nullable TransactionType type, long fromMs, long toMs, @Nullable String currencyId) throws SQLException {
        String sql = buildFilteredSql(
                "SELECT type,counterpart_id,target_id,amount,balance_before,balance_after,ts,source,note,currency_id "
                + "FROM transactions", targetId, type, fromMs, toMs, currencyId)
            + " ORDER BY ts DESC, amount DESC, balance_after DESC, balance_before DESC, type DESC,"
            + " COALESCE(counterpart_id, '') DESC, COALESCE(source, '') DESC, COALESCE(note, '') DESC"
            + " LIMIT ? OFFSET ?";
        List<TransactionEntry> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = bindFilterParams(ps, targetId, type, fromMs, toMs, currencyId, 1);
            ps.setInt(idx++, limit);
            ps.setInt(idx, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapRow(rs));
                }
            }
        }
        return result;
    }

    @Override
    public int countTransactions(UUID targetId, @Nullable TransactionType type,
            long fromMs, long toMs) throws SQLException {
        return countTransactions(targetId, type, fromMs, toMs, null);
    }

    @Override
    public int countTransactions(UUID targetId, @Nullable TransactionType type,
            long fromMs, long toMs, @Nullable String currencyId) throws SQLException {
        String sql = buildFilteredSql("SELECT COUNT(*) FROM transactions",
                targetId, type, fromMs, toMs, currencyId);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bindFilterParams(ps, targetId, type, fromMs, toMs, currencyId, 1);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private static String buildFilteredSql(String select, UUID targetId,
            @Nullable TransactionType type, long fromMs, long toMs, @Nullable String currencyId) {
        StringBuilder sb = new StringBuilder(select)
                .append(" WHERE target_id=?");
        if (type != null) sb.append(" AND type=?");
        if (fromMs > 0) sb.append(" AND ts>=?");
        if (toMs < Long.MAX_VALUE) sb.append(" AND ts<=?");
        if (currencyId != null && !currencyId.isBlank()) sb.append(" AND LOWER(currency_id)=?");
        return sb.toString();
    }

    private static int bindFilterParams(PreparedStatement ps, UUID targetId,
            @Nullable TransactionType type, long fromMs, long toMs,
            @Nullable String currencyId, int startIdx) throws SQLException {
        int idx = startIdx;
        ps.setString(idx++, targetId.toString());
        if (type != null) ps.setString(idx++, type.name());
        if (fromMs > 0) ps.setLong(idx++, fromMs);
        if (toMs < Long.MAX_VALUE) ps.setLong(idx++, toMs);
        if (currencyId != null && !currencyId.isBlank()) ps.setString(idx++, normalizeCurrencyLookupKey(currencyId));
        return idx;
    }

    private static TransactionEntry mapRow(ResultSet rs) throws SQLException {
        TransactionType type = TransactionType.valueOf(rs.getString("type"));
        String cpStr = rs.getString("counterpart_id");
        UUID counterpartId = cpStr != null ? UUID.fromString(cpStr) : null;
        UUID tgtId = UUID.fromString(rs.getString("target_id"));
        BigDecimal amount = rs.getBigDecimal("amount");
        BigDecimal before = rs.getBigDecimal("balance_before");
        BigDecimal after = rs.getBigDecimal("balance_after");
        long ts = rs.getLong("ts");
        String source = rs.getString("source");
        String note = rs.getString("note");
        String currencyId = null;
        try {
            currencyId = rs.getString("currency_id");
        } catch (SQLException ignored) {
            // Legacy queries/tests without the new column in the select list still map correctly.
        }
        return new TransactionEntry(type, counterpartId, tgtId, amount, before, after, ts, source, note, currencyId);
    }

    @Override
    public int pruneTransactions(long cutoffMs) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM transactions WHERE ts < ?")) {
            ps.setLong(1, cutoffMs);
            return ps.executeUpdate();
        }
    }

    private static String normalizeCurrencyId(String currencyId) {
        if (currencyId == null) {
            return "openeco";
        }
        String trimmed = currencyId.trim();
        return trimmed.isEmpty() ? "openeco" : trimmed;
    }

    private String normalizePersistedCurrencyId(String currencyId) {
        if (currencyId == null) {
            return defaultCurrencyId;
        }
        String trimmed = currencyId.trim();
        return trimmed.isEmpty() ? defaultCurrencyId : trimmed;
    }

    private static String normalizeCurrencyLookupKey(String currencyId) {
        return currencyId.trim().toLowerCase(Locale.ROOT);
    }

    private static long resolvePersistedTimestamp(Connection conn, UUID targetId, long requestedTimestamp) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT MAX(ts) FROM transactions WHERE target_id=?")) {
            ps.setString(1, targetId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return requestedTimestamp;
                }
                long latestTimestamp = rs.getLong(1);
                if (rs.wasNull() || latestTimestamp < requestedTimestamp) {
                    return requestedTimestamp;
                }
                return latestTimestamp == Long.MAX_VALUE ? Long.MAX_VALUE : latestTimestamp + 1;
            }
        }
    }

    private record PersistedAccountRow(String name, long createdAt, long updatedAt, boolean frozen, long version) {}

    private record PersistedBalanceRow(String currencyId, BigDecimal balance, long updatedAt) {}

    private static String sqlLiteral(String value) {
        return value.replace("'", "''");
    }
}
