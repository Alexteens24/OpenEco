# Configuration

The `config.yml` file lives in `plugins/OpenEco/`. OpenEco auto-migrates legacy `currency.*` keys into `currencies.*` on startup and `/eco reload`.

Click any option below to view additional information.

::: tip Apply most changes without a restart
After editing `config.yml`, run `/eco reload` to apply messages and most runtime rules. Storage backends and `cross-server.enabled` still require a restart.
:::

<ConfigGroup name="currencies">

<ConfigProperty name="default" value="openeco" type="string">
Default currency for commands, Vault v1, PlaceholderAPI placeholders without a suffix, and API methods that do not take an explicit `currencyId`.
</ConfigProperty>

<ConfigGroup name="definitions">

<ConfigGroup name="&lt;currency-id&gt;">

<ConfigProperty name="name-singular" value="Dollar" type="string">
Display name for a single unit.
</ConfigProperty>

<ConfigProperty name="name-plural" value="Dollars" type="string">
Display name for multiple units.
</ConfigProperty>

<ConfigProperty name="decimal-digits" value="2" type="number">
Number of decimal places (0–8). Controls rounding and formatting.
</ConfigProperty>

<ConfigProperty name="starting-balance" value="0.00" type="number">
Balance given to new accounts and used by `/eco reset`.
</ConfigProperty>

<ConfigProperty name="max-balance" value="-1" type="number">
Maximum balance a player can hold. `-1` means unlimited.
</ConfigProperty>

</ConfigGroup>

</ConfigGroup>

</ConfigGroup>

<ConfigGroup name="storage">

<ConfigProperty name="type" value="sqlite" type="string">
Backend: `sqlite`, `h2`, `mysql`, `mariadb`, or `postgresql`. Changing type does not move data — use `/openecomigrate`. **Restart required.**
</ConfigProperty>

<ConfigGroup name="sqlite">

<ConfigProperty name="file" value="economy.db" type="string">
SQLite database file name under `plugins/OpenEco/`. WAL sidecar files (`-wal`, `-shm`) are normal while running.
</ConfigProperty>

</ConfigGroup>

<ConfigGroup name="h2">

<ConfigProperty name="file" value="economy" type="string">
H2 file base name (without extension). H2 appends `.mv.db` automatically. Local-only.
</ConfigProperty>

</ConfigGroup>

<ConfigGroup name="mysql">

<ConfigProperty name="host" value="localhost" type="string">Database host.</ConfigProperty>
<ConfigProperty name="port" value="3306" type="number">Database port.</ConfigProperty>
<ConfigProperty name="database" value="openeco" type="string">Database name.</ConfigProperty>
<ConfigProperty name="username" value="root" type="string">Connection username.</ConfigProperty>
<ConfigProperty name="password" value="" type="string">Connection password.</ConfigProperty>
<ConfigProperty name="pool-size" value="10" type="number">HikariCP pool size for remote backends.</ConfigProperty>

</ConfigGroup>

<ConfigGroup name="mariadb">

<ConfigProperty name="host" value="localhost" type="string">Database host.</ConfigProperty>
<ConfigProperty name="port" value="3306" type="number">Database port.</ConfigProperty>
<ConfigProperty name="database" value="openeco" type="string">Database name.</ConfigProperty>
<ConfigProperty name="username" value="root" type="string">Connection username.</ConfigProperty>
<ConfigProperty name="password" value="" type="string">Connection password.</ConfigProperty>
<ConfigProperty name="pool-size" value="10" type="number">HikariCP pool size.</ConfigProperty>

</ConfigGroup>

<ConfigGroup name="postgresql">

<ConfigProperty name="host" value="localhost" type="string">Database host.</ConfigProperty>
<ConfigProperty name="port" value="5432" type="number">Database port.</ConfigProperty>
<ConfigProperty name="database" value="openeco" type="string">Database name.</ConfigProperty>
<ConfigProperty name="username" value="postgres" type="string">Connection username.</ConfigProperty>
<ConfigProperty name="password" value="" type="string">Connection password.</ConfigProperty>
<ConfigProperty name="pool-size" value="10" type="number">HikariCP pool size.</ConfigProperty>

</ConfigGroup>

<ConfigGroup name="migration">

<ConfigProperty name="source-type" value="sqlite" type="string">
Optional local backup source when the server already runs on a remote backend.
</ConfigProperty>

<ConfigProperty name="source-folder" value="" type="string">
Folder containing the source database file. Leave empty to use the active local database.
</ConfigProperty>

<ConfigProperty name="source-file" value="" type="string">
Source database file name for storage migration helpers.
</ConfigProperty>

</ConfigGroup>

</ConfigGroup>

<ConfigProperty name="autosave-interval" value="30" type="number">
Seconds between automatic background saves. Values ≤ 0 are clamped to 1. Lower values reduce crash loss window but increase write pressure.
</ConfigProperty>

<ConfigGroup name="pay">

<ConfigProperty name="cooldown-seconds" value="0" type="number">
Cooldown between `/pay` uses in seconds. `0` disables cooldown.
</ConfigProperty>

<ConfigProperty name="tax-percent" value="0.0" type="number">
Percentage of the sent amount deducted as tax. `0.0` disables tax.
</ConfigProperty>

<ConfigProperty name="min-amount" value="0.01" type="number">
Minimum amount per `/pay` transaction. `0` means no minimum.
</ConfigProperty>

</ConfigGroup>

<ConfigGroup name="baltop">

<ConfigProperty name="page-size" value="10" type="number">
Number of entries per `/baltop` page.
</ConfigProperty>

<ConfigProperty name="cache-ttl-seconds" value="30" type="number">
Seconds to cache the sorted leaderboard. Also affects PlaceholderAPI top placeholders.
</ConfigProperty>

</ConfigGroup>

<ConfigGroup name="history">

<ConfigProperty name="retention-days" value="-1" type="number">
Days to keep transaction history. `≤ 0` keeps all history with no pruning.
</ConfigProperty>

</ConfigGroup>

<ConfigGroup name="cross-server">

<ConfigProperty name="enabled" value="false" type="boolean">
Enable proxy-assisted account handoff sync. Requires MySQL, MariaDB, or PostgreSQL on every backend. **Restart required.** Do not enable on single-server setups.
</ConfigProperty>

</ConfigGroup>

## Messages

The `messages` section contains MiniMessage-formatted chat strings. Placeholders like `<player>`, `<balance>`, `<amount>`, and `<currency>` are replaced at runtime.

Run `/eco reload` after editing messages. No restart needed.

## Full example

```yaml
currencies:
  default: openeco
  definitions:
    openeco:
      name-singular: "Dollar"
      name-plural: "Dollars"
      decimal-digits: 2
      starting-balance: 0.00
      max-balance: -1

storage:
  type: sqlite
  sqlite:
    file: economy.db

autosave-interval: 30

pay:
  cooldown-seconds: 0
  tax-percent: 0.0
  min-amount: 0.01

baltop:
  page-size: 10
  cache-ttl-seconds: 30

history:
  retention-days: -1

cross-server:
  enabled: false
```

See [Production guide](/docs/production) for recommended starting values on live servers.
