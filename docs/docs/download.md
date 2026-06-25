# Download

<img src="/logo.png" alt="OpenEco" class="showcase-shot" style="max-width: 200px;" />

OpenEco releases are published on [GitHub Releases](https://github.com/Alexteens24/OpenEco/releases).

## Core plugin

Download `OpenEco-<version>.jar` from the latest release and place it in your server's `plugins/` folder.

The core JAR includes:

- The main economy plugin
- Built-in storage migration via `/openecomigrate`
- Vault v1 and VaultUnlocked v2 providers
- PlaceholderAPI expansion (when PAPI is installed)

## Optional addons

Build addons from source or download them from release artifacts when available:

| JAR | Purpose |
|---|---|
| `OpenEcoMigrator-<version>.jar` | Import balances from other economy plugins |
| `OpenEcoEnhancements-<version>.jar` | Interest, pay limits, perm caps, `/exchange` |
| `OpenEco-Proxy-<version>.jar` | Velocity proxy handoff helper |

Build all artifacts locally:

```bash
./gradlew shadowJar :migrator-addon:shadowJar :enhancements-addon:shadowJar :proxy-addon:shadowJar
```

Output paths:

- `build/libs/OpenEco-<version>.jar`
- `migrator-addon/build/libs/OpenEcoMigrator-<version>.jar`
- `enhancements-addon/build/libs/OpenEcoEnhancements-<version>.jar`
- `proxy-addon/build/libs/OpenEco-Proxy-<version>.jar`

## Requirements

| Requirement | Notes |
|---|---|
| Java 21 | Server and build toolchain |
| Paper 1.20.5+ or Folia 1.21+ | `folia-supported: true` in plugin.yml |
| Vault or VaultUnlocked | Hard dependency — plugin name must be `Vault` |
| PlaceholderAPI | Optional — placeholders register automatically |

::: tip VaultUnlocked on Paper
Paper exposes VaultUnlocked under the plugin name `Vault`. OpenEco's `depend: [Vault]` in plugin.yml works with both Vault and VaultUnlocked.
:::

## Addon API (developers)

Published on [JitPack](https://jitpack.io/#Alexteens24/OpenEco):

```kotlin
compileOnly("com.github.Alexteens24:OpenEco:v1.4.7")
```

See [Addon API](/docs/api) for integration details.

## Next steps

After downloading, follow the [Installation](/docs/installation) guide to configure storage and verify your setup.
