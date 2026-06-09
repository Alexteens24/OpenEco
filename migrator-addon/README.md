# OpenEcoMigrator

Addon for [OpenEco](../README.md) that imports player balances from other economy plugins.

Requires **OpenEco** on the same server. Registers economy sources with `/openecomigrate`.

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

All migration commands are on the main plugin:

```
/openecomigrate <source> [--scan] [--dry-run] [--overwrite]
```

Economy sources: `essentials`, `cmi`, `liteeco`, `xconomy`, `boseconomy`, `tne`, `playerpoints`, `vault`

Permission: `openeco.migrator.admin`

Full details: [Migration Guide](../docs/migration.md).
