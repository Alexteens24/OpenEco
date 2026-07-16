# Welcome to OpenEco

**OpenEco** is an economy plugin for Paper and Folia. It supports fast local operation and safe database-authoritative mutations across multiple proxy backends sharing MySQL, MariaDB, or PostgreSQL.

## Quick Navigation

<CardGrid>
  <DocCard title="Features" icon="💰" link="/docs/features" desc="Multi-currency, Vault, PlaceholderAPI, atomic exchange, and safe network writes." />
  <DocCard title="Installation" icon="📦" link="/docs/installation" desc="Install the JAR, configure storage, and verify your first commands." />
  <DocCard title="Commands" icon="⌨️" link="/docs/commands" desc="Player commands, admin /eco subcommands, and migration tools." />
  <DocCard title="Configuration" icon="⚙️" link="/docs/configuration" desc="Currencies, storage backends, pay rules, and messages." />
  <DocCard title="Migration" icon="🔄" link="/docs/migration" desc="Import from other economy plugins or move between storage backends." />
  <DocCard title="Addon API" icon="🔌" link="/docs/api" desc="Integrate with OpenEco directly from your own Bukkit plugin." />
</CardGrid>

## Why OpenEco?

Most economy plugins optimize for one of two extremes: a lightweight in-memory layer with minimal persistence, or a database-heavy design that trades latency for distribution. OpenEco targets the middle ground that fits most Paper servers:

- **Fast local reads and writes** through an in-memory account registry.
- **Reliable persistence** via SQLite, H2, MySQL, MariaDB, or PostgreSQL.
- **Safe multi-writer network mode** with durable JDBC cache invalidation and optional Redis wake-ups.

If you need a distributed ledger or live balance broadcasts to every backend, OpenEco is not the right tool. See the [Production guide](/docs/production) for fit guidance.

## Requirements

- Paper 1.20.5+ or Folia 1.21+
- Java 21
- [Vault](https://www.spigotmc.org/resources/vault.34315/) or [VaultUnlocked](https://github.com/TheNewEconomy/VaultUnlocked)
- [PlaceholderAPI](https://placeholderapi.com/) (optional)
