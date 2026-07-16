# Features

OpenEco supports two operational models: an in-memory hot path for local/handoff deployments, and an authoritative JDBC mutation path for safe multi-writer networks.

## Core economy

- **In-memory account registry** — balance reads and writes stay on the JVM after accounts are loaded.
- **Batched account loading** — accounts preload into memory in bounded batches so hot-path operations never query storage.
- **Multi-currency support** — define currencies under `currencies.definitions.*` with per-currency decimals, starting balances, and max caps.
- **Transaction history** — balance changes and payments are recorded; optional retention pruning.
- **Account administration** — freeze, unfreeze, rename, delete, and reset accounts from `/eco`.

## Integrations

### Vault

OpenEco registers two economy providers:

- **VaultUnlocked v2** — full multi-currency API when VaultUnlocked is present.
- **Legacy Vault v1** — default-currency compatibility layer for older plugins.

Both providers route through the same core money rules, so behavior stays consistent regardless of which Vault API a dependent plugin uses.

### PlaceholderAPI

When PlaceholderAPI is installed, OpenEco registers its own expansion automatically. Placeholders cover player balances, ranks, currency names, and leaderboard entries. See [Placeholders](/docs/placeholders).

### Public addon API

Plugin developers can integrate directly through `OpenEcoApi` registered in Bukkit's `ServicesManager`. See [Addon API](/docs/api).

## Player-facing commands

| Feature | Command |
|---|---|
| Check balance | `/balance [player] [currency]` |
| Leaderboard | `/baltop [page] [currency]` |
| Send money | `/pay <player> <amount> [currency]` |
| Transaction history | `/history [player] [page] [currency]` |

Pay supports configurable cooldown, tax percentage, and minimum amount. Baltop uses lightweight snapshots refreshed in the background.

## Admin tools

`/eco` provides give, take, set, reset, delete, freeze, unfreeze, rename, and reload subcommands. `/openecomigrate` handles economy plugin imports and storage backend migrations.

`/eco reload` refreshes messages and most runtime rules. Storage backend, cross-server mode, and Redis topology changes require a restart.

## Optional addons

### OpenEcoMigrator

Imports balances from EssentialsX, CMI, LiteEco, XConomy, BOSEconomy, TheNewEconomy, PlayerPoints, or any active Vault economy provider. See [Migration](/docs/migration).

### OpenEcoEnhancements

Adds interest payouts, pay limits, permission-based balance caps, and `/exchange` for currency conversion. In multi-writer mode, permission policies consume region-safe player snapshots and interest payouts use idempotent operation IDs for safe retry after partial runs.

### OpenEco Proxy (Velocity)

Coordinates account flush and refresh during server transfers when `cross-server.enabled` is true on backends. Exposes `/ecosync <player>` on the proxy for manual admin refreshes.

## Network modes

With `cross-server.mode: multi-writer`, every balance, transfer, exchange, account mutation, cooldown, and registered policy check commits in one database transaction. Row locking and deterministic account lock order prevent lost updates, overspending, and transfer deadlocks. Versions remain monotonic even when the same account UUID is deleted and recreated. A durable change log refreshes each backend's read cache in batches; optional Redis only reduces notification latency.

With `cross-server.mode: handoff`:

1. A player's account is flushed to the database when they leave a backend.
2. The account is re-read from the database before they finish joining the next backend.
3. The optional Velocity proxy addon can trigger targeted flush/refresh requests.

Handoff remains a compatibility mode and still assumes one active writer per account.

## Platform support

- **Paper 1.20.5+** — confirmed to load.
- **Folia 1.21+** — region-aware schedulers for player-facing replies and mutations.
- **Java 21** — required runtime.

## What OpenEco does not do

- Linearizable cache reads on every backend (cached reads may lag by the configured poll interval).
- Cross-server mode on SQLite or H2 (local-only backends).
- Shared accounts between players.

For operational guidance, see the [Production guide](/docs/production).
