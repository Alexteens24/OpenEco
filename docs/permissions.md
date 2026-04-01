# Permissions

## Player permissions (default: true)

| Permission | Description |
|---|---|
| `simpleeco.command.balance` | Check own balance |
| `simpleeco.command.baltop` | View leaderboard |
| `simpleeco.command.pay` | Send money to another player |
| `simpleeco.command.history` | View own transaction history |

## Admin permissions (default: op)

| Permission | Description |
|---|---|
| `simpleeco.command.balance.others` | Check any player's balance |
| `simpleeco.command.history.others` | View any player's history |
| `simpleeco.command.eco.give` | Give money |
| `simpleeco.command.eco.take` | Take money |
| `simpleeco.command.eco.set` | Set balance |
| `simpleeco.command.eco.reset` | Reset balance to starting amount |
| `simpleeco.command.eco.delete` | Delete a player's current account and own history |
| `simpleeco.command.eco.reload` | Reload config in-game |
| `simpleeco.admin` | **Wildcard** — grants all admin permissions above |

## Example (LuckPerms)

Give all players the default permissions:
```
/lp group default permission set simpleeco.command.balance true
/lp group default permission set simpleeco.command.baltop true
/lp group default permission set simpleeco.command.pay true
/lp group default permission set simpleeco.command.history true
```

Give admins the wildcard:
```
/lp group admin permission set simpleeco.admin true
```
