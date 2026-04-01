# Configuration

Config file: `plugins/SimpleEco/config.yml`  
Reload without restart: `/eco reload`

Most settings can be reloaded safely with `/eco reload`, but storage backend changes should be treated as a stop-the-server maintenance task.

---

## Currency

```yaml
currency:
  id: "simpleeco"          # Internal ID used by VaultUnlocked multi-currency queries
  name-singular: "Dollar"  # Used when balance == 1
  name-plural: "Dollars"   # Used when balance != 1
  decimal-digits: 2        # Decimal places shown (0–8)
  starting-balance: 0.00   # Balance for new accounts
```

Production note:

- `decimal-digits` should be treated as a data-formatting choice, not something to flip repeatedly after launch.
- `starting-balance` only affects new accounts and `/eco reset`.

## Storage

```yaml
storage:
  type: sqlite   # sqlite or h2
  sqlite:
    file: economy.db
  h2:
    file: economy  # H2 appends .mv.db automatically
```

Both backends store data in `plugins/SimpleEco/`.

Production notes:

- SQLite is the default and is the simplest choice for a single local server.
- H2 is also supported, but it is still local embedded storage.
- Switching backend type or file name does not migrate data automatically.
- Back up the whole `plugins/SimpleEco/` directory before storage changes.

## Auto-save

```yaml
autosave-interval: 30   # Seconds between background saves to disk (values <= 0 are clamped to 1)
```

All balance changes are applied in-memory instantly. The auto-save flush writes dirty accounts to the database periodically. Data is also flushed on server shutdown.

Production notes:

- Lower values reduce worst-case balance loss after an unclean crash, but increase disk activity.
- `10` to `30` seconds is a reasonable production starting range.
- `1` or `2` seconds is useful for staging or persistence stress tests, not usually as a first production default.

## Pay

```yaml
pay:
  cooldown-seconds: 0    # Seconds a player must wait between /pay uses (0 = disabled)
  tax-percent: 0.0       # Percentage deducted from sent amount (0.0 = disabled)
  min-amount: 0.01       # Smallest transfer that will be accepted
```

**Example:** `tax-percent: 5.0` means sending $100 costs the sender $100 but the receiver gets $95.

Production notes:

- Use `cooldown-seconds` or a non-trivial `min-amount` if you want to suppress spammy transfer loops.
- Tax reduces the global money supply unless you add your own sink/source logic elsewhere.

## Baltop

```yaml
baltop:
  page-size: 10           # Entries per page
  cache-ttl-seconds: 30   # How long the sorted leaderboard is cached
```

Production note:

- Higher TTL values reduce repeated sorting work at the cost of slightly staler leaderboard views.

## Messages

All messages use [MiniMessage](https://docs.advntr.dev/minimessage/format.html) format.

Operational note:

- `account-sync-failed` and `account-name-conflict` are important production-facing safety messages and should not be removed unless you replace them with equivalent warnings.

Available placeholders per message key:

| Key | Placeholders |
|---|---|
| `balance-self` | `<balance>` |
| `balance-other` | `<player>`, `<balance>` |
| `pay-sent` | `<player>`, `<amount>` |
| `pay-received` | `<player>`, `<amount>` |
| `pay-tax` | `<tax>` |
| `pay-cooldown` | `<seconds>` |
| `account-not-found` | `<player>` |
| `eco-give` | `<player>`, `<amount>`, `<balance>` |
| `eco-take` | `<player>`, `<amount>`, `<balance>` |
| `eco-set` | `<player>`, `<balance>` |
| `eco-reset` | `<player>`, `<balance>` |
| `baltop-header` | `<page>`, `<total>` |
| `baltop-entry` | `<rank>`, `<player>`, `<balance>` |
| `history-header` | `<player>`, `<page>`, `<total>` |
| `history-give/take/set/reset` | `<date>`, `<amount>`, `<balance>` |
| `history-pay-sent/received` | `<date>`, `<amount>`, `<counterpart>` |

**Example** — gradient header:
```yaml
baltop-header: "<gradient:gold:yellow><bold>--- Balance Top (<page>/<total>) ---</bold></gradient>"
```
