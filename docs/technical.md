# Technical Details

## Architecture

SimpleEco uses a **full in-memory cache** backed by an async-flush persistence layer.

```
Player command / VaultUnlocked API call
        │
        ▼
  AccountService (in-memory ConcurrentHashMap)
        │  read/write instant, no DB round-trip
        │
        ▼
  Background executor (single thread)
        │  dirty records batched periodically
        ▼
  JdbcAccountRepository → SQLite / H2 file
```

All hot-path operations (balance read, deposit, withdraw, pay) complete without touching the database. The DB is only written to:
- Every `autosave-interval` seconds (async scheduler)
- On server shutdown (`onDisable`)
- Transaction entries are written immediately after each operation (async, single-threaded executor)

This makes SimpleEco fast for a single local server, but it also means crash semantics and storage growth matter operationally.

## Threading Model

| Thread | What it does |
|---|---|
| Main / Folia region thread | Commands, balance operations, in-memory state mutations |
| Paper async scheduler | `flushDirty()` — batch upsert of dirty accounts |
| `SimpleEco-transactions` | Single-threaded executor — sequential `INSERT` of transaction log entries |
| Player join thread / owning region | Account creation / name update when the player joins |

`AccountRecord` mutations are guarded by `synchronized(record)`. The `pay()` operation locks two records in canonical UUID order to prevent deadlock.

## Persistence

Account data is stored in a single `accounts` table:

```sql
CREATE TABLE accounts (
    id         VARCHAR(36)   PRIMARY KEY,
    name       VARCHAR(16)   NOT NULL,
    balance    DECIMAL(30,8) NOT NULL,
    created_at BIGINT        NOT NULL,
    updated_at BIGINT        NOT NULL
)
```

Transaction history is append-only during normal operations. Deleting an account removes that player's own history rows so a later rejoin starts fresh:

```sql
CREATE TABLE transactions (
    type           VARCHAR(16)   NOT NULL,  -- GIVE, TAKE, SET, RESET, PAY_SENT, PAY_RECEIVED
    counterpart_id VARCHAR(36),             -- NULL for admin ops
    target_id      VARCHAR(36)   NOT NULL,
    amount         DECIMAL(30,8) NOT NULL,
    balance_before DECIMAL(30,8) NOT NULL,
    balance_after  DECIMAL(30,8) NOT NULL,
    ts             BIGINT        NOT NULL
)
```

Indexes: `idx_accounts_name_lower` (case-insensitive name lookup), `idx_tx_target_ts` (paginated history queries).

Production notes:

- SQLite runs in WAL mode with `synchronous=NORMAL`.
- SQLite `.db-wal` and `.db-shm` companion files are expected while the server is running.
- Deleting rows does not necessarily shrink the SQLite file immediately.
- There is currently no built-in history retention policy.

## Scale Characteristics

| Accounts | Estimated heap (accounts only) | Notes |
|---|---|---|
| 10,000 | ~5 MB | No concerns |
| 50,000 | ~25 MB | Comfortable |
| 100,000 | ~50 MB | `/baltop` sort ~50 ms without cache |
| 300,000+ | ~150 MB+ | Startup load time noticeable |

The practical bottleneck at high account counts is `/pay` and `/baltop` tab-complete, which stream the full name map. The baltop sort is mitigated by `cache-ttl-seconds`. This plugin is designed for single-server deployments; cross-server/network economy would require a shared DB (MySQL/MariaDB) and a non-cached architecture.

Observed production-style staging runs on a constrained 2-thread, 2 GB host completed cleanly at 1000 accounts and 100 operations per tick with successful post-run verification. That is a good readiness signal for small public beta deployments, but it does not change the single-server design assumptions above.

## Dirty Flag & Snapshot Pattern

When a balance changes, `AccountRecord.setBalance()` marks the record dirty. On flush:

1. `flushDirty()` acquires a snapshot of each dirty record under `synchronized(record)`.
2. The dirty flag is cleared immediately — new changes between snapshot and DB write will re-mark dirty.
3. The snapshot batch is written to DB. On failure, records are re-marked dirty for the next cycle.

This means at most one `autosave-interval` of data can be lost if the process is killed without `onDisable` running (e.g. `kill -9`).

## Operational Caveats

1. Storage backend changes are manual maintenance operations, not live toggles.
2. Transaction history can dominate file growth long before account rows do.
3. SQLite file size after heavy testing often requires a manual maintenance window and `VACUUM` if you want to reclaim disk space.
4. The stress-addon exists for staging only and should be removed from live production servers.
