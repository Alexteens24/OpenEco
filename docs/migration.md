# Migration Guide

OpenEco supports two migration paths:

1. **Economy plugin import** — move player balances from another economy plugin into OpenEco (`OpenEcoMigrator` addon).
2. **Storage backend import** — copy OpenEco's own data from a local SQLite/H2 file into MySQL, MariaDB, or PostgreSQL (built into the main plugin).

Both are admin-only, maintenance-window operations. Back up before you run them.

## Before You Start

1. Stop player traffic or put the server in maintenance mode.
2. Back up `plugins/openeco/` (and any source plugin folders you import from).
3. Run `scan`, then `run` with `--dry-run`, then `run` without flags.
4. Spot-check balances and `/history` for a few players before reopening.

---

## Economy Plugin Import (OpenEcoMigrator)

Install **OpenEco** and **OpenEcoMigrator** together. The migrator is a separate jar built from the `migrator-addon` module:

```bash
./gradlew shadowJar :migrator-addon:jar
```

Copy `build/libs/OpenEco-<version>.jar` and `migrator-addon/build/libs/OpenEcoMigrator-<version>.jar` into `plugins/`.

### Commands

| Command | Description |
|---|---|
| `/openemomigrate list` | List supported sources and auto-detect status |
| `/openemomigrate scan <source>` | Count accounts and total balance |
| `/openemomigrate run <source> [--dry-run] [--overwrite]` | Import into OpenEco |

Aliases: `/oemigrate`, `/ecoimport`

Permission: `openeco.migrator.admin` (default: op)

### Supported sources

| Source ID | Plugin | Data location |
|---|---|---|
| `essentials` | EssentialsX | `plugins/Essentials/userdata/*.yml` (`money`, `last-account-name`) |
| `cmi` | CMI | SQLite `users` / `CMI_users` table under `plugins/CMI/` |
| `liteeco` | LiteEco | `plugins/LiteEco/database.db` |
| `xconomy` | XConomy | `plugins/XConomy/playerdata/.../data.db` |
| `boseconomy` | BOSEconomy7 | SQLite under `plugins/BOSEconomy/` (`accounts.db`, etc.) |
| `vault` | Any Vault economy | Active Vault provider (see limitations below) |

Alias examples: `ess`, `essentialsx`, `lite`, `xcon`, `bose`.

### Addon config

File: `plugins/OpenEcoMigrator/config.yml`

```yaml
target-currency: openeco

paths:
  essentials-userdata: ""
  cmi-database: ""
  liteeco-database: ""
  xconomy-database: ""
```

- `target-currency` must match a currency id in OpenEco's `currencies.definitions`.
- Leave path overrides empty to auto-detect under `plugins/`.

### Recommended workflow

```
1. Install OpenEco + OpenEcoMigrator; keep the old economy plugin installed until import succeeds.
2. /openemomigrate list
3. /openemomigrate scan essentials
4. /openemomigrate run essentials --dry-run
5. /openemomigrate run essentials
6. Verify /balance for several players.
7. Disable or remove the old economy plugin; ensure OpenEco is the Vault provider.
```

Use `--overwrite` only when you intentionally replace balances for accounts that already exist in OpenEco.

### Limitations

- **File/database readers** (Essentials, CMI, LiteEco, XConomy, BOSEconomy) work while OpenEco is already active. They read from disk; the old plugin does not need to be running.
- **Vault** only works when the *source* economy is still registered as the Vault provider and is **not** OpenEco. After switching to OpenEco, use a file/database source instead.
- **MySQL-backed CMI / XConomy / LiteEco** on remote hosts are not imported directly; copy or export the SQLite file locally first, or use a file-based source.
- **TNE, PlayerPoints**, and other plugins are not supported yet.

---

## Storage Backend Import (`/openecostorage`)

Built into the main OpenEco jar. Copies `accounts`, `account_balances`, and `transactions` from the active local database (or a backup file) into a remote JDBC backend.

### Commands

| Command | Description |
|---|---|
| `/openecostorage list` | Show supported source and target backends |
| `/openecostorage scan <mysql\|mariadb\|postgresql>` | Count rows in source and target |
| `/openecostorage migrate <target> [--dry-run] [--overwrite]` | Copy data into the remote database |

Aliases: `/ecostorage`, `/openecodb`

Permission: `openeco.command.storage` (default: op; included in `openeco.admin`)

### Config

Remote connection settings use the existing `storage.mysql`, `storage.mariadb`, and `storage.postgresql` blocks in `plugins/openeco/config.yml`.

Optional backup source (when the server already runs on a remote backend):

```yaml
storage:
  migration:
    source-type: sqlite    # sqlite or h2
    source-folder: ""      # empty = plugins/openeco/
    source-file: ""        # empty = active sqlite/h2 file from storage.sqlite.file / storage.h2.file
```

### Recommended workflow (SQLite → MySQL)

```
1. Back up plugins/openeco/.
2. Configure storage.mysql.* (host, port, database, credentials).
3. Keep storage.type: sqlite while migrating.
4. /openecostorage scan mysql
5. /openecostorage migrate mysql --dry-run
6. /openecostorage migrate mysql --overwrite   # if the target DB already has test data
7. Change storage.type: mysql in config.yml.
8. Restart the server.
9. Verify balances and /history.
```

Use `--overwrite` when the target database already contains OpenEco tables with data. Without it, migration aborts if the target is non-empty.

### What gets copied

- All account rows (UUID, name, balances per currency, frozen flag, timestamps).
- All transaction history rows.

### Limitations

- Source must be **sqlite** or **h2** (active backend or `storage.migration` backup path).
- Target must be **mysql**, **mariadb**, or **postgresql**.
- Does not migrate from remote-to-remote or local-to-local; use database tools for those cases.
- Run while the server is up; the command flushes dirty balances before reading the source. Prefer low traffic.
- After migration, you must change `storage.type` and **restart**; `/eco reload` is not enough.

---

## Combining Both Migrations

Typical server switch:

1. Import balances from the old economy plugin with **OpenEcoMigrator** (OpenEco on SQLite).
2. Move OpenEco storage to MySQL/MariaDB/PostgreSQL with **`/openecostorage`** if you need a shared remote database for proxy handoff.

Do not run both importers against the same accounts unless you understand the overwrite behavior.
