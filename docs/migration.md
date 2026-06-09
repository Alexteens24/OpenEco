# Migration Guide

OpenEco uses one admin command for every migration path:

```
/openecomigrate <source> [--scan] [--dry-run] [--overwrite]
```

Permission: `openeco.migrator.admin` (default: op)

**Economy plugin import** needs the **OpenEcoMigrator** addon. **Storage migration** is built into the main OpenEco jar.

Back up before you run anything. Prefer maintenance mode.

## Before You Start

1. Stop player traffic or put the server in maintenance mode.
2. Back up `plugins/openeco/` (and any source plugin folders you import from).
3. Run `--scan`, then `--dry-run`, then the command without flags.
4. Spot-check balances and `/history` for a few players before reopening.

---

## Economy plugin import

Install **OpenEco** and **OpenEcoMigrator** together:

```bash
./gradlew shadowJar :migrator-addon:jar
```

Copy both jars into `plugins/`.

### Usage

| Command | Description |
|---|---|
| `/openecomigrate` | Show help and list sources |
| `/openecomigrate <source> --scan` | Count accounts and total balance |
| `/openecomigrate <source> --dry-run` | Preview import without writing |
| `/openecomigrate <source>` | Import into OpenEco |
| `/openecomigrate <source> --overwrite` | Replace existing OpenEco balances |

### Supported economy sources

| Source | Plugin | Data location |
|---|---|---|
| `essentials` | EssentialsX | `plugins/Essentials/userdata/*.yml` |
| `cmi` | CMI | SQLite under `plugins/CMI/` |
| `liteeco` | LiteEco | `plugins/LiteEco/database.db` |
| `xconomy` | XConomy | `plugins/XConomy/playerdata/.../data.db` |
| `boseconomy` | BOSEconomy7 | SQLite under `plugins/BOSEconomy/` |
| `tne` | TheNewEconomy | YAML `accounts/*.yml` or SQLite |
| `playerpoints` | PlayerPoints | SQLite or legacy `storage.yml` |
| `vault` | Any Vault economy | Active Vault provider (see limitations) |

Alias examples: `ess`, `lite`, `xcon`, `bose`, `theneweconomy`, `pp`.

### Addon config

File: `plugins/OpenEcoMigrator/config.yml` — `target-currency` and optional `paths.*` overrides.

### Example workflow

```
/openecomigrate essentials --scan
/openecomigrate essentials --dry-run
/openecomigrate essentials
```

### Limitations

- File/database readers work while OpenEco is active; the old plugin does not need to run.
- **Vault** only works when the source economy is still the active Vault provider.
- Remote MySQL data for CMI / XConomy / LiteEco / TNE / PlayerPoints: copy the SQLite file locally first.
- **TNE multi-currency** is merged into one OpenEco currency (VIRTUAL holdings preferred).
- **PlayerPoints** legacy username-only rows are skipped.

---

## Storage backend migration

Built into OpenEco. Uses `storage.mysql` connection settings in `plugins/openeco/config.yml`.

### Usage

| Command | Description |
|---|---|
| `/openecomigrate sqlitetomysql --scan` | Count rows in local SQLite and remote MySQL |
| `/openecomigrate sqlitetomysql --dry-run` | Preview copy to MySQL |
| `/openecomigrate sqlitetomysql --overwrite` | Copy data into MySQL |
| `/openecomigrate mysqltosqlite --scan` | Count rows when exporting MySQL to local file |
| `/openecomigrate mysqltosqlite --overwrite` | Copy MySQL data into local SQLite |

After `sqlitetomysql`, set `storage.type: mysql` and restart. After `mysqltosqlite`, set `storage.type: sqlite` and restart.

Optional backup source when the server already runs on MySQL:

```yaml
storage:
  migration:
    source-type: sqlite
    source-folder: ""
    source-file: ""
```

---

## Typical server cutover

1. Import economy data with `/openecomigrate <plugin>`.
2. Move storage with `/openecomigrate sqlitetomysql` if you need a shared remote database.
3. Switch Vault to OpenEco and remove the old economy plugin.
