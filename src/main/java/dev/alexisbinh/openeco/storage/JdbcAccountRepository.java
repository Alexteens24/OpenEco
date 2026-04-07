package dev.alexisbinh.openeco.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.alexisbinh.openeco.model.AccountRecord;
import dev.alexisbinh.openeco.model.TransactionEntry;
import dev.alexisbinh.openeco.model.TransactionType;
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
        createSchema();
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
                        balance    DECIMAL(30,8) NOT NULL DEFAULT 0,
                        created_at BIGINT        NOT NULL,
                        updated_at BIGINT        NOT NULL
                    )
                    """);
                stmt.execute(dialect.createNameIndexSql());
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
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_tx_target_ts ON transactions(target_id, ts DESC)");
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
    public List<AccountRecord> loadAll() throws SQLException {
        Map<UUID, PersistedAccountRow> accounts = new LinkedHashMap<>();
        Map<UUID, Map<String, PersistedBalanceRow>> balancesByAccount = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection()) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT id,name,created_at,updated_at,frozen FROM accounts")) {
                while (rs.next()) {
                    UUID id = UUID.fromString(rs.getString("id"));
                    accounts.put(id, new PersistedAccountRow(
                            rs.getString("name"),
                            rs.getLong("created_at"),
                            rs.getLong("updated_at"),
                            rs.getBoolean("frozen")));
                }
            }

            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT account_id,currency_id,balance,updated_at FROM account_balances")) {
                while (rs.next()) {
                    UUID accountId = UUID.fromString(rs.getString("account_id"));
                    if (!accounts.containsKey(accountId)) {
                        continue;
                    }
                    String currencyId = normalizePersistedCurrencyId(rs.getString("currency_id"));
                    PersistedBalanceRow candidate = new PersistedBalanceRow(
                            currencyId,
                            rs.getBigDecimal("balance"),
                            rs.getLong("updated_at"));
                    Map<String, PersistedBalanceRow> accountBalances = balancesByAccount.computeIfAbsent(
                            accountId,
                            ignored -> new LinkedHashMap<>());
                    String lookupKey = normalizeCurrencyLookupKey(currencyId);
                    PersistedBalanceRow existing = accountBalances.get(lookupKey);
                    if (existing == null || candidate.updatedAt() >= existing.updatedAt()) {
                        accountBalances.put(lookupKey, candidate);
                    }
                }
            }
        }

        List<AccountRecord> result = new ArrayList<>(accounts.size());
        for (Map.Entry<UUID, PersistedAccountRow> entry : accounts.entrySet()) {
            UUID accountId = entry.getKey();
            PersistedAccountRow account = entry.getValue();
            Map<String, BigDecimal> balances = new LinkedHashMap<>();
            Map<String, PersistedBalanceRow> persistedBalances = balancesByAccount.get(accountId);
            if (persistedBalances != null) {
                for (PersistedBalanceRow balanceRow : persistedBalances.values()) {
                    balances.put(balanceRow.currencyId(), balanceRow.balance());
                }
            }
            if (balances.isEmpty()) {
                balances.put(defaultCurrencyId, BigDecimal.ZERO);
            }

            AccountRecord record = new AccountRecord(
                    accountId,
                    account.name(),
                    defaultCurrencyId,
                    balances,
                    account.createdAt(),
                    account.updatedAt());
            record.setFrozen(account.frozen());
            record.clearDirty();
            result.add(record);
        }
        return result;
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
            if (account == null) return Optional.empty();

            Map<String, PersistedBalanceRow> balanceRows = new LinkedHashMap<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT currency_id,balance,updated_at FROM account_balances WHERE account_id=?")) {
                ps.setString(1, id.toString());
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
            return Optional.of(record);
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
                    accountPs.setString(1, r.getId().toString());
                    accountPs.setString(2, r.getLastKnownName());
                    accountPs.setBigDecimal(3, r.getBalance());
                    accountPs.setLong(4, r.getCreatedAt());
                    accountPs.setLong(5, r.getUpdatedAt());
                    accountPs.setBoolean(6, r.isFrozen());
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

    private record PersistedAccountRow(String name, long createdAt, long updatedAt, boolean frozen) {}

    private record PersistedBalanceRow(String currencyId, BigDecimal balance, long updatedAt) {}

    private static String sqlLiteral(String value) {
        return value.replace("'", "''");
    }
}

