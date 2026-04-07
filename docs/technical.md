# Technical Notes

This file is for owners who want the operational details behind the plugin.

## Runtime Model

OpenEco keeps account data in memory and writes it through JDBC in the background.

Hot path callers include:

- player commands
- Vault v1 plugins
- VaultUnlocked v2 plugins
- addons using the OpenEco API

Balance reads and writes do not need a database round trip. Dirty account snapshots are flushed on the autosave interval and on normal shutdown.

Transaction history is written on a dedicated single-thread executor.
Before a dirty balance batch is persisted, OpenEco waits for older queued history writes so persisted balances do not outrun their recorded audit trail.

The default shape is still single-server-first. An optional proxy-assisted handoff mode can flush and refresh accounts across backend transfers when every backend shares one remote database.

## Storage

- Storage can be local SQLite or H2 under `plugins/OpenEco/`, or remote MySQL / MariaDB / PostgreSQL.
- SQLite uses WAL mode.
- `economy.db-wal` and `economy.db-shm` are normal while SQLite is active.
- Deleting rows does not guarantee that the SQLite file shrinks immediately.
- Proxy-assisted handoff mode requires a remote backend; SQLite and H2 are local-only.

## History

- Balance changes and payments create history entries.
- Deleting an account deletes that account's own history.
- `history.retention-days` controls background pruning.
- `history.retention-days: -1` keeps all history.

## Crash Semantics

- Recent balance changes can be lost after an unclean stop because balances are flushed on a schedule.
- The loss window is at most one `autosave-interval` under normal conditions.
- Normal shutdown drains queued history writes, then performs a final balance flush.

## Scaling Notes

- OpenEco is designed around one active server authority per account.
- Proxy-assisted handoff can work across backend servers, but it is not real-time global replication.
- Large account counts increase startup load time and leaderboard work.
- `/pay`, `/baltop`, and name tab-complete are the most obvious features to feel account-count growth.
- Large history volumes can dominate file size before account rows do.

Observed staging run:

- 1000 accounts
- 100 operations per tick
- 180 seconds
- 2-thread, 2 GB host
- successful verify after the run

That is a useful staging signal. It is not a promise for every server or plugin stack.
