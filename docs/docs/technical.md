# Technical Notes

Operational details behind OpenEco's runtime model. For contributor internals, see [Development](/docs/development).

## Runtime model

OpenEco supports eager in-memory state and an opt-in lazy working-set cache. Both modes write dirty balances through JDBC in the background.

### Account loading

In the default `eager` mode, accounts and balances are read with one ordered streaming join and retained in the in-memory registry.

In `lazy` mode, startup does not scan account balances and cold UUID/name lookups load from JDBC. With `account-loading.lazy.cache.enabled: true`, recently accessed accounts form a bounded hot set and clean offline entries expire after the configured idle period. With caching disabled, clean records are removed on a short maintenance cycle while dirty, online, login-pinned, and actively leased records remain for correctness. Concurrent UUID and normalized-name misses are coalesced, and loader queues are bounded. Synchronous Vault and OpenEco API calls can therefore wait for database I/O on a cold lookup; VaultUnlocked's async facade uses a separate bounded worker pool.

Lazy PlaceholderAPI requests never wait for a cold database read. They return the most recent bounded snapshot (or a safe zero/blank fallback) and refresh it asynchronously. Player account creation/rename and required storage checks run during async pre-login; a storage failure denies login instead of admitting a player with an unusable economy account.

Explicit bulk APIs such as `getUUIDNameMap()` still materialize the requested full result for compatibility. Addons that iterate every account can therefore create temporary memory and database load even when lazy caching is enabled.

Dirty account snapshots flush on the autosave interval and on normal shutdown.

### History writes

Transaction history is written on a dedicated single-thread executor. Before a dirty balance batch is persisted, OpenEco waits for older queued history writes so persisted balances do not outrun their audit trail.

### Baltop cache

Eager mode uses lightweight per-currency snapshots. Lazy mode flushes dirty balances on the configured refresh interval and queries leaderboard pages/ranks from the database, avoiding an all-account leaderboard copy in RAM.

## Storage

- Local: SQLite or H2 under `plugins/OpenEco/`.
- Remote: MySQL, MariaDB, or PostgreSQL through HikariCP.
- SQLite uses WAL mode — `economy.db-wal` and `economy.db-shm` are normal while running.
- Deleting rows does not immediately shrink the SQLite file.
- Cross-server mode requires a remote backend.

## Cross-server handoff

When `cross-server.enabled` is true:

1. Account flush on backend disconnect.
2. Account refresh during async pre-login, including remote deletion detection.
3. `openeco:sync` plugin messaging channel for proxy-triggered flush/refresh.

This is handoff sync, not real-time global replication. Balances are not broadcast live to every backend.

## Crash semantics

- Recent balance changes can be lost after an unclean stop.
- Loss window is at most one `persistence.autosave-interval-seconds` under normal conditions.
- Normal shutdown drains queued history writes, then performs a final balance flush.

## Scaling notes

- OpenEco is designed around one active server authority per account.
- Large account counts increase eager startup load time and leaderboard work. Lazy mode trades cold-read latency and database work for bounded retained heap.
- `/pay`, `/baltop`, and name tab-complete are the most visible features under account-count growth.
- Large history volumes can dominate file size before account rows do.

Observed staging signal (not a guarantee for every server):

- 1000 accounts, 100 operations per tick, 180 seconds, 2-thread 2 GB host — successful verify after the run.
- 500,000 accounts, H2, one currency, 1 GB heap — streaming preload retained about 228 MiB, peaked around 638 MiB, and completed in roughly 3.6–3.8 seconds on the benchmark host.

## Hot path callers

These use the hot account registry; in lazy mode a cold synchronous call may first load from JDBC:

- Player commands (`/balance`, `/pay`, `/eco`, …)
- Vault v1 and VaultUnlocked v2 providers
- PlaceholderAPI expansion (non-blocking snapshots in lazy mode)
- `OpenEcoApi` for addon integrations

For architecture and component details, see [Development](/docs/development).
