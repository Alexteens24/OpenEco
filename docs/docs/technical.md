# Technical Notes

Operational details behind OpenEco's runtime model. For contributor internals, see [Development](/docs/development).

## Runtime model

OpenEco keeps account data in memory and writes it through JDBC in the background.

### Account loading

Accounts and their balances are read with one ordered streaming join, assembled a row at a time, and emitted into the in-memory registry in bounded batches. After startup, balance reads and writes never fall back to database queries.

Dirty account snapshots flush on the autosave interval and on normal shutdown.

### History writes

Transaction history is written on a dedicated single-thread executor. Before a dirty balance batch is persisted, OpenEco waits for older queued history writes so persisted balances do not outrun their audit trail.

### Baltop cache

Lightweight per-currency leaderboard snapshots are refreshed in the background at the configured interval. Requests keep using the previous immutable snapshot while a refresh is running; rank lookup is constant-time.

## Storage

- Local: SQLite or H2 under `plugins/OpenEco/`.
- Remote: MySQL, MariaDB, or PostgreSQL through HikariCP.
- SQLite uses WAL mode — `economy.db-wal` and `economy.db-shm` are normal while running.
- Deleting rows does not immediately shrink the SQLite file.
- Cross-server mode requires a remote backend.

## Cross-server handoff

When `cross-server.enabled` is true:

1. Account flush on backend disconnect.
2. Account refresh on backend join completion.
3. `openeco:sync` plugin messaging channel for proxy-triggered flush/refresh.

This is handoff sync, not real-time global replication. Balances are not broadcast live to every backend.

## Crash semantics

- Recent balance changes can be lost after an unclean stop.
- Loss window is at most one `autosave-interval` under normal conditions.
- Normal shutdown drains queued history writes, then performs a final balance flush.

## Scaling notes

- OpenEco is designed around one active server authority per account.
- Large account counts increase startup load time and leaderboard work.
- `/pay`, `/baltop`, and name tab-complete are the most visible features under account-count growth.
- Large history volumes can dominate file size before account rows do.

Observed staging signal (not a guarantee for every server):

- 1000 accounts, 100 operations per tick, 180 seconds, 2-thread 2 GB host — successful verify after the run.
- 500,000 accounts, H2, one currency, 1 GB heap — streaming preload retained about 228 MiB, peaked around 638 MiB, and completed in roughly 3.6–3.8 seconds on the benchmark host.

## Hot path callers

These all read and write through the in-memory registry:

- Player commands (`/balance`, `/pay`, `/eco`, …)
- Vault v1 and VaultUnlocked v2 providers
- PlaceholderAPI expansion
- `OpenEcoApi` for addon integrations

For architecture and component details, see [Development](/docs/development).
