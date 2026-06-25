# OpenEco

[![CI](https://github.com/Alexteens24/OpenEco/actions/workflows/ci.yml/badge.svg)](https://github.com/Alexteens24/OpenEco/actions/workflows/ci.yml)

OpenEco is a single-server-first economy plugin for Paper or Folia.

It keeps account state in memory for fast local use, and can optionally do proxy-assisted account handoff sync when you run multiple backend servers against one shared remote database.

**Documentation:** https://alexteens24.github.io/OpenEco/

## Features

- In-memory balances with JDBC persistence (SQLite, H2, MySQL, MariaDB, PostgreSQL)
- Multi-currency support with Vault v1 and VaultUnlocked v2 providers
- PlaceholderAPI expansion (optional)
- Transaction history with optional retention pruning
- Optional cross-server handoff via Velocity proxy addon

## Requirements

- Paper 1.20.5+ or Folia 1.21+
- Java 21
- [Vault](https://www.spigotmc.org/resources/vault.34315/) or [VaultUnlocked](https://github.com/TheNewEconomy/VaultUnlocked)
- [PlaceholderAPI](https://placeholderapi.com/) (optional)

## Quick start

1. Download `OpenEco-<version>.jar` from [GitHub Releases](https://github.com/Alexteens24/OpenEco/releases).
2. Install Vault or VaultUnlocked.
3. Place the JAR in `plugins/` and start the server once.
4. Review `plugins/OpenEco/config.yml`, back up, and verify `/balance`, `/baltop`, and `/pay`.

See the [Installation guide](https://alexteens24.github.io/OpenEco/docs/installation) for full setup and network mode.

## Documentation

| Topic | Link |
|---|---|
| Features | [docs/features](https://alexteens24.github.io/OpenEco/docs/features) |
| Commands | [docs/commands](https://alexteens24.github.io/OpenEco/docs/commands) |
| Configuration | [docs/configuration](https://alexteens24.github.io/OpenEco/docs/configuration) |
| Migration | [docs/migration](https://alexteens24.github.io/OpenEco/docs/migration) |
| Production guide | [docs/production](https://alexteens24.github.io/OpenEco/docs/production) |
| Addon API | [docs/api](https://alexteens24.github.io/OpenEco/docs/api) |
| Development | [docs/development](https://alexteens24.github.io/OpenEco/docs/development) |
| Proxy addon | [proxy-addon/README.md](proxy-addon/README.md) |

## Build from source

```bash
./gradlew build
```

Output: `build/libs/OpenEco-<version>.jar`

## License

This project is licensed under the Apache License 2.0. See [LICENSE](LICENSE).
