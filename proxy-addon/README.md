# OpenEco Proxy Addon

This addon is the Velocity-side helper for OpenEco's optional cross-server handoff mode.

It is meant for networks where backend Paper or Folia servers share one remote MySQL, MariaDB, or PostgreSQL database.

## What It Does

- Sends `flush <uuid>` to the source backend before a player switches servers.
- Waits briefly for an acknowledgement from that backend.
- Sends `refresh <uuid>` to the destination backend after the switch.
- Exposes `/ecosync <player>` for manual admin-triggered refreshes.

## What It Does Not Do

- Real-time balance broadcasts to every backend.
- Safe simultaneous writes to the same account from multiple live backends.
- Support for SQLite or H2 network sharing.

## Install

1. Configure OpenEco on every backend to use MySQL, MariaDB, or PostgreSQL.
2. Set `cross-server.enabled: true` in each backend's `plugins/OpenEco/config.yml`.
3. Place this addon jar on the Velocity proxy.
4. Restart the proxy and all backend servers.

## Operational Notes

- The pre-switch flush waits up to about 2 seconds for an acknowledgement from the source backend.
- If that acknowledgement times out, the switch still proceeds and the destination refresh becomes best-effort.
- `/ecosync <player>` does not send an automatic refresh if the flush acknowledgement times out.
- This addon assumes normal player handoff: one active backend session per player account.

## Command

`/ecosync <player>`

- Permission: `openeco.admin.sync`
- Use it after maintenance or when you want to force a manual flush and refresh for one online player.