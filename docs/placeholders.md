# PlaceholderAPI

Install PlaceholderAPI if you want placeholders. SimpleEco registers its own expansion automatically.

## Player Placeholders

| Placeholder | Result |
|---|---|
| `%simpleeco_balance%` | Raw balance |
| `%simpleeco_balance_<currency>%` | Raw balance for the given currency |
| `%simpleeco_balance_formatted%` | Formatted balance |
| `%simpleeco_balance_formatted_<currency>%` | Formatted balance for the given currency |
| `%simpleeco_rank%` | Leaderboard rank (empty if not ranked) |
| `%simpleeco_rank_<currency>%` | Leaderboard rank within the given currency |
| `%simpleeco_frozen%` | `true` if account is frozen, `false` otherwise |
| `%simpleeco_currency_singular%` | Singular currency name |
| `%simpleeco_currency_singular_<currency>%` | Singular name for the given currency |
| `%simpleeco_currency_plural%` | Plural currency name |
| `%simpleeco_currency_plural_<currency>%` | Plural name for the given currency |

## Leaderboard Placeholders

| Placeholder | Result |
|---|---|
| `%simpleeco_top_1_name%` | Name at rank 1 |
| `%simpleeco_top_1_name_<currency>%` | Name at rank 1 for the given currency |
| `%simpleeco_top_1_balance%` | Raw balance at rank 1 |
| `%simpleeco_top_1_balance_<currency>%` | Raw balance at rank 1 for the given currency |
| `%simpleeco_top_1_balance_formatted%` | Formatted balance at rank 1 |
| `%simpleeco_top_1_balance_formatted_<currency>%` | Formatted balance at rank 1 for the given currency |
| `%simpleeco_top_N_name%` | Name at rank N |
| `%simpleeco_top_N_name_<currency>%` | Name at rank N for the given currency |
| `%simpleeco_top_N_balance%` | Raw balance at rank N |
| `%simpleeco_top_N_balance_<currency>%` | Raw balance at rank N for the given currency |
| `%simpleeco_top_N_balance_formatted%` | Formatted balance at rank N |
| `%simpleeco_top_N_balance_formatted_<currency>%` | Formatted balance at rank N for the given currency |

If a rank does not exist:

- `_name` returns `---`
- other rank fields return `0`

Leaderboard placeholders use the same cache controlled by `baltop.cache-ttl-seconds`.

All existing placeholders still target the default currency when no currency suffix is provided.
