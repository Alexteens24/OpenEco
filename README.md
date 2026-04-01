# SimpleEco

[![CI](https://github.com/Alexteens24/SimpleEco/actions/workflows/ci.yml/badge.svg)](https://github.com/Alexteens24/SimpleEco/actions/workflows/ci.yml)

SimpleEco is an economy plugin for one Paper or Folia server.

What it does:

- Keeps balances in memory for fast reads and writes.
- Stores data locally in SQLite or H2 under `plugins/SimpleEco/`.
- Exposes Vault v1 and VaultUnlocked v2 economy providers.
- Supports PlaceholderAPI if it is installed.

What it does not do:

- Cross-server or proxy-wide balance sync.
- Automatic migration between storage backends or file names.
- Shared database access across multiple JVMs.

## Requirements

- Paper 1.20.5+ is confirmed to load
- Folia 1.21+ is the current safe baseline
- [VaultUnlocked](https://github.com/TheNewEconomy/VaultUnlocked)
- [PlaceholderAPI](https://placeholderapi.com/) if you want placeholders

VaultUnlocked is loaded by Paper as plugin `Vault`. SimpleEco depends on that runtime name.

## Install

1. Put `SimpleEco-<version>.jar` in `plugins/`.
2. Install VaultUnlocked.
3. Start the server once to generate `plugins/SimpleEco/config.yml`.
4. Stop the server and review the config.
5. Back up `plugins/SimpleEco/` before opening the server.
6. Start the server again and verify `/balance`, `/baltop`, and `/history`.

## Commands

| Command | Use | Permission |
|---|---|---|
| `/balance [player]` | Check balance | `simpleeco.command.balance` |
| `/baltop [page]` | View leaderboard | `simpleeco.command.baltop` |
| `/pay <player> <amount>` | Send money | `simpleeco.command.pay` |
| `/eco give <player> <amount>` | Give money | `simpleeco.command.eco.give` |
| `/eco take <player> <amount>` | Take money | `simpleeco.command.eco.take` |
| `/eco set <player> <amount>` | Set balance | `simpleeco.command.eco.set` |
| `/eco reset <player>` | Reset to starting balance | `simpleeco.command.eco.reset` |
| `/eco delete <player>` | Delete an account and that account's history | `simpleeco.command.eco.delete` |
| `/eco reload` | Reload config and messages | `simpleeco.command.eco.reload` |
| `/history [player] [page]` | View transaction history | `simpleeco.command.history` |

`simpleeco.admin` grants all admin permissions.

## Owner Notes

- SimpleEco is meant for one server with local storage.
- SQLite companion files such as `economy.db-wal` and `economy.db-shm` are normal while the server is running.
- Balance data is flushed periodically and on normal shutdown.
- History can be kept forever or pruned with `history.retention-days`.

## Guides

- [Production Guide](docs/production.md)
- [Developer Guide](docs/development.md)
- [Configuration](docs/configuration.md)
- [Permissions](docs/permissions.md)
- [PlaceholderAPI](docs/placeholders.md)
- [Addon API](docs/api.md)
- [Technical Notes](docs/technical.md)

## Build From Source

```bash
./gradlew build
```

Output: `build/libs/SimpleEco-<version>.jar`

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE).
