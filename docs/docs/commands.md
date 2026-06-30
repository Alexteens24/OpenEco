# Commands

All core commands are registered in `plugin.yml`. Tab-completion suggests subcommands and arguments you have permission for.

## Player commands

<CommandRow commands="/balance" aliases="bal, money" permission="openeco.command.balance">
Check your balance. With `openeco.command.balance.others`, specify another player. Add an optional currency id as the last argument.
</CommandRow>

<CommandRow commands="/baltop" aliases="balancetop, moneytop" permission="openeco.command.baltop">
View the richest players. Supports pagination and an optional currency id. Results are cached for `baltop.cache-ttl-seconds`.
</CommandRow>

<CommandRow commands="/pay" permission="openeco.command.pay">
Send money to another player. Respects pay cooldown, tax, and minimum amount from config. Optional currency argument.
</CommandRow>

<CommandRow commands="/history" aliases="txhistory, ecohistory" permission="openeco.command.history">
View transaction history. Defaults to your own account. With `openeco.command.history.others`, specify another player. Supports pagination and optional currency filter.
</CommandRow>

## Admin commands — `/eco`

All `/eco` subcommands require their individual permission node (or `openeco.admin`).

<CommandRow commands="/eco give" permission="openeco.command.eco.give">
Give money to a player. Optional currency argument. Fires balance change events and records history.
</CommandRow>

<CommandRow commands="/eco take" permission="openeco.command.eco.take">
Take money from a player. Fails if the player has insufficient funds. Optional currency argument.
</CommandRow>

<CommandRow commands="/eco set" permission="openeco.command.eco.set">
Set a player's balance to an exact amount. Optional currency argument.
</CommandRow>

<CommandRow commands="/eco reset" permission="openeco.command.eco.reset">
Reset a player's balance to the configured starting balance. Optional currency argument.
</CommandRow>

<CommandRow commands="/eco delete" permission="openeco.command.eco.delete">
Delete a player's account and that account's transaction history. This cannot be undone.
</CommandRow>

<CommandRow commands="/eco freeze" permission="openeco.command.eco.freeze">
Freeze an account. Frozen accounts cannot deposit, withdraw, pay, or receive payments.
</CommandRow>

<CommandRow commands="/eco unfreeze" permission="openeco.command.eco.unfreeze">
Unfreeze a previously frozen account.
</CommandRow>

<CommandRow commands="/eco rename" permission="openeco.command.eco.rename">
Rename an account's display name. Names must be unique (case-insensitive) and 16 characters or fewer.
</CommandRow>

<CommandRow commands="/eco reload" permission="openeco.command.eco.reload">
Reload `config.yml` and message templates. Restarts autosave and history prune schedulers.

Does not replace a restart after changing storage backends, `cross-server.enabled`, or `accounts.load-strategy`.
</CommandRow>

::: tip Alias
`/economy` is an alias for `/eco`.
:::

## Migration command

<CommandRow commands="/openecomigrate" permission="openeco.migrator.admin">
Import economy data from another plugin or migrate between storage backends.

Usage: <code>/openecomigrate &lt;source&gt; [--scan] [--dry-run] [--overwrite]</code>

Economy plugin import requires the **OpenEcoMigrator** addon. Storage migration is built into the core JAR.
</CommandRow>

## Addon commands

### OpenEcoEnhancements

<CommandRow commands="/exchange" permission="openeco.enhancements.exchange">
Exchange one currency for another at a configured rate. Usage: <code>/exchange &lt;amount&gt; &lt;from&gt; &lt;to&gt;</code>
</CommandRow>

### OpenEco Proxy (Velocity)

<CommandRow commands="/ecosync" permission="openeco.admin.sync">
Manually force a flush-then-refresh cycle for an online player on the proxy network. Usage: <code>/ecosync &lt;player&gt;</code>

Useful after direct database edits or when debugging a suspected stale balance.
</CommandRow>

## What `/eco reload` does

`reloadSettings()` runs on the main thread and:

1. Reloads `config.yml` from disk (including legacy `currency.*` → `currencies.*` migration).
2. Refreshes message templates.
3. Restarts autosave and history prune schedulers.

Storage type, cross-server mode, and account load strategy still require a full server restart.
