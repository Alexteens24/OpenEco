# PlaceholderAPI

Install PlaceholderAPI if you want placeholders. OpenEco registers its own expansion automatically.

## Player Placeholders

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

## Leaderboard Placeholders

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

If a rank does not exist:

- `_name` returns `---`
- other rank fields return `0`

Leaderboard placeholders use the same cache controlled by `baltop.cache-ttl-seconds`.

All existing placeholders still target the default currency when no currency suffix is provided.
