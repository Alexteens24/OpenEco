# Production Guide

Use OpenEco when you want a single-server-first economy for Paper or Folia, with an optional proxy-assisted handoff mode for shared remote databases.

## Good fit

- One server, or a proxy network with controlled player handoff.
- Local file-based storage, or one shared remote JDBC database.
- Standard Vault economy integrations.
- Predictable operational model over feature breadth.

## Not a fit

- Real-time distributed balance replication across every backend.
- Multiple live backends mutating the same account simultaneously.
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
2. Install the proxy addon JAR on Velocity.
3. Enable `cross-server.enabled: true` on every backend.
4. Restart the proxy and all backends.

If you plan to use `accounts.load-strategy: lazy`, set it from the start. Restart after changing load strategy.

## Storage choice

### SQLite (default)

- Easy to back up.
- Uses WAL mode.
- Best fit for a local single-server economy.

### H2

- Supported alternative file format.
- Still local-only.

### Remote JDBC

Use MySQL, MariaDB, or PostgreSQL when you need one shared database for backend handoff.

- Required for proxy-assisted cross-server mode.
- Still not a distributed ledger — assume one active writer per account.

### Changing backends

Changing `storage.type` does not move data automatically. Use `/openecomigrate sqlitetomysql` or see [Migration](/docs/migration).

## Recommended starting values

```yaml
autosave-interval: 10-30

pay:
  cooldown-seconds: 0-5
  tax-percent: 0.0-5.0
  min-amount: 0.01-1.00

baltop:
  cache-ttl-seconds: 15-60

history:
  retention-days: -1
```

- Lower `autosave-interval` reduces worst-case balance loss after an unclean stop.
- `pay.min-amount` helps prevent spammy micro-transfers.
- `history.retention-days: -1` keeps all history; positive values prune in the background.

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

- Balance mutations apply in memory immediately.
- Dirty balances flush on the autosave interval and on normal shutdown.
- An unclean stop can lose up to one autosave interval of recent balance changes.
- Network mode requests an immediate flush on handoff, but this is best-effort — not global real-time replication.

## Network mode checklist

1. Shared backend must be MySQL, MariaDB, or PostgreSQL.
2. Install proxy addon on Velocity.
3. Enable `cross-server.enabled: true` on all backends.
4. Restart everything after toggling cross-server mode.
5. Test at least one server switch, one disconnect, and one `/ecosync <player>`.

## Rollout advice

1. Use staging first.
2. Test with the real plugins that call Vault.
3. Keep backups before changing storage settings.
4. Treat backend changes as maintenance, not live toggles.
