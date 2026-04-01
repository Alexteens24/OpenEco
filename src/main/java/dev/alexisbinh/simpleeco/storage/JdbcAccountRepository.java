package dev.alexisbinh.simpleeco.storage;

import dev.alexisbinh.simpleeco.model.AccountRecord;
import dev.alexisbinh.simpleeco.model.TransactionEntry;
import dev.alexisbinh.simpleeco.model.TransactionType;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.sql.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public class JdbcAccountRepository implements AccountRepository {

    private final Connection connection;
    private final DatabaseDialect dialect;

    public JdbcAccountRepository(DatabaseDialect dialect, String dataFolder, String filename) throws SQLException {
        this.dialect = dialect;
        String url = dialect.getJdbcUrl(dataFolder, filename);
        this.connection = DriverManager.getConnection(url);
        dialect.applyTuning(connection);
        createSchema();
    }

    private void createSchema() throws SQLException {
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
            stmt.execute(
                "CREATE INDEX IF NOT EXISTS idx_tx_target_ts ON transactions(target_id, ts DESC)"
            );
        }
    }

    @Override
    public synchronized List<AccountRecord> loadAll() throws SQLException {
        List<AccountRecord> result = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT id,name,balance,created_at,updated_at FROM accounts")) {
            while (rs.next()) {
                UUID id = UUID.fromString(rs.getString("id"));
                String name = rs.getString("name");
                BigDecimal balance = rs.getBigDecimal("balance");
                long createdAt = rs.getLong("created_at");
                long updatedAt = rs.getLong("updated_at");
                result.add(new AccountRecord(id, name, balance, createdAt, updatedAt));
            }
        }
        return result;
    }

    @Override
    public synchronized void upsertBatch(Collection<AccountRecord> records) throws SQLException {
        if (records.isEmpty()) return;
        connection.setAutoCommit(false);
        try (PreparedStatement ps = connection.prepareStatement(dialect.upsertSql())) {
            for (AccountRecord r : records) {
                ps.setString(1, r.getId().toString());
                ps.setString(2, r.getLastKnownName());
                ps.setBigDecimal(3, r.getBalance());
                ps.setLong(4, r.getCreatedAt());
                ps.setLong(5, r.getUpdatedAt());
                ps.addBatch();
            }
            ps.executeBatch();
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
             PreparedStatement deleteAccount = connection.prepareStatement(
                    "DELETE FROM accounts WHERE id=?")) {
            deleteTransactions.setString(1, accountId.toString());
            deleteTransactions.executeUpdate();

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
                "INSERT INTO transactions(type,counterpart_id,target_id,amount,balance_before,balance_after,ts) "
              + "VALUES(?,?,?,?,?,?,?)")) {
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
            ps.executeUpdate();
        }
    }

    @Override
    public synchronized List<TransactionEntry> getTransactions(UUID targetId, int limit, int offset)
            throws SQLException {
        List<TransactionEntry> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT type,counterpart_id,target_id,amount,balance_before,balance_after,ts "
              + "FROM transactions WHERE target_id=? ORDER BY ts DESC LIMIT ? OFFSET ?")) {
            ps.setString(1, targetId.toString());
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapRow(rs));
                }
            }
        }
        return result;
    }

    @Override
    public synchronized int countTransactions(UUID targetId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM transactions WHERE target_id=?")) {
            ps.setString(1, targetId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    @Override
    public synchronized List<TransactionEntry> getTransactions(UUID targetId, int limit, int offset,
            @Nullable TransactionType type, long fromMs, long toMs) throws SQLException {
        String sql = buildFilteredSql("SELECT type,counterpart_id,target_id,amount,balance_before,balance_after,ts "
                + "FROM transactions", targetId, type, fromMs, toMs)
                + " ORDER BY ts DESC LIMIT ? OFFSET ?";
        List<TransactionEntry> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int idx = bindFilterParams(ps, targetId, type, fromMs, toMs, 1);
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
        String sql = buildFilteredSql("SELECT COUNT(*) FROM transactions", targetId, type, fromMs, toMs);
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bindFilterParams(ps, targetId, type, fromMs, toMs, 1);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private static String buildFilteredSql(String select, UUID targetId,
            @Nullable TransactionType type, long fromMs, long toMs) {
        StringBuilder sb = new StringBuilder(select)
                .append(" WHERE target_id=?");
        if (type != null) sb.append(" AND type=?");
        if (fromMs > 0) sb.append(" AND ts>=?");
        if (toMs < Long.MAX_VALUE) sb.append(" AND ts<=?");
        return sb.toString();
    }

    private static int bindFilterParams(PreparedStatement ps, UUID targetId,
            @Nullable TransactionType type, long fromMs, long toMs, int startIdx) throws SQLException {
        int idx = startIdx;
        ps.setString(idx++, targetId.toString());
        if (type != null) ps.setString(idx++, type.name());
        if (fromMs > 0) ps.setLong(idx++, fromMs);
        if (toMs < Long.MAX_VALUE) ps.setLong(idx++, toMs);
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
        return new TransactionEntry(type, counterpartId, tgtId, amount, before, after, ts);
    }

    @Override
    public synchronized int pruneTransactions(long cutoffMs) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM transactions WHERE ts < ?")) {
            ps.setLong(1, cutoffMs);
            return ps.executeUpdate();
        }
    }
}
