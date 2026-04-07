# Developer Guide

This document is for contributors, maintainers, and plugin authors who need to understand how OpenEco is built internally.

If you only run the plugin on a server, use the production and configuration docs instead.

## Scope

Use this guide when you need to:

- change balance, pay, cooldown, or tax behavior
- add or adjust storage behavior
- debug account lifecycle issues
- integrate another plugin beyond the public API surface
- understand where to add tests before changing internals

For the public addon-facing contract, read [Addon API](api.md) alongside this file.

## Project Layout

| Path | Purpose |
|---|---|
| `src/main/java/dev/alexisbinh/openeco/OpenEcoPlugin.java` | plugin bootstrap, service registration, scheduler startup, reload, shutdown |
| `src/main/java/dev/alexisbinh/openeco/api/` | public immutable API, result types, snapshots, API exception |
| `src/main/java/dev/alexisbinh/openeco/service/` | core business logic, registry, cache, history writer, config snapshot |
| `src/main/java/dev/alexisbinh/openeco/storage/` | JDBC repository and dialect-specific schema/tuning |
| `src/main/java/dev/alexisbinh/openeco/economy/` | VaultUnlocked v2 adapter and legacy Vault v1 adapter |
| `src/main/java/dev/alexisbinh/openeco/command/` | Bukkit command handlers and tab completion |
| `src/main/java/dev/alexisbinh/openeco/event/` | Bukkit events emitted by OpenEco |
| `src/main/java/dev/alexisbinh/openeco/listener/` | player join/quit sync logic |
| `src/main/java/dev/alexisbinh/openeco/placeholder/` | PlaceholderAPI expansion |
| `src/test/java/dev/alexisbinh/openeco/` | unit and integration tests by subsystem |

## Startup Lifecycle

`OpenEcoPlugin.onEnable()` follows this order:

1. Save default config.
2. Resolve storage backend and database filename.
3. Open `JdbcAccountRepository` and create schema if needed.
4. Create `AccountService` and load all accounts into memory.
5. Load message templates.
6. Register the public `OpenEcoApi` service.
7. Register VaultUnlocked v2 and legacy Vault v1 providers.
8. Register commands, listeners, and optional PlaceholderAPI expansion.
9. Start autosave and history prune schedulers.

If storage open or initial account load fails, the plugin disables itself early.

`reloadSettings()` reloads config and messages, then restarts autosave and prune schedules.

`onDisable()` cancels schedulers, unregisters Bukkit services, shuts down the history executor, flushes remaining dirty state through `AccountService.shutdown()`, and closes the JDBC repository.

## Runtime Model

OpenEco is an in-memory economy with a single-JVM authority and JDBC persistence.

- All account rows are loaded into memory at startup.
- Live account state is held inside `AccountRegistry`.
- Normal reads and writes do not round-trip to the database.
- Dirty account rows are flushed in batches on the autosave interval and on clean shutdown.
- Transaction history is written through a dedicated single-thread executor.
- Before a dirty balance batch is persisted, older queued history writes are drained so the persisted balance snapshot does not outrun its audit trail.
- Baltop is cached with a TTL and stored as frozen account snapshots.

Storage can be local SQLite/H2 or remote MySQL/MariaDB/PostgreSQL.
There is also an optional proxy-assisted handoff mode that re-reads and flushes player accounts during server transfers.

This design optimizes same-server latency first. Even with proxy-assisted handoff enabled, it is not a real-time distributed ledger or a safe multi-writer design for the same account.

## Main Components

### `AccountService`

`AccountService` is the orchestration layer.

It owns:

- `AccountRegistry` for live records and the UUID/name index
- `LeaderboardCache` for cached rich-list snapshots
- `TransactionHistoryService` for ordered history writes
- `EconomyOperations` for money rules and mutations
- config snapshots used by the hot path

Use this class when you need account lifecycle actions, history reads, leaderboard reads, or public-service orchestration.

### `EconomyOperations`

`EconomyOperations` contains balance and pay rules.

It is the main place for:

- amount validation
- max balance checks
- insufficient funds checks
- tax calculation
- pay cooldown enforcement
- self-transfer rejection
- mutation event dispatch
- transaction history creation for balance changes and payments

If a change affects what counts as a valid deposit, withdraw, set, reset, or pay, this is usually the first file to inspect.

### `TransactionHistoryService`

History writes are serialized through a single daemon executor.

This gives you:

- ordered transaction inserts
- async writes outside the main gameplay path
- a defined shutdown drain point before destructive account deletion or plugin shutdown

If you add a new mutation that should appear in history, keep the write path consistent with this service instead of starting a separate executor.

### `JdbcAccountRepository`

`JdbcAccountRepository` is the only built-in persistence implementation.

Important details:

- SQLite, H2, MySQL, MariaDB, and PostgreSQL are supported.
- SQLite uses a case-insensitive name index based on `LOWER(name)`.
- H2 uses a plain name index because the SQLite expression index is not portable there.
- remote backends are used directly through HikariCP
- account deletes also delete that account's transaction rows.
- filtered history queries are built centrally to keep pagination and counting aligned.

## Threading Rules

OpenEco is not a free-threaded API.

