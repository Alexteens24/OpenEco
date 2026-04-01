# SimpleEco

Single-server economy plugin for Paper/Folia servers. SimpleEco is designed around an in-memory account cache with local embedded persistence and a VaultUnlocked v2-compatible economy provider.

## Requirements

- Paper or Folia 1.21+
- [VaultUnlocked](https://github.com/TheNewEconomy/VaultUnlocked) (hard dependency, loaded by Paper as plugin `Vault`)
- [PlaceholderAPI](https://placeholderapi.com/) (optional)

## Production Deployment

1. Upload only `SimpleEco-<version>.jar` from `build/libs/` to the server `plugins/` folder.
2. Install VaultUnlocked. On the server plugin list it appears as `Vault`, and SimpleEco depends on that runtime plugin name.
3. Start the server once so `plugins/SimpleEco/config.yml` is generated.
4. Stop the server and review storage, autosave, pay, and message settings before opening the server to players.
5. Back up the generated `plugins/SimpleEco/` directory before significant config or backend changes.
6. Restart the server and verify that SimpleEco enables cleanly.

Production notes:

- SimpleEco is intended for a single server with local embedded storage. It does not provide cross-server synchronization.
- Switching between SQLite and H2, or changing the configured file name, does not migrate existing data automatically.
- Do not deploy the `stress-addon` module on a production server. It is for staging and load testing only.

## Commands

| Command | Description | Permission |
|---|---|---|
| `/balance [player]` | Check balance | `simpleeco.command.balance` |
| `/baltop [page]` | Richest players leaderboard | `simpleeco.command.baltop` |
| `/pay <player> <amount>` | Send money to a player | `simpleeco.command.pay` |
| `/eco give <player> <amount>` | Give money | `simpleeco.command.eco.give` |
| `/eco take <player> <amount>` | Take money | `simpleeco.command.eco.take` |
| `/eco set <player> <amount>` | Set balance | `simpleeco.command.eco.set` |
| `/eco reset <player>` | Reset to starting balance | `simpleeco.command.eco.reset` |
| `/eco delete <player>` | Delete account and personal history | `simpleeco.command.eco.delete` |
| `/eco reload` | Reload config | `simpleeco.command.eco.reload` |
| `/history [player] [page]` | Transaction history | `simpleeco.command.history` |

`simpleeco.admin` grants all admin permissions at once.

## Operations

- Keep regular backups of `plugins/SimpleEco/`, especially before changing storage settings or running large-scale cleanup.
- For SQLite, `economy.db`, `economy.db-wal`, and `economy.db-shm` are normal when WAL mode is enabled.
- Transaction history is append-only during normal operation. Large test runs or busy production servers will grow the database over time.
- Use [docs/production.md](docs/production.md) for backend selection, backup guidance, and production rollout notes.

## Building

```bash
./gradlew build
```

Output: `build/libs/SimpleEco-<version>.jar`

## Guides

- [Production Guide](docs/production.md)
- [Configuration](docs/configuration.md)
- [Addon API](docs/api.md)
- [Permissions](docs/permissions.md)
- [PlaceholderAPI](docs/placeholders.md)
- [Technical Details](docs/technical.md)
