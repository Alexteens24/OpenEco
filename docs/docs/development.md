# Development

This guide is for contributors, maintainers, and plugin authors who need to understand how OpenEco is built internally.

For the public addon-facing contract, read [Addon API](/docs/api). Server owners should use the [Production guide](/docs/production) and [Configuration](/docs/configuration) instead.

## Project layout

| Path | Purpose |
|---|---|
| `src/main/java/.../OpenEcoPlugin.java` | Bootstrap, service registration, schedulers, reload, shutdown |
| `api/src/main/java/.../OpenEcoApi.java` | Public immutable API (JitPack-published) |
| `src/main/java/.../service/` | Core business logic, registry, cache, history |
| `src/main/java/.../storage/` | JDBC repository and dialect-specific schema |
| `src/main/java/.../economy/` | VaultUnlocked v2 and legacy Vault v1 adapters |
| `src/main/java/.../command/` | Bukkit command handlers |
| `src/main/java/.../event/` | Bukkit events |
| `src/main/java/.../listener/` | Player join/quit sync logic |
| `src/main/java/.../placeholder/` | PlaceholderAPI expansion |
| `enhancements-addon/` | Interest, pay limits, perm caps, exchange |
| `migrator-addon/` | Economy plugin import |
| `proxy-addon/` | Velocity proxy handoff helper |

## Startup lifecycle

`OpenEcoPlugin.onEnable()`:

1. Save default config.
2. Resolve storage backend and open `JdbcAccountRepository`.
3. Create `AccountService`, preload account batches, and build initial leaderboard snapshots.
4. Load message templates.
5. Register `OpenEcoApi` in Bukkit's `ServicesManager`.
6. Register VaultUnlocked v2 and legacy Vault v1 providers.
7. Register commands, listeners, and optional PlaceholderAPI expansion.
8. Start autosave, leaderboard refresh, and history prune schedulers.

If storage open or initial account load fails, the plugin disables itself.

`reloadSettings()` reloads config and messages, then restarts autosave, leaderboard refresh, and prune schedules.

`onDisable()` cancels schedulers, unregisters services, drains history, flushes dirty state, and closes JDBC.

## Runtime model

OpenEco is an in-memory economy with single-JVM authority and JDBC persistence.

- Live state in `AccountRegistry`.
- Normal reads/writes do not round-trip to the database.
- Dirty rows flush on autosave interval and clean shutdown.
- History writes through a dedicated single-thread executor.
- Baltop uses lightweight immutable snapshots refreshed asynchronously on the configured interval.

## Main components

### AccountService

Orchestration layer: account lifecycle, history reads, leaderboard reads, config snapshots.

### EconomyOperations

Balance and pay rules: amount validation, max balance, tax, cooldown, event dispatch, history creation.

### TransactionHistoryService

Serializes history inserts on a daemon executor. New mutations should use this service, not a separate executor.

### JdbcAccountRepository

Only built-in persistence. Supports SQLite, H2, MySQL, MariaDB, PostgreSQL. Account deletes also delete that account's transaction rows.

## Threading rules

- Call mutating logic from a safe server context.
- Paper: normal server thread.
- Folia: owning region thread for the player or entity involved.
- Autosave and history prune run on Paper's async scheduler.
- Player-targeted replies on Folia must return through the player's scheduler.

## Integration surfaces

| Surface | Use when |
|---|---|
| `OpenEcoApi` | Same-server plugin integration (preferred) |
| Vault v1 / VaultUnlocked v2 | Compatibility with existing economy plugins |
| Bukkit events | Observe or veto lifecycle and balance changes |

Main events: `AccountCreateEvent`, `AccountRenameEvent`, `AccountDeleteEvent`, `BalanceChangeEvent`, `BalanceChangedEvent`, `PayEvent`, `PayCompletedEvent`.

Pre-mutation events (`AccountRenameEvent`, `AccountDeleteEvent`, `BalanceChangeEvent`, `PayEvent`) are cancellable.

## Build and test

```bash
./gradlew build
./gradlew test
./gradlew shadowJar
```

Notes:

- Runtime libraries load through the `libraries:` section in `plugin.yml`.
- The build patches VaultUnlocked module metadata back to Java 21.
- `stress-addon/` is included only when the directory exists.

## Safe change checklist

| If you change... | Also verify... |
|---|---|
| Amount validation or balance rules | `EconomyOperations`, API statuses, Vault adapters, tests |
| Name/account lifecycle | `AccountService`, `PlayerConnectionListener`, API docs |
| History writing | `TransactionHistoryService`, history command, API docs |
| Baltop logic | Cache invalidation, snapshot immutability, rank lookups |
| Storage schema or account scan | SQLite/H2 tests and the remote-storage CI job |
| Scheduler usage | Paper vs Folia safety, reply dispatch |

## Entry points

| Task | Start here |
|---|---|
| Money logic | `service/EconomyOperations.java` |
| Account lifecycle | `service/AccountService.java` |
| Persistence | `storage/JdbcAccountRepository.java` |
| Plugin boot | `OpenEcoPlugin.java` |
| Addon contract | `api/OpenEcoApi.java` |
| Vault adapters | `economy/OpenEcoEconomyProvider.java` |
