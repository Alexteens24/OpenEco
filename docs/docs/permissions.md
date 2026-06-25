# Permissions

Grant permission nodes explicitly in your permissions plugin. Server operators bypass checks by default.

<BaseTable :columns="['Permission', 'Description', 'Default']" grid="2fr 3fr 0.6fr">

<PermRow permission="openeco.command.balance" defaultVal="true">
Check own balance via `/balance`.
</PermRow>

<PermRow permission="openeco.command.balance.others" defaultVal="op">
Check another player's balance.
</PermRow>

<PermRow permission="openeco.command.baltop" defaultVal="true">
View the balance leaderboard via `/baltop`.
</PermRow>

<PermRow permission="openeco.command.pay" defaultVal="true">
Send money via `/pay`.
</PermRow>

<PermRow permission="openeco.command.history" defaultVal="true">
View own transaction history via `/history`.
</PermRow>

<PermRow permission="openeco.command.history.others" defaultVal="op">
View another player's transaction history.
</PermRow>

<PermRow permission="openeco.command.eco.give" defaultVal="op">
Use `/eco give`.
</PermRow>

<PermRow permission="openeco.command.eco.take" defaultVal="op">
Use `/eco take`.
</PermRow>

<PermRow permission="openeco.command.eco.set" defaultVal="op">
Use `/eco set`.
</PermRow>

<PermRow permission="openeco.command.eco.reset" defaultVal="op">
Use `/eco reset`.
</PermRow>

<PermRow permission="openeco.command.eco.delete" defaultVal="op">
Use `/eco delete`.
</PermRow>

<PermRow permission="openeco.command.eco.freeze" defaultVal="op">
Use `/eco freeze`.
</PermRow>

<PermRow permission="openeco.command.eco.unfreeze" defaultVal="op">
Use `/eco unfreeze`.
</PermRow>

<PermRow permission="openeco.command.eco.rename" defaultVal="op">
Use `/eco rename`.
</PermRow>

<PermRow permission="openeco.command.eco.reload" defaultVal="op">
Use `/eco reload`.
</PermRow>

<PermRow permission="openeco.migrator.admin" defaultVal="op">
Use `/openecomigrate` for economy import and storage migration.
</PermRow>

<PermRow permission="openeco.command.storage" defaultVal="op">
Deprecated alias for `openeco.migrator.admin`.
</PermRow>

<PermRow permission="openeco.admin" defaultVal="op">
Wildcard — grants all admin permissions listed above.
</PermRow>

</BaseTable>

## Addon permissions

<BaseTable :columns="['Permission', 'Description', 'Default']" grid="2fr 3fr 0.6fr">

<PermRow permission="openeco.enhancements.exchange" defaultVal="true">
Use `/exchange` (OpenEcoEnhancements).
</PermRow>

<PermRow permission="openeco.enhancements.bypass.paylimit" defaultVal="op">
Bypass pay limits enforced by OpenEcoEnhancements.
</PermRow>

<PermRow permission="openeco.enhancements.bypass.permcap" defaultVal="op">
Bypass permission-based balance caps enforced by OpenEcoEnhancements.
</PermRow>

<PermRow permission="openeco.admin.sync" defaultVal="op">
Use `/ecosync` on the Velocity proxy (OpenEco Proxy addon).
</PermRow>

</BaseTable>

::: tip Wildcard
`openeco.admin` is declared in `plugin.yml` with children for all core admin nodes. Grant it to give trusted staff full economy administration access.
:::
