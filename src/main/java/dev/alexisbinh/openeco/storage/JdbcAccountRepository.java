package dev.alexisbinh.openeco.storage;

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
import java.util.UUID;

public class JdbcAccountRepository implements AccountRepository {

    private final Connection connection;
    private final DatabaseDialect dialect;
    private final String defaultCurrencyId;

    public JdbcAccountRepository(DatabaseDialect dialect, String dataFolder, String filename) throws SQLException {
        this(dialect, dataFolder, filename, "openeco");
    }

    public JdbcAccountRepository(DatabaseDialect dialect, String dataFolder, String filename,
                                 String defaultCurrencyId) throws SQLException {
        this.dialect = dialect;
        this.defaultCurrencyId = normalizeCurrencyId(defaultCurrencyId);
        String url = dialect.getJdbcUrl(dataFolder, filename);
        this.connection = DriverManager.getConnection(url);
        dialect.applyTuning(connection);
        createSchema();
    }

    private void createSchema() throws SQLException {
        connection.setAutoCommit(false);
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS accounts (
                    id         VARCHAR(36)   NOT NULL PRIMARY KEY,
                    name       VARCHAR(16)   NOT NULL,
                    balance    DECIMAL(30,8) NOT NULL DEFAULT 0,
                    created_at BIGINT        NOT NULL,
                    updated_at BIGINT        NOT NULL
                )
                """);
            stmt.execute(switch (dialect) {
                case H2 -> "CREATE INDEX IF NOT EXISTS idx_accounts_name ON accounts(name)";
                case SQLITE -> "CREATE INDEX IF NOT EXISTS idx_accounts_name_lower ON accounts(LOWER(name))";
            });
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
            ensureColumn(stmt, "transactions", "source", "VARCHAR(64)");
            ensureColumn(stmt, "transactions", "note", "VARCHAR(255)");
            ensureColumn(stmt, "transactions", "currency_id",
                    "VARCHAR(32) NOT NULL DEFAULT '" + sqlLiteral(defaultCurrencyId) + "'");
            ensureColumn(stmt, "accounts", "frozen", "BOOLEAN NOT NULL DEFAULT FALSE");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_tx_target_ts ON transactions(target_id, ts DESC)");
            backfillDefaultBalances();
            backfillTransactionCurrencies();
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private void ensureColumn(Statement stmt, String tableName, String columnName, String definition) throws SQLException {
        if (columnExists(tableName, columnName)) {
            return;
        }
        stmt.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + definition);
    }

    private boolean columnExists(String tableName, String columnName) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        return hasColumn(metaData, tableName, columnName)
                || hasColumn(metaData, tableName.toUpperCase(Locale.ROOT), columnName.toUpperCase(Locale.ROOT))
                || hasColumn(metaData, tableName.toLowerCase(Locale.ROOT), columnName.toLowerCase(Locale.ROOT));
    }

    private static boolean hasColumn(DatabaseMetaData metaData, String tableName, String columnName) throws SQLException {
        try (ResultSet rs = metaData.getColumns(null, null, tableName, columnName)) {
            return rs.next();
        }
    }

    @Override
    public synchronized List<AccountRecord> loadAll() throws SQLException {
        Map<UUID, AccountRecord> result = new LinkedHashMap<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id,name,created_at,updated_at,frozen FROM accounts")) {
            while (rs.next()) {
                UUID id = UUID.fromString(rs.getString("id"));
                String name = rs.getString("name");
                long createdAt = rs.getLong("created_at");
                long updatedAt = rs.getLong("updated_at");
                boolean frozen = rs.getBoolean("frozen");
                AccountRecord rec = new AccountRecord(
                        id,
                        name,
                        defaultCurrencyId,
                        Map.of(defaultCurrencyId, BigDecimal.ZERO),
                        createdAt,
                        updatedAt);
                rec.setFrozen(frozen);
                rec.clearDirty();
                result.put(id, rec);
            }
        }

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT account_id,currency_id,balance,updated_at FROM account_balances")) {
            while (rs.next()) {
                UUID accountId = UUID.fromString(rs.getString("account_id"));
                AccountRecord record = result.get(accountId);
                if (record == null) {
                    continue;
                }
                record.setBalance(rs.getString("currency_id"), rs.getBigDecimal("balance"));
                record.clearDirty();
            }
        }

        return new ArrayList<>(result.values());
    }

    @Override
    public synchronized void upsertBatch(Collection<AccountRecord> records) throws SQLException {
        if (records.isEmpty()) return;
        connection.setAutoCommit(false);
        try (PreparedStatement accountPs = connection.prepareStatement(dialect.upsertSql());
             PreparedStatement balancePs = connection.prepareStatement(dialect.balanceUpsertSql())) {
            for (AccountRecord r : records) {
                accountPs.setString(1, r.getId().toString());
                accountPs.setString(2, r.getLastKnownName());
                accountPs.setBigDecimal(3, r.getBalance());
                accountPs.setLong(4, r.getCreatedAt());
                accountPs.setLong(5, r.getUpdatedAt());
                accountPs.setBoolean(6, r.isFrozen());
                accountPs.addBatch();

                for (Map.Entry<String, BigDecimal> balanceEntry : r.getBalancesSnapshot().entrySet()) {
                    balancePs.setString(1, r.getId().toString());
                    balancePs.setString(2, balanceEntry.getKey());
                    balancePs.setBigDecimal(3, balanceEntry.getValue());
                    balancePs.setLong(4, r.getUpdatedAt());
                    balancePs.addBatch();
                }
            }
            accountPs.executeBatch();
            balancePs.executeBatch();
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    @Override
    public synchronized void delete(UUID accountId) throws SQLException {
        connection.setAutoCommit(false);
        try (PreparedStatement deleteTransactions = connection.prepareStatement(
                    "DELETE FROM transactions WHERE target_id=?");
             PreparedStatement deleteBalances = connection.prepareStatement(
                "DELETE FROM account_balances WHERE account_id=?");
             PreparedStatement deleteAccount = connection.prepareStatement(
                    "DELETE FROM accounts WHERE id=?")) {
            deleteTransactions.setString(1, accountId.toString());
            deleteTransactions.executeUpdate();

            deleteBalances.setString(1, accountId.toString());
            deleteBalances.executeUpdate();

            deleteAccount.setString(1, accountId.toString());
            deleteAccount.executeUpdate();

            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    // ── TransactionRepository ────────────────────────────────────────────────

    @Override
    public synchronized void insertTransaction(TransactionEntry entry) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
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
            ps.setString(10, entry.getCurrencyId() != null ? entry.getCurrencyId() : defaultCurrencyId);
            ps.executeUpdate();
        }
    }

    private void backfillDefaultBalances() throws SQLException {
        try (PreparedStatement selectAccounts = connection.prepareStatement(
                    "SELECT id,balance,updated_at FROM accounts");
             PreparedStatement hasAnyBalance = connection.prepareStatement(
                    "SELECT 1 FROM account_balances WHERE account_id=?");
             PreparedStatement insertBalance = connection.prepareStatement(
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

    private void backfillTransactionCurrencies() throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE transactions SET currency_id=? WHERE currency_id IS NULL OR TRIM(currency_id) = ''")) {
            ps.setString(1, defaultCurrencyId);
            ps.executeUpdate();
        }
    }

    @Override
    public synchronized List<TransactionEntry> getTransactions(UUID targetId, int limit, int offset)
            throws SQLException {
        return getTransactions(targetId, limit, offset, null, 0L, Long.MAX_VALUE, null);
    }

    @Override
    public synchronized List<TransactionEntry> getTransactions(UUID targetId, int limit, int offset,
            @Nullable String currencyId) throws SQLException {
        return getTransactions(targetId, limit, offset, null, 0L, Long.MAX_VALUE, currencyId);
    }

    @Override
    public synchronized int countTransactions(UUID targetId) throws SQLException {
        return countTransactions(targetId, null, 0L, Long.MAX_VALUE, null);
    }

    @Override
    public synchronized int countTransactions(UUID targetId, @Nullable String currencyId) throws SQLException {
        return countTransactions(targetId, null, 0L, Long.MAX_VALUE, currencyId);
    }

    @Override
    public synchronized List<TransactionEntry> getTransactions(UUID targetId, int limit, int offset,
            @Nullable TransactionType type, long fromMs, long toMs) throws SQLException {
        return getTransactions(targetId, limit, offset, type, fromMs, toMs, null);
    }

    @Override
    public synchronized List<TransactionEntry> getTransactions(UUID targetId, int limit, int offset,
            @Nullable TransactionType type, long fromMs, long toMs, @Nullable String currencyId) throws SQLException {
        String sql = buildFilteredSql("SELECT type,counterpart_id,target_id,amount,balance_before,balance_after,ts,source,note,currency_id "
                + "FROM transactions", targetId, type, fromMs, toMs, currencyId)
                + " ORDER BY ts DESC LIMIT ? OFFSET ?";
        List<TransactionEntry> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
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
    public synchronized int countTransactions(UUID targetId, @Nullable TransactionType type,
            long fromMs, long toMs) throws SQLException {
        return countTransactions(targetId, type, fromMs, toMs, null);
    }

    @Override
    public synchronized int countTransactions(UUID targetId, @Nullable TransactionType type,
            long fromMs, long toMs, @Nullable String currencyId) throws SQLException {
        String sql = buildFilteredSql("SELECT COUNT(*) FROM transactions", targetId, type, fromMs, toMs, currencyId);
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
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
        if (currencyId != null && !currencyId.isBlank()) sb.append(" AND currency_id=?");
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
        if (currencyId != null && !currencyId.isBlank()) ps.setString(idx++, currencyId);
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
    public synchronized int pruneTransactions(long cutoffMs) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
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

    private static String sqlLiteral(String value) {
        return value.replace("'", "''");
    }
}
