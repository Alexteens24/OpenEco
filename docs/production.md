# Production Guide

SimpleEco is ready for single-server production use on Paper or Folia, but it is still an embedded-storage plugin. Treat deployment and operations like a local stateful service, not a stateless utility jar.

## Deployment model

- Single server only. There is no shared-database synchronization, distributed cache, or cross-server balance propagation.
- Local embedded persistence only: SQLite or H2 files under `plugins/SimpleEco/`.
- No automatic data migration between backends or file names.
- Transaction history is append-only during normal operation and will grow over time.

## Preflight checklist

1. Java 21 is installed on the host.
2. Paper or Folia 1.21+ is installed.
3. VaultUnlocked is installed. On Paper it is loaded as plugin `Vault`.
4. Only the main SimpleEco jar is deployed on production. The `stress-addon` jar should stay on staging only.
5. A backup plan exists for `plugins/SimpleEco/`.

## Backend selection

### SQLite

Use SQLite when you want the default, lowest-friction setup for a small or medium single server.

- Default backend.
- Uses WAL mode.
- Easy to back up and inspect.
- Good fit for the current SimpleEco architecture.

### H2

Use H2 when you specifically prefer an embedded Java database file format and still want a local-only deployment.

- Also supported and tested.
- Still local-only, still single-server.
- Does not solve cross-server or shared-state requirements.

### Switching backends

Changing `storage.type` or changing the configured database filename does not migrate data automatically.

Recommended process:

1. Stop the server.
2. Back up `plugins/SimpleEco/`.
3. Migrate or archive the old database manually.
4. Update config.
5. Start the server and validate balances/history before opening to players.

## Recommended production settings

These are starting points, not hard rules.

```yaml
autosave-interval: 10-30

pay:
  cooldown-seconds: 0-5
  tax-percent: 0.0-5.0
  min-amount: 0.01-1.00

baltop:
  cache-ttl-seconds: 15-60
```

Guidance:

- Lower `autosave-interval` reduces worst-case account data loss after a crash, but increases write pressure.
- `pay.min-amount` is useful to suppress spammy micro-transfers.
- `baltop.cache-ttl-seconds` trades leaderboard freshness for lower repeated sort cost.

## Backups and recovery

Preferred backup method:

1. Stop the server.
2. Copy the full `plugins/SimpleEco/` directory.

If you must copy while the server is running and you use SQLite, copy all SQLite files together:

- `economy.db`
- `economy.db-wal`
- `economy.db-shm`

After restore, start the server and validate:

1. balances for several known accounts
2. `/history` for several known accounts
3. `/baltop`
4. PlaceholderAPI placeholders if you use them

## History growth

SimpleEco keeps transaction history for normal operations until the account is deleted. This is operationally simple, but storage grows with usage.

Production implications:

- Stress tests can grow SQLite/H2 files quickly.
- Even after deleting records, SQLite file size may not shrink immediately.
- Large cleanup operations may require a manual `VACUUM` on SQLite during maintenance windows.

If transaction history retention matters operationally, plan for one of these later:

1. periodic archive/export
2. retention/prune tooling
3. moving to a storage design intended for longer-lived analytics/history

## Release hardening before public rollout

1. Run a normal restart and verify balances after startup.
2. Run a staged load test without the stress addon installed on production.
3. Test with your real Vault-consuming plugins, not just SimpleEco in isolation.
4. Confirm `/eco reload` behavior on your final config.
5. Confirm backups can actually be restored.

## When not to use SimpleEco as-is

Reconsider the current architecture if you need:

1. network-wide balances across multiple servers
2. shared database writes from multiple JVMs
3. unbounded long-term history growth without archive/retention tooling
4. multi-currency or shared-account semantics far beyond the current model