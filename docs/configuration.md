# Configuration

Config file: `plugins/OpenEco/config.yml`  
Reload command: `/eco reload`

`/eco reload` refreshes messages and most runtime rules.

Treat these as restart-required settings:

- `storage.type`
- local database file names
- remote database connection targets
- `cross-server.enabled`

## Currency

```yaml
currencies:
  default: "openeco"
  definitions:
    openeco:
      name-singular: "Dollar"
      name-plural: "Dollars"
      decimal-digits: 2
      starting-balance: 0.00
      max-balance: -1
    gems:
      name-singular: "Gem"
      name-plural: "Gems"
      decimal-digits: 0
      starting-balance: 0
      max-balance: -1
```

Notes:

- `currencies.default` is the currency used by commands, placeholders, Vault v1, and API methods that do not take an explicit `currencyId`.
- Every configured currency has its own `decimal-digits`, `starting-balance`, and `max-balance`.
- `decimal-digits` controls rounding and display.
- `starting-balance` affects new accounts and `/eco reset`.
- `max-balance: -1` means unlimited.
- The legacy `currency.*` block is still accepted for backward compatibility, but new configs should use `currencies.*`.
- Reloading after changing `currencies.default` switches the default-currency wrappers immediately. Named balances stay attached to their own currency ids.
- OpenEco also migrates the saved file on startup and `/eco reload`: it adds missing defaults, rewrites legacy `currency.*` into `currencies.*`, and removes the deprecated keys from the file.

## Accounts

```yaml
accounts:
  load-strategy: eager
```

Notes:

- `eager` loads every stored account into memory at startup. This is the safest and most predictable choice.
- `lazy` skips startup preload and loads accounts on first access, with repository fallback for name and identity lookups.
- Use `lazy` if you want to reduce startup work on a server with many accounts. It trades that for first-access database reads.
- Treat `accounts.load-strategy` as a startup setting. If you change it, restart the server so the in-memory registry matches the selected mode.

## Storage

```yaml
storage:
  type: sqlite
  sqlite:
    file: economy.db
  h2:
    file: economy
  mysql:
    host: localhost
    port: 3306
    database: openeco
  mariadb:
    host: localhost
    port: 3306
    database: openeco
  postgresql:
    host: localhost
    port: 5432
    database: openeco
```

Notes:

- `sqlite` and `h2` are local file backends under `plugins/openeco/`.
- `mysql`, `mariadb`, and `postgresql` are remote JDBC backends.
- `sqlite` is the default.
- Changing `storage.type` or the configured file name does not move data by itself. Use `/openecomigrate sqlitetomysql` or follow [Migration Guide](migration.md).

### Storage migration settings

```yaml
storage:
  migration:
    source-type: sqlite
    source-folder: ""
    source-file: ""
```

Notes:

- Used by `/openecomigrate sqlitetomysql` when importing from a local backup while the server already runs on a remote backend.
- Leave `source-folder` and `source-file` empty to read from the active local database.
- See [Migration Guide](migration.md) for the full workflow.

Remote backend troubleshooting:

- MySQL 8 defaults to `caching_sha2_password`. OpenEco enables `allowPublicKeyRetrieval` for local/non-TLS setups. For production, prefer TLS (`useSSL=true` on the server side) instead of relying on RSA key retrieval over plain connections.
- Use MySQL 8.0.13+ for remote storage. Older MySQL versions do not support the functional name index OpenEco creates for case-insensitive lookups.
- MariaDB keeps the older `VALUES()` upsert syntax because MariaDB does not yet support MySQL's `AS alias` upsert form.
- Ensure the database server uses a UTF-8 charset (`utf8mb4` on MySQL/MariaDB). OpenEco requests `UTF-8` on the JDBC connection.

## Cross-Server Handoff

```yaml
cross-server:
  enabled: false
```

Notes:

- This mode is only for networks with a shared remote database and the optional Velocity proxy addon.
- It flushes a player's account during backend handoff and reloads it on the destination backend.
- It does not push live balance updates to every backend.
- It does not make simultaneous writes to the same account from multiple live servers safe.
- Do not use it with SQLite or H2.
- Toggling this setting requires a full restart; `/eco reload` is not enough.

## Auto-Save

```yaml
autosave-interval: 30
```

Notes:

- Value is in seconds.
- Values less than 1 are clamped to 1.
- Lower values reduce possible loss after an unclean stop, but write more often.
- This still matters in network mode because the periodic flush is separate from proxy handoff sync.

## Pay

```yaml
pay:
  cooldown-seconds: 0
  tax-percent: 0.0
  min-amount: 0.01
```

Notes:

- `cooldown-seconds: 0` disables cooldown.
- `tax-percent` is deducted from the sent amount before the receiver is credited.
- `min-amount` rejects transfers smaller than the configured value after scaling.

Example: `tax-percent: 5.0` means sending 100 deducts 100 from the sender and credits 95 to the receiver.

## Baltop

```yaml
baltop:
  page-size: 10
  cache-ttl-seconds: 30
```

Notes:

- Higher cache TTL lowers repeated sort cost.
- Higher cache TTL also means slightly older leaderboard views.

## History

```yaml
history:
  retention-days: -1
```

Notes:

- Any value less than or equal to `0` keeps all history.
- A positive value deletes older history rows in the background.
- Deleting rows does not guarantee the SQLite file shrinks immediately.

## Messages

Messages use [MiniMessage](https://docs.advntr.dev/minimessage/format.html).

Keep these warnings present unless you replace them with equivalent text:

- `account-sync-failed`
- `account-name-conflict`

All message keys and their placeholders:

| Key | Placeholders |
|---|---|
| `no-permission` | *(none)* |
| `console-player-only` | *(none)* |
| `account-not-found` | `<player>` |
| `account-sync-failed` | *(none)* |
| `account-name-conflict` | `<player>` |
| `account-frozen` | *(none)* |
| `self-pay` | *(none)* |
| `insufficient-funds` | *(none)* |
| `unknown-currency` | *(none)* |
| `invalid-amount` | *(none)* |
| `negative-amount` | *(none)* |
| `balance-self` | `<balance>` |
| `balance-other` | `<player>`, `<balance>` |
| `pay-sent` | `<player>`, `<amount>` |
| `pay-received` | `<player>`, `<amount>` |
| `pay-tax` | `<tax>` |
| `pay-cooldown` | `<seconds>` |
| `pay-balance-limit` | `<player>` |
| `pay-too-low` | `<min>` |
| `pay-cancelled` | *(none)* |
| `eco-give` | `<player>`, `<amount>`, `<balance>` |
| `eco-give-failed` | `<player>` |
| `eco-take` | `<player>`, `<amount>`, `<balance>` |
| `eco-take-failed` | `<player>` |
| `eco-set` | `<player>`, `<balance>` |
| `eco-set-failed` | `<player>` |
| `eco-reset` | `<player>`, `<balance>` |
| `eco-delete` | `<player>` |
| `eco-delete-failed` | `<player>` |
| `eco-freeze` | `<player>` |
| `eco-freeze-failed` | `<player>` |
| `eco-unfreeze` | `<player>` |
| `eco-unfreeze-failed` | `<player>` |
| `eco-balance-limit` | `<player>`, `<limit>` |
| `eco-rename` | `<old_name>`, `<new_name>` |
| `eco-rename-name-in-use` | `<new_name>` |
| `eco-rename-invalid` | `<new_name>` |
| `eco-rename-failed` | `<player>` |
| `eco-reload` | *(none)* |
| `baltop-header` | `<page>`, `<total>` |
| `baltop-entry` | `<rank>`, `<player>`, `<balance>` |
| `history-header` | `<player>`, `<page>`, `<total>` |
| `history-empty` | *(none)* |
| `history-error` | *(none)* |
| `history-give` | `<date>`, `<amount>` |
| `history-take` | `<date>`, `<amount>` |
| `history-set` | `<date>`, `<balance>` |
| `history-reset` | `<date>`, `<balance>` |
| `history-pay-sent` | `<date>`, `<amount>`, `<counterpart>` |
| `history-pay-received` | `<date>`, `<amount>`, `<counterpart>` |
| `history-custom` | `<date>`, `<kind>`, `<amount>`, `<details>`, `<source>`, `<note>`, `<balance>` |
