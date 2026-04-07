# Production Guide

Use OpenEco when you want a single-server-first economy for Paper or Folia, with an optional proxy-assisted handoff mode for shared remote databases.

## Good Fit

- One server.
- Local file-based storage, or one shared remote JDBC database.
- Optional player handoff between backend servers through the Velocity proxy addon.
- Standard Vault economy integrations.
- Predictable operational model over feature breadth.

## Not A Fit

- Real-time distributed balance replication across every backend.
- Multiple live backends mutating the same account at the same time.
- Network mode on SQLite or H2.
- Shared accounts or multi-currency beyond the current feature set.

## Before You Launch

1. Install Java 21.
2. Install a supported server build.
  Paper 1.20.5 is confirmed to boot this plugin.
  Folia 1.21+ is the current safe baseline for the scheduler APIs used here.
3. Install VaultUnlocked. Paper exposes it as plugin `Vault`.
4. Start once so `plugins/OpenEco/config.yml` is created.
5. Review storage, autosave, pay, history retention, and messages.
6. Back up `plugins/OpenEco/`.
7. Test one restart, one `/eco reload`, one `/pay`, and one Vault plugin that depends on an economy provider.

If you are using network mode, also:

1. Use MySQL, MariaDB, or PostgreSQL.
2. Install the `proxy-addon` jar on Velocity.
3. Enable `cross-server.enabled: true` on every backend.
4. Restart the proxy and every backend after changing those settings.

## Storage Choice

### SQLite

Use SQLite unless you have a reason not to.

- Default backend.
- Easy to back up.
- Uses WAL mode.
- Good fit for a local single-server economy.

### H2

Use H2 only if you prefer that file format.

- Supported.
- Still local-only.
- Does not change the single-server design.

### Remote JDBC Backends

Use MySQL, MariaDB, or PostgreSQL only when you need one shared database for backend handoff.

- Required for proxy-assisted cross-server mode.
- Still not a distributed ledger.
- You should assume one active writer per player account during normal play.

### Backend Changes

Changing `storage.type` or changing the configured database file name does not move old data.

Safe process:

1. Stop the server.
2. Back up `plugins/OpenEco/`.
3. Move or copy the old database yourself.
4. Change config.
5. Start the server and verify balances and history before reopening.

## Recommended Starting Values

These are starting points, not rules.

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

Notes:

- Lower `autosave-interval` reduces worst-case balance loss after an unclean stop, but increases write pressure.
- `pay.min-amount` helps prevent spammy micro-transfers.
- `history.retention-days: -1` keeps all history.
- A positive `history.retention-days` deletes old history in the background.

## Backups

Best method:

1. Stop the server.
2. Copy the full `plugins/OpenEco/` directory.

If you back up SQLite while the server is running, copy all of these together:

- `economy.db`
- `economy.db-wal`
- `economy.db-shm`

After a restore, verify:

1. `/balance` for known players
2. `/history` for known players
3. `/baltop`
4. Any PlaceholderAPI or Vault-based plugin that uses the economy

## History And File Growth

- History entries are created for normal balance changes and payments.
- Deleting an account deletes that account's own history.
- `history.retention-days` can prune old rows.
- SQLite file size may not shrink after deletes until a manual `VACUUM` during maintenance.

## Crash Semantics

- Balance mutations are applied in memory immediately.
- Dirty balances are flushed on the autosave interval and on normal shutdown.
- An unclean stop can lose up to one autosave interval of recent balance changes.
- In network mode, backend handoff also requests an immediate flush, but that is still operationally best-effort rather than a global real-time replication system.

## Network Mode Checklist

Use this only when you need backend handoff on a proxy network.

1. Shared backend must be MySQL, MariaDB, or PostgreSQL.
2. Install `proxy-addon` on Velocity.
3. Enable `cross-server.enabled: true` on all backends.
4. Restart everything after toggling cross-server mode.
5. Test at least one server switch, one disconnect, and one manual `/ecosync <player>` before trusting it in production.

## Rollout Advice

1. Use staging first.
2. Test with the real plugins that call Vault.
3. Keep backups before changing storage settings.
4. Treat backend changes as maintenance, not live toggles.