---
layout: home

hero:
  name: OpenEco
  text: Simple Economy
  tagline: Single-server-first economy for Paper and Folia with optional proxy-assisted handoff
  image:
    src: /logo.png
    alt: OpenEco Logo
  actions:
    - theme: brand
      text: Get Started
      link: /docs/
    - theme: alt
      text: Download
      link: /docs/download

features:
  - title: In-memory speed
    details: Account state lives in memory for fast reads and writes. JDBC persistence runs in the background on a configurable autosave interval.
    link: /docs/features
  - title: Multi-currency
    details: Configure named currencies with per-currency decimals, starting balances, and max caps. Vault v1 and VaultUnlocked v2 providers included.
    link: /docs/features
  - title: Vault compatible
    details: Drop-in economy provider for Vault and VaultUnlocked. Works with existing shop, job, and reward plugins that depend on Vault.
    link: /docs/installation
  - title: Folia ready
    details: Region-aware schedulers for player-facing work. folia-supported in plugin.yml.
    link: /docs/installation
  - title: Network handoff
    details: Optional cross-server mode flushes and refreshes accounts during proxy transfers when backends share one remote database.
    link: /docs/production
  - title: Open source
    details: Apache 2.0 licensed. Download from GitHub Releases; integrate via JitPack API or Vault.
    link: https://github.com/Alexteens24/OpenEco
---