- Call mutating logic from a safe server context.
- On Paper, that means the normal server thread.
- On Folia, that means the correct owning region thread for the player or entity involved.
- Do not move Bukkit-facing work onto arbitrary async threads.

Internal async work is deliberately narrow:

- autosave runs on Paper's async scheduler
- history prune runs on Paper's async scheduler
- transaction inserts run on the dedicated history executor

One subtle but important rule already baked into the codebase: player-targeted replies on Folia must return through the player's scheduler, not a global scheduler.

## Data And Contract Invariants

These rules are relied on across commands, Vault bridges, and the public API.

### Account identity

- account names are trimmed before validation
- blank names are rejected
- names longer than 16 characters are rejected
- names are unique case-insensitively
- legacy Vault v1 string lookups resolve through OpenEco's internal name mapping rather than inventing UUIDs

### Money rules

- deposit, withdraw, and pay amounts must be positive
- deposit, withdraw, and pay amounts that round to `0` at the configured decimal scale are invalid
- set accepts zero and positive amounts, but not negative amounts
- `has(...)` rejects negative probe amounts
- `has(...)` compares against the configured decimal scale; positive probe amounts that round to `0` fail the probe
- self-transfer is invalid
- max balance is enforced on deposit and on the receiving side of pay

### Cache and persistence rules

- any account create, rename, or delete must invalidate the baltop cache immediately
- balance mutations do not eagerly invalidate the baltop cache; the cache expires naturally on its configured TTL
- cached leaderboard entries must be immutable snapshots, not live records
- deleting an account must not race pending history writes; the service waits for the history queue to drain before deleting persisted rows

If a change breaks one of these assumptions, commands, Vault bridges, placeholders, or addon API behavior will drift out of sync.

## Integration Surfaces

OpenEco exposes three main integration paths.

### Public addon API

Preferred for same-server plugin integrations.

- type: `OpenEcoApi`
- registration: Bukkit `ServicesManager`
- contract: immutable snapshots and plugin-native result objects

Use this path when you own both sides of the integration or need the richest contract.

### Vault providers

Compatibility layer for existing economy plugins.

- `OpenEcoEconomyProvider` implements VaultUnlocked v2
- `OpenEcoLegacyEconomyProvider` implements legacy Vault v1

When you change money rules, keep both providers behaviorally aligned with the core service.

### Bukkit events

For listeners that want to observe or veto lifecycle and balance changes.

Main events:

- `AccountCreateEvent`
- `AccountRenameEvent`
- `AccountDeleteEvent`
- `BalanceChangeEvent`
- `BalanceChangedEvent`
- `PayEvent`
- `PayCompletedEvent`

Use pre-events when you need veto points, and post-events when you need audit, webhook, or analytics hooks after a successful mutation.
Pre-events are now dispatched outside the plugin's internal locks. Their payloads describe the attempted mutation at dispatch time; if another operation races before commit, the post-events and final snapshots are authoritative.

## Build And Test Workflow

From repo root:

```bash
./gradlew build
```

Useful commands:

```bash
./gradlew test
./gradlew shadowJar
```

Notes:

- the root build produces the shaded plugin jar
- the build patches VaultUnlocked module metadata back to Java 21 because the published metadata currently reports a higher target JVM than this project uses
- a local `stress-addon/` module can exist in private workspaces, but the public repo builds without it because `settings.gradle.kts` includes it only when the directory exists

## Test Strategy

Tests are grouped by subsystem under `src/test/java/dev/alexisbinh/openeco/`.

Current coverage focuses on:

- API contract behavior
- service-level money and lifecycle rules
- listener behavior on player sync
- legacy/Vault adapter compatibility
- JDBC/H2 persistence integration
- leaderboard cache behavior

Before changing internals, try to add or update tests in the same subsystem rather than only relying on end-to-end manual checks.

## Safe Change Checklist

When you touch a core behavior, verify the nearby surfaces that depend on it.

| If you change... | Also verify... |
|---|---|
| amount validation or balance rules | `EconomyOperations`, `AccountService`, addon API statuses, Vault v1/v2 adapters, tests |
| name/account lifecycle | `AccountService`, `PlayerConnectionListener`, name lookup behavior, docs/api.md |
| history writing or filtering | `TransactionHistoryService`, repository count/query parity, history command, API docs |
| baltop logic | cache invalidation, snapshot immutability, rank lookups, tests |
| storage schema or SQL | both SQLite and H2 behavior, startup load, integration tests |
| scheduler usage | Paper vs Folia safety, reply dispatch, Bukkit thread access |

## Practical Entry Points

If you are new to the codebase, start from one of these files depending on what you need to change:

- money logic: `service/EconomyOperations.java`
- account lifecycle: `service/AccountService.java`
- persistence and SQL: `storage/JdbcAccountRepository.java`
- plugin boot and registration: `OpenEcoPlugin.java`
- addon integration contract: `api/OpenEcoApi.java` and `docs/api.md`
- compatibility adapters: `economy/OpenEcoEconomyProvider.java` and `economy/OpenEcoLegacyEconomyProvider.java`

That is usually enough to find the real call path without having to scan the entire plugin first.