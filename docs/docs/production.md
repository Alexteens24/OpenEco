# Production Guide

Use OpenEco for a local Paper/Folia economy or a safe multi-writer proxy network backed by shared JDBC storage.

## Good fit

- One server, or a proxy network with controlled player handoff.
- Local file-based storage, or one shared remote JDBC database.
- Standard Vault economy integrations.
- Predictable operational model over feature breadth.

## Not a fit

- Real-time distributed balance replication across every backend.
- Workloads requiring every cached read to be immediately linearizable across all backends.
- Network mode on SQLite or H2.
- Shared accounts between players.
- Per-currency cooldown or tax rules beyond the current feature set.

## Before you launch

1. Install Java 21.
2. Install Paper 1.20.5+ or Folia 1.21+.
3. Install Vault or VaultUnlocked.
4. Start once so `plugins/OpenEco/config.yml` is created.
5. Review storage, autosave, pay, history retention, and messages.
6. Back up `plugins/OpenEco/`.
7. Test one restart, one `/eco reload`, one `/pay`, and one Vault-dependent plugin.

For network mode, also:

1. Use MySQL, MariaDB, or PostgreSQL.
2. Enable `cross-server.enabled: true` and `cross-server.mode: multi-writer` on every backend.
3. Optionally install the proxy addon JAR on Velocity.
4. Restart the proxy and all backends.

## Storage choice

### SQLite (default)

- Easy to back up.
- Uses WAL mode.
- Best fit for a local single-server economy.

### H2

- Supported alternative file format.
- Still local-only.

### Remote JDBC

Use MySQL, MariaDB, or PostgreSQL when you need one authoritative shared database.

- Required for cross-server mode.
- In `multi-writer` mode, concurrent mutations are serialized safely in database transactions.

### Changing backends

Changing `storage.type` does not move data automatically. Use `/openecomigrate sqlitetomysql` or see [Migration](/docs/migration).

## Recommended starting values

```yaml
persistence:
  autosave-interval-seconds: 30 # Usually 10-30

pay:
  cooldown-seconds: 0          # Usually 0-5
  tax-percent: 0.0             # Usually 0.0-5.0
  min-amount: 0.01             # Usually 0.01-1.00

baltop:
  refresh-interval-seconds: 30 # Usually 15-60

history:
  retention-days: -1

account-loading:
  mode: eager                     # Use lazy for very large account sets
  lazy:
    cache:
      enabled: true               # Retain the hot working set
      maximum-size: 50000
      expire-after-access-minutes: 30
```

- Lower `persistence.autosave-interval-seconds` reduces worst-case balance loss after an unclean stop.
- `pay.min-amount` helps prevent spammy micro-transfers.
- `history.retention-days: -1` keeps all history; positive values prune in the background.
- Start with `account-loading.mode: eager`. For large long-lived datasets, stage `lazy` mode and monitor cold DB latency. Keep `account-loading.lazy.cache.enabled: true` for a bounded hot set, or disable it when minimizing retained clean records matters more than repeated database reads.

## Backups

Best method:

1. Stop the server.
2. Copy the full `plugins/OpenEco/` directory.

If backing up SQLite while running, copy all of these together:

- `economy.db`
- `economy.db-wal`
- `economy.db-shm`

After a restore, verify `/balance`, `/history`, `/baltop`, and any Vault or PlaceholderAPI integrations.

## History and file growth

- History entries are created for balance changes and payments.
- Deleting an account deletes that account's history.
- `history.retention-days` can prune old rows.
- SQLite file size may not shrink after deletes until a manual `VACUUM` during maintenance.

## Crash semantics

- In multi-writer mode, mutations commit to JDBC before success is returned and database errors fail closed.
- Cached reads on another backend may lag by `cache-refresh-interval-ms`; Redis can shorten this but JDBC polling remains the recovery path.
- Account generations remain monotonic across delete/recreate, and enhancement interest retries are deduplicated per run, account, and currency.
- Multi-writer housekeeping prunes expired operation keys, rolling-policy usage, and old cluster jobs; size the operation retention window for your longest supported retry.
- Local and handoff modes still flush dirty balances on autosave and can lose up to one interval after an unclean stop.

## Telemetry

OpenEco starts bStats and FastStats only after the economy has enabled successfully. Telemetry startup or shutdown failures are isolated and do not disable economy features.

The FastStats integration submits built-in Bukkit platform metrics plus these OpenEco metrics:

| Source ID | Type | Value |
|---|---|---|
| `storage_backend` | String | Active database type |
| `account_count` | Number | Total number of stored economy accounts |
| `currency_count` | Number | Number of configured currencies |
| `cross_server_enabled` | Boolean | Whether shared-database network mode is enabled |
| `integrations` | String array | Detected Vault and PlaceholderAPI integrations |

These source IDs and types must match the data sources configured in the FastStats project. OpenEco does not submit account balances, account names, UUIDs, transaction history, or error tracking.

- Disable FastStats for the whole server with the JVM option `-Dfaststats.enabled=false`.
- Configure the existing bStats integration in `plugins/bStats/config.yml`.
- See the [FastStats system properties](https://docs.faststats.dev/java/system-properties) for debug and initial-delay controls.

## Network mode checklist

1. Shared backend must be MySQL, MariaDB, or PostgreSQL.
2. Enable `cross-server.enabled: true` and `cross-server.mode: multi-writer` on all backends.
3. Optionally install the proxy addon on Velocity.
4. Restart everything after toggling cross-server mode.
5. Test at least one server switch, one disconnect, and one `/ecosync <player>`.

## Rollout advice

1. Use staging first.
2. Test with the real plugins that call Vault.
3. Keep backups before changing storage settings.
4. Treat backend changes as maintenance, not live toggles.
