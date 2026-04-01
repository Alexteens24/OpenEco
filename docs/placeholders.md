# PlaceholderAPI

Requires [PlaceholderAPI](https://placeholderapi.com/) to be installed. No expansion download needed — SimpleEco registers its own expansion on startup.

---

## Player placeholders

| Placeholder | Description |
|---|---|
| `%simpleeco_balance%` | Raw balance (e.g. `1234.50`) |
| `%simpleeco_balance_formatted%` | Formatted balance (e.g. `1234.50 Dollars`) |
| `%simpleeco_currency_singular%` | Currency name singular (e.g. `Dollar`) |
| `%simpleeco_currency_plural%` | Currency name plural (e.g. `Dollars`) |

## Baltop placeholders

| Placeholder | Description |
|---|---|
| `%simpleeco_top_1_name%` | Name of the #1 richest player |
| `%simpleeco_top_1_balance%` | Raw balance of #1 |
| `%simpleeco_top_1_balance_formatted%` | Formatted balance of #1 |
| `%simpleeco_top_N_name%` | Name of rank N (replace N with any number) |
| `%simpleeco_top_N_balance%` | Raw balance of rank N |
| `%simpleeco_top_N_balance_formatted%` | Formatted balance of rank N |

If rank N doesn't exist, `_name` returns `---` and `_balance` returns `0`.

Baltop results are cached (see `baltop.cache-ttl-seconds` in config).

## Example — scoreboard sidebar

```
&6Your balance
&e%simpleeco_balance_formatted%

&6Top Players
&f1. %simpleeco_top_1_name%
&e   %simpleeco_top_1_balance_formatted%
&f2. %simpleeco_top_2_name%
&e   %simpleeco_top_2_balance_formatted%
```
