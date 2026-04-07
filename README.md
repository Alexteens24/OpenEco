# OpenEco

[![CI](https://github.com/Alexteens24/OpenEco/actions/workflows/ci.yml/badge.svg)](https://github.com/Alexteens24/OpenEco/actions/workflows/ci.yml)

OpenEco is a single-server-first economy plugin for Paper or Folia.

It keeps account state in memory for fast local use, and can optionally do proxy-assisted account handoff sync when you run multiple backend servers against one shared remote database.

What it does:

- Keeps balances in memory for fast reads and writes.
- Stores data in SQLite, H2, MySQL, MariaDB, or PostgreSQL.
- Supports multiple named currencies with a configurable default-currency compatibility layer.
- Exposes Vault v1 and VaultUnlocked v2 economy providers.
- Supports PlaceholderAPI if it is installed.
- Offers optional cross-server handoff sync for shared remote databases when paired with the Velocity proxy addon.

What it does not do:

- Automatic migration between storage backends or file names.
- Real-time distributed balance broadcasts to every backend server.
- Safe simultaneous writes to the same account from multiple live backends without controlled player handoff.

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

## Network Mode

For multi-backend networks:

1. Use MySQL, MariaDB, or PostgreSQL.
2. Enable `cross-server.enabled: true` on every backend.
3. Install the `proxy-addon` jar on Velocity.
4. Restart the proxy and all backend servers.

This mode is for player handoff between backends. It is not a real-time distributed ledger.

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
- Network mode is opt-in and meant for player handoff over a shared remote database, not for general multi-writer sharing.
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
- [Proxy Addon](proxy-addon/README.md)

## Build From Source

```bash
./gradlew build
```

Output: `build/libs/OpenEco-<version>.jar`

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE).
