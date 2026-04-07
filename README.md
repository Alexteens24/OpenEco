# OpenEco

[![CI](https://github.com/Alexteens24/OpenEco/actions/workflows/ci.yml/badge.svg)](https://github.com/Alexteens24/OpenEco/actions/workflows/ci.yml)

OpenEco is an economy plugin for one Paper or Folia server.

What it does:

- Keeps balances in memory for fast reads and writes.
- Stores data locally in SQLite or H2 under `plugins/OpenEco/`.
- Supports multiple named currencies with a configurable default-currency compatibility layer.
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

VaultUnlocked is loaded by Paper as plugin `Vault`. OpenEco depends on that runtime name.

## Install

1. Put `OpenEco-<version>.jar` in `plugins/`.
2. Install VaultUnlocked.
3. Start the server once to generate `plugins/OpenEco/config.yml`.
4. Stop the server and review the config.
5. Back up `plugins/OpenEco/` before opening the server.
6. Start the server again and verify `/balance`, `/baltop`, and `/history`.

## Commands

| Command | Use | Permission |
|---|---|---|
| `/balance [player] [currency]` | Check balance | `openeco.command.balance` |
| `/baltop [page] [currency]` | View leaderboard | `openeco.command.baltop` |
| `/pay <player> <amount> [currency]` | Send money | `openeco.command.pay` |
| `/eco give <player> <amount> [currency]` | Give money | `openeco.command.eco.give` |
| `/eco take <player> <amount> [currency]` | Take money | `openeco.command.eco.take` |
| `/eco set <player> <amount> [currency]` | Set balance | `openeco.command.eco.set` |
| `/eco reset <player> [currency]` | Reset to starting balance | `openeco.command.eco.reset` |
| `/eco delete <player>` | Delete an account and that account's history | `openeco.command.eco.delete` |
| `/eco reload` | Reload config and messages | `openeco.command.eco.reload` |
| `/history [player] [page] [currency]` | View transaction history | `openeco.command.history` |

`openeco.admin` grants all admin permissions.

## Owner Notes

- OpenEco is meant for one server with local storage.
- New configs should use `currencies.default` and `currencies.definitions.*`; the legacy `currency.*` block is still read for backward compatibility.
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

Output: `build/libs/OpenEco-<version>.jar`

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE).
