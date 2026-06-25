# Placeholders

Install [PlaceholderAPI](https://placeholderapi.com/) if you want placeholders. OpenEco registers its own expansion automatically when PAPI is present.

All placeholders without a currency suffix target the **default currency** (`currencies.default`).

## Player placeholders

| Placeholder | Result |
|---|---|
| `%openeco_balance%` | Raw balance |
| `%openeco_balance_<currency>%` | Raw balance for the given currency |
| `%openeco_balance_formatted%` | Formatted balance |
| `%openeco_balance_formatted_<currency>%` | Formatted balance for the given currency |
| `%openeco_rank%` | Leaderboard rank (empty if not ranked) |
| `%openeco_rank_<currency>%` | Leaderboard rank within the given currency |
| `%openeco_frozen%` | `true` if account is frozen, `false` otherwise |
| `%openeco_currency_singular%` | Singular currency name |
| `%openeco_currency_singular_<currency>%` | Singular name for the given currency |
| `%openeco_currency_plural%` | Plural currency name |
| `%openeco_currency_plural_<currency>%` | Plural name for the given currency |

## Leaderboard placeholders

| Placeholder | Result |
|---|---|
| `%openeco_top_1_name%` | Name at rank 1 |
| `%openeco_top_1_name_<currency>%` | Name at rank 1 for the given currency |
| `%openeco_top_1_balance%` | Raw balance at rank 1 |
| `%openeco_top_1_balance_<currency>%` | Raw balance at rank 1 for the given currency |
| `%openeco_top_1_balance_formatted%` | Formatted balance at rank 1 |
| `%openeco_top_1_balance_formatted_<currency>%` | Formatted balance at rank 1 for the given currency |
| `%openeco_top_N_name%` | Name at rank N |
| `%openeco_top_N_name_<currency>%` | Name at rank N for the given currency |
| `%openeco_top_N_balance%` | Raw balance at rank N |
| `%openeco_top_N_balance_<currency>%` | Raw balance at rank N for the given currency |
| `%openeco_top_N_balance_formatted%` | Formatted balance at rank N |
| `%openeco_top_N_balance_formatted_<currency>%` | Formatted balance at rank N for the given currency |

Replace `N` with the desired rank number (1, 2, 3, …).

## Missing ranks

When a rank does not exist:

- `_name` placeholders return `---`
- Balance placeholders return `0`

## Cache behavior

Leaderboard placeholders use the same cache as `/baltop`, controlled by `baltop.cache-ttl-seconds` in config. Lower values give fresher leaderboard data at the cost of more sorting work.

::: tip Reload
Placeholder formatting follows the current currency definitions. After changing `currencies.*`, run `/eco reload` or restart so display names and decimal digits stay in sync.
:::
