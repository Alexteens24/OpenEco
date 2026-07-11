# Technical Notes

Operational details behind OpenEco's runtime model. For contributor internals, see [Development](/docs/development).

## Runtime model

OpenEco keeps a local account registry backed by JDBC. Persistence behavior depends on the account loading strategy.

### Account loading

| Strategy | Behavior |
|---|---|
| `eager` (default) | Loads all account rows at startup |
| `lazy` | Loads on first access; repository fallback for lookups |
| `live` | Re-reads on access and writes through every successful mutation |

In eager mode, balance reads and writes do not round-trip to the database after preload. In lazy mode, first access may trigger a one-time repository load.

Dirty account snapshots in `eager` and `lazy` flush on the autosave interval and on normal shutdown. In `live`, a balance operation succeeds only after its account snapshot is committed; a failed write restores the previous in-memory state.

`live` reduces stale reads and the crash-loss window, but it is not a distributed transaction protocol. Two backends mutating the same account at exactly the same time can still race; keep one active server authority per account.

### History writes

Transaction history is written on a dedicated single-thread executor. Before a dirty balance batch is persisted, OpenEco waits for older queued history writes so persisted balances do not outrun their audit trail.

### Baltop cache

Leaderboard results are cached with a configurable TTL and stored as immutable account snapshots.

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

## Hot path callers

These all read and write through the in-memory registry:

- Player commands (`/balance`, `/pay`, `/eco`, …)
- Vault v1 and VaultUnlocked v2 providers
- PlaceholderAPI expansion
- `OpenEcoApi` for addon integrations

For architecture and component details, see [Development](/docs/development).
