# OpenEcoMigrator

Addon for [OpenEco](../README.md) that imports player balances from other economy plugins.

Requires **OpenEco** on the same server.

## Build

```bash
./gradlew :migrator-addon:jar
```

Output: `migrator-addon/build/libs/OpenEcoMigrator-<version>.jar`

## Install

1. Copy `OpenEcoMigrator-<version>.jar` into `plugins/` next to OpenEco.
2. Restart the server.
3. Edit `plugins/OpenEcoMigrator/config.yml` if you need `target-currency` or custom data paths.

## Commands

| Command | Description |
|---|---|
| `/openemomigrate list` | Supported sources |
| `/openemomigrate scan <source>` | Preview account count and total balance |
| `/openemomigrate run <source> [--dry-run] [--overwrite]` | Import into OpenEco |

Permission: `openeco.migrator.admin`

## Supported sources

`essentials`, `cmi`, `liteeco`, `xconomy`, `boseconomy`, `tne`, `playerpoints`, `vault`

Full details, limitations, and workflows: [Migration Guide](../docs/migration.md).
