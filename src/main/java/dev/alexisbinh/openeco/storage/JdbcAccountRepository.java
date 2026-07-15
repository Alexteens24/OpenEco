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

public class JdbcAccountRepository implements AccountRepository {

    private static final int ACCOUNT_SCAN_FETCH_SIZE = 1_000;
    private static final String ACCOUNT_SCAN_SQL = """
            SELECT a.id AS account_id,
                   a.name AS account_name,
                   a.created_at AS account_created_at,
                   a.updated_at AS account_updated_at,
                   a.frozen AS account_frozen,
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
                ensureColumn(conn, stmt, "accounts", "normalized_name", "VARCHAR(16)");
                stmt.executeUpdate("UPDATE accounts SET normalized_name=LOWER(TRIM(name)) "
                        + "WHERE normalized_name IS NULL OR normalized_name=''");
                failOnDuplicateNormalizedNames(conn);
                createIndexIfMissing(conn, stmt, "accounts", "ux_accounts_normalized_name",
                        uniqueNormalizedNameIndexSql());
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
                                rs.getBoolean("account_frozen"));
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
                id, account.name(), defaultCurrencyId, balances, account.createdAt(), account.updatedAt());
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
        try (Connection conn = dataSource.getConnection()) {
            PersistedAccountRow account = null;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT name,created_at,updated_at,frozen FROM accounts WHERE id=?")) {
                ps.setString(1, id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        account = new PersistedAccountRow(
                                rs.getString("name"),
                                rs.getLong("created_at"),
                                rs.getLong("updated_at"),
                                rs.getBoolean("frozen"));
                    }
                }
            }
            if (account == null) {
                return Optional.empty();
            }
            return Optional.of(buildRecord(conn, id, account));
        }
    }

    @Override
    public Optional<AccountRecord> loadAccountByName(String normalizedName) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id,name,created_at,updated_at,frozen FROM accounts WHERE normalized_name=?")) {
            ps.setString(1, normalizeAccountName(normalizedName));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                UUID id = UUID.fromString(rs.getString("id"));
                PersistedAccountRow row = new PersistedAccountRow(
                        rs.getString("name"), rs.getLong("created_at"), rs.getLong("updated_at"), rs.getBoolean("frozen"));
                return Optional.of(buildRecord(conn, id, row));
            }
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
        int total = countAccounts();
        List<LeaderboardEntry> entries = new ArrayList<>(safeLimit);
        String sql = "SELECT a.id,a.name,COALESCE(b.balance,0) AS leaderboard_balance "
                + "FROM accounts a LEFT JOIN account_balances b "
                + "ON b.account_id=a.id AND LOWER(b.currency_id)=LOWER(?) "
                + "ORDER BY leaderboard_balance DESC,LOWER(a.name),a.id LIMIT ? OFFSET ?";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
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
        return new LeaderboardView(total, entries);
    }

    @Override
    public int loadLeaderboardRank(String currencyId, UUID accountId) throws SQLException {
        String sql = "SELECT ranked_position FROM ("
                + "SELECT a.id,ROW_NUMBER() OVER (ORDER BY COALESCE(b.balance,0) DESC,LOWER(a.name),a.id) AS ranked_position "
                + "FROM accounts a LEFT JOIN account_balances b "
                + "ON b.account_id=a.id AND LOWER(b.currency_id)=LOWER(?)"
                + ") ranked WHERE id=?";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, currencyId);
            ps.setString(2, accountId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    private AccountRecord buildRecord(Connection conn, UUID id, PersistedAccountRow account) throws SQLException {
        Map<String, BigDecimal> balances = loadBalances(conn, id);
        if (balances.isEmpty()) {
            balances.put(defaultCurrencyId, BigDecimal.ZERO);
        }

        AccountRecord record = new AccountRecord(
                id,
                account.name(),
                defaultCurrencyId,
                balances,
                account.createdAt(),
                account.updatedAt());
        record.setFrozen(account.frozen());
        record.clearDirty();
        return record;
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
                            currencyId,
                            rs.getBigDecimal("balance"),
                            rs.getLong("updated_at"));
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

    private static String normalizeNameLookupKey(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
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

    private record PersistedAccountRow(String name, long createdAt, long updatedAt, boolean frozen) {}

    private record PersistedBalanceRow(String currencyId, BigDecimal balance, long updatedAt) {}

    private static String sqlLiteral(String value) {
        return value.replace("'", "''");
    }
}
