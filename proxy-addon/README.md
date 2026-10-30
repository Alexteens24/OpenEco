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
- If that acknowledgement times out, or the backend reports a failed flush, the switch is
  **cancelled** (fail-closed) so the destination can never load a stale balance. The player is
  asked to try again.
- Flush requests carry a per-attempt token that the backend echoes back. An acknowledgement for
  an older attempt is ignored, so a late reply cannot satisfy a newer flush.
- `/ecosync <player>` reports exactly what happened: timeout, backend failure, or a newer sync
  superseding it. No refresh is sent unless the flush was acknowledged.
- This addon assumes normal player handoff: one active backend session per player account.
- **The proxy addon and the OpenEco plugin must be updated together.** A proxy that sends
  tokenized flushes to an older backend gets no acknowledgement (the backend cannot parse the
  request), so every switch is denied until the backend is updated. Updating the proxy first is
  safe; updating only the backends is not.

## Command

`/ecosync <player>`

- Permission: `openeco.admin.sync`
- Use it after maintenance or when you want to force a manual flush and refresh for one online player.