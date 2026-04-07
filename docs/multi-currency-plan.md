# Multi-Currency Support Plan

> **Note:** This document is a historical planning artifact. Multi-currency support has been fully implemented. Refer to [configuration.md](configuration.md), [api.md](api.md), and [development.md](development.md) for current documentation.



Add true per-player multi-currency balances to OpenEco without breaking the current default-currency behavior for existing servers, legacy Vault v1 integrations, and addons that only know about one balance.

## Current State In Code

The current implementation is single-currency from top to bottom.

- `AccountRecord` stores one `BigDecimal balance` and uses that field for all in-memory reads, writes, snapshots, and dirty tracking.
- `JdbcAccountRepository` persists one `balance` column on `accounts` and stores history rows without `currency_id`.
- `AccountService`, `EconomyOperations`, commands, placeholders, and leaderboard logic all read and format one balance.
- `OpenEcoApi` exposes one balance per account and one `CurrencyInfo` as the only currency model.
- `BalanceChangeEvent`, `BalanceChangedEvent`, `PayEvent`, and `PayCompletedEvent` do not identify which currency changed.
- `OpenEcoEconomyProvider` implements VaultUnlocked v2 but explicitly reports `hasMultiCurrencySupport() == false` and ignores the currency parameter in balance and mutation calls.
- `OpenEcoLegacyEconomyProvider` can only ever represent one default currency because Vault v1 itself is single-currency.
- The enhancements addon and its tests assume `api.getBalance(id)`, `api.deposit(id, amount)`, and `api.getRules().currency()` refer to the whole economy.

This means multi-currency is feasible, but it is a public-contract change, not just a storage tweak.

## Design Decisions To Lock Before Coding

These should be treated as explicit decisions before implementation starts.

1. Keep legacy Vault v1 default-currency only.
   Reason: Vault v1 does not model multiple currencies.

2. Keep the existing `OpenEcoApi` as a default-currency compatibility layer.
   Reason: current addons and tests already depend on it.

3. Add a new multi-currency addon API instead of breaking the old one in place.
   Recommended name: `OpenEcoMultiCurrencyApi`.

4. Disallow cross-currency conversion in the first release.
   Only transfers within the same currency should be supported.

5. Keep account freeze as account-wide, not currency-specific.
   Reason: this matches current semantics and avoids partial freeze edge cases.

6. Make one configured default currency mandatory.
   Existing `accounts.balance` and old history rows migrate into that currency.

7. Keep pay cooldown global per sender in the first pass.
   This minimizes state churn while still allowing per-currency balances.

8. Move precision, starting balance, and max balance to per-currency definitions.
   These rules are currency-specific by nature.

## Recommended Target Architecture

### Config Model

Replace the single `currency.*` block with a currency catalog plus a required default currency.

Suggested shape:

```yaml
currencies:
  default: openeco
  definitions:
    openeco:
      name-singular: Dollar
      name-plural: Dollars
      decimal-digits: 2
      starting-balance: 0.00
      max-balance: -1
    gems:
      name-singular: Gem
      name-plural: Gems
      decimal-digits: 0
      starting-balance: 0
      max-balance: 100000

pay:
  cooldown-seconds: 0
  tax-percent: 0.0
  min-amount: 0.01
```

Notes:

- `pay.cooldown-seconds` stays global in phase 1.
- `pay.tax-percent` and `pay.min-amount` can stay global initially, but all scaling must use the active currency precision.
- `currency.*` should still be read as a legacy config path for one transition release and mapped into the new catalog automatically.

### In-Memory Domain Model

Keep `AccountRecord` as the aggregate root and locking object, but stop treating it as a single-balance record.

Recommended direction:

- `AccountRecord`
  - keep: `id`, `lastKnownName`, `createdAt`, `updatedAt`, `dirty`, `frozen`
  - replace single `balance` with `Map<String, BigDecimal> balances`
  - add dirty tracking for changed currencies, for example `Set<String> dirtyCurrencies`
  - add helpers such as `getBalance(currencyId)`, `setBalance(currencyId, amount)`, `snapshot()`

Why this shape:

- current locking already happens on the account object
- current `AccountRegistry` uses one live record per UUID
- atomic same-currency transfers between two players can keep the same lock ordering strategy
- `freeze` and name changes remain naturally account-scoped

### Persistence Model

Keep `accounts` for metadata and add a dedicated balances table.

Recommended schema:

- `accounts`
  - `id`
  - `name`
  - `created_at`
  - `updated_at`
  - `frozen`
  - keep `balance` temporarily for migration compatibility during one release window

- `account_balances`
  - `account_id`
  - `currency_id`
  - `balance`
  - `updated_at`
  - primary key `(account_id, currency_id)`

- `transactions`
  - add `currency_id NOT NULL`
  - existing rows backfilled with the default currency id during migration

Why not add a `currencies` DB table in v1:

- the plugin is config-driven today
- config remains the source of truth for currency definitions
- this avoids adding synchronization rules between config and database metadata in the same release

### Public API Model

Keep `OpenEcoApi` working exactly as the default-currency facade.

Add a new interface, for example:

- `OpenEcoMultiCurrencyApi`
  - `Set<String> getCurrencies()`
  - `Optional<CurrencyInfo> getCurrencyInfo(String currencyId)`
  - `BigDecimal getBalance(UUID accountId, String currencyId)`
  - `boolean has(UUID accountId, String currencyId, BigDecimal amount)`
  - `BalanceCheckResult canDeposit(UUID accountId, String currencyId, BigDecimal amount)`
  - `BalanceChangeResult deposit(UUID accountId, String currencyId, BigDecimal amount)`
  - `TransferResult transfer(UUID fromId, UUID toId, String currencyId, BigDecimal amount)`
  - currency-aware history and leaderboard accessors

Also add currency-aware snapshots instead of overloading old record types with ambiguous semantics.

Suggested new types:

- `AccountBalanceSnapshot`
- `CurrencyRulesSnapshot`
- `MultiCurrencyRulesSnapshot`
- `TransactionSnapshotV2` or currency-aware `TransactionSnapshot`

### Event Model

Do not fork the event system unless necessary. Extend the existing events with currency awareness.

Recommended change:

- add `String currencyId` to `BalanceChangeEvent`
- add `String currencyId` to `BalanceChangedEvent`
- add `String currencyId` to `PayEvent`
- add `String currencyId` to `PayCompletedEvent`
- keep old getters unchanged
- add overloaded constructors so internal callers can migrate without breaking listener binaries unnecessarily

This allows existing listeners to continue working on the default path while giving new listeners the missing context.

## Phase Plan

### Phase 0: Compatibility Contract And Config Parsing

Goal: lock the compatibility strategy before touching storage.

Files likely involved:

- `src/main/resources/config.yml`
- `src/main/java/dev/alexisbinh/openeco/service/EconomyConfigSnapshot.java`
- new currency definition classes under `src/main/java/dev/alexisbinh/openeco/api/` or `service/`
- `src/test/java/dev/alexisbinh/openeco/service/EconomyConfigSnapshotTest.java`

Tasks:

1. Add a new config parser that can read both legacy `currency.*` and new `currencies.*`.
2. Define an internal immutable currency catalog snapshot.
3. Preserve a clear `defaultCurrencyId` and a `getDefaultCurrency()` lookup.
4. Keep legacy single-currency config working unchanged.

Exit criteria:

- old config still loads without warnings other than optional deprecation notices
- new multi-currency config parses into a validated in-memory catalog
- invalid configs fail fast at startup with clear messages

### Phase 1: Storage Migration Foundation

Goal: add persistence support without changing public behavior yet.

Files likely involved:

- `src/main/java/dev/alexisbinh/openeco/storage/AccountRepository.java`
- `src/main/java/dev/alexisbinh/openeco/storage/TransactionRepository.java`
- `src/main/java/dev/alexisbinh/openeco/storage/JdbcAccountRepository.java`
- `src/test/java/dev/alexisbinh/openeco/storage/JdbcAccountRepositoryIntegrationTest.java`

Tasks:

1. Create `account_balances` if missing.
2. Add `currency_id` to `transactions` if missing.
3. On startup migration:
   - backfill `account_balances` from `accounts.balance` into the default currency
   - backfill `transactions.currency_id` for old rows with the default currency
4. Update repository read/write paths to load account metadata plus all balances.
5. Keep `accounts.balance` readable for one compatibility release, but stop treating it as authoritative once `account_balances` exists.

Exit criteria:

- old H2 and SQLite data upgrade in place
- migrated balances equal the old single balance for the default currency
- history rows survive and gain `currency_id`
- repository tests cover both clean schema and upgraded legacy schema

### Phase 2: In-Memory Account And Mutation Refactor

Goal: make the service layer currency-aware while preserving current default-currency behavior.

Files likely involved:

- `src/main/java/dev/alexisbinh/openeco/model/AccountRecord.java`
- `src/main/java/dev/alexisbinh/openeco/service/AccountRegistry.java`
- `src/main/java/dev/alexisbinh/openeco/service/AccountService.java`
- `src/main/java/dev/alexisbinh/openeco/service/EconomyOperations.java`
- `src/main/java/dev/alexisbinh/openeco/service/TransactionHistoryService.java`
- `src/test/java/dev/alexisbinh/openeco/service/AccountRegistryTest.java`
- `src/test/java/dev/alexisbinh/openeco/service/EconomyOperationsTest.java`
- `src/test/java/dev/alexisbinh/openeco/service/AccountServicePersistenceIntegrationTest.java`

Tasks:

1. Replace single-balance operations with currency-aware operations internally.
2. Preserve current methods as default-currency wrappers.
3. Refactor `format`, `getBalance`, `deposit`, `withdraw`, `set`, `reset`, `has`, `canDeposit`, `canWithdraw`, `pay`, `canTransfer`, and `previewTransfer` to require a currency context internally.
4. Ensure scale, max-balance, and reset logic use the active currency definition.
5. Update history logging to write `currency_id` with each entry.
6. Keep lock ordering exactly as it is today for two-account transfers.

Exit criteria:

- the old single-currency calls still behave identically for the default currency
- new currency-aware internal methods are the only place where business rules live
- pay and balance events still fire once per successful mutation

### Phase 3: Leaderboard, History, And Formatting Surface

Goal: remove the remaining single-balance assumptions from user-facing read models.

Files likely involved:

- `src/main/java/dev/alexisbinh/openeco/service/LeaderboardCache.java`
- `src/main/java/dev/alexisbinh/openeco/model/TransactionEntry.java`
- `src/main/java/dev/alexisbinh/openeco/command/BalTopCommand.java`
- `src/main/java/dev/alexisbinh/openeco/command/HistoryCommand.java`
- `src/main/java/dev/alexisbinh/openeco/placeholder/OpenEcoPlaceholderExpansion.java`
- docs under `docs/placeholders.md`, `docs/configuration.md`, `docs/api.md`

Tasks:

1. Make leaderboard caches currency-specific.
   Recommended: one cache per currency id.
2. Extend transaction entries and formatting to display the active currency.
3. Add optional currency selectors to `/baltop` and `/history`.
4. Add new PlaceholderAPI patterns for currency-aware balance and top placeholders.
   Example:
   - `%openeco_balance_openeco%`
   - `%openeco_balance_formatted_gems%`
   - `%openeco_top_openeco_1_balance%`
5. Keep current placeholders mapped to the default currency.

Exit criteria:

- default placeholders and commands still work unchanged
- new placeholders and commands can target a specific currency
- leaderboard invalidation works per currency after mutations

### Phase 4: Public Addon API Evolution

Goal: expose multi-currency safely without breaking existing addons.

Files likely involved:

- `src/main/java/dev/alexisbinh/openeco/api/OpenEcoApi.java`
- `src/main/java/dev/alexisbinh/openeco/api/OpenEcoApiImpl.java`
- `src/main/java/dev/alexisbinh/openeco/api/AccountSnapshot.java`
- `src/main/java/dev/alexisbinh/openeco/api/CurrencyInfo.java`
- `src/main/java/dev/alexisbinh/openeco/api/EconomyRulesSnapshot.java`
- new API files under `src/main/java/dev/alexisbinh/openeco/api/`
- `src/test/java/dev/alexisbinh/openeco/api/OpenEcoApiImplTest.java`

Tasks:

1. Keep `OpenEcoApi` semantics bound to the default currency.
2. Introduce `OpenEcoMultiCurrencyApi` and register it with `ServicesManager` alongside the old API.
3. Add currency-aware snapshots and result types where needed.
4. Ensure `getRules()` on the old API still returns the default currency rules.
5. Add an explicit currency catalog accessor on the new API.

Exit criteria:

- existing addon tests keep passing against the old API
- new tests can read and mutate non-default currencies via the new API

### Phase 5: Vault And Command Integration

Goal: wire the new currency model into integration points without breaking servers that only use one currency.

Files likely involved:

- `src/main/java/dev/alexisbinh/openeco/economy/OpenEcoEconomyProvider.java`
- `src/main/java/dev/alexisbinh/openeco/economy/OpenEcoLegacyEconomyProvider.java`
- `src/main/java/dev/alexisbinh/openeco/OpenEcoPlugin.java`
- `src/main/java/dev/alexisbinh/openeco/command/BalanceCommand.java`
- `src/main/java/dev/alexisbinh/openeco/command/PayCommand.java`
- `src/main/java/dev/alexisbinh/openeco/command/EcoCommand.java`
- tests under `src/test/java/dev/alexisbinh/openeco/economy/`

Tasks:

1. Switch VaultUnlocked v2 provider to `hasMultiCurrencySupport() == true` once currency-aware methods are real.
2. Honor the requested currency argument in all balance and mutation calls.
3. Keep Vault v1 mapped to the default currency only.
4. Extend commands with optional trailing currency arguments.
   Recommended syntax:
   - `/balance [player] [currency]`
   - `/pay <player> <amount> [currency]`
   - `/eco give|take|set <player> <amount> [currency]`
   - `/eco reset <player> [currency]`
   - `/baltop [currency] [page]`
   - `/history [player] [page] [currency]`
5. Keep current syntax as the default-currency shortcut.

Exit criteria:

- legacy Vault plugins still behave exactly as before
- VaultUnlocked v2 plugins can query and mutate named currencies
- command UX remains backward-compatible for current players and admins

### Phase 6: Events And Addon Blast Radius

Goal: make extension points currency-aware and update the bundled addon.

Files likely involved:

- `src/main/java/dev/alexisbinh/openeco/event/BalanceChangeEvent.java`
- `src/main/java/dev/alexisbinh/openeco/event/BalanceChangedEvent.java`
- `src/main/java/dev/alexisbinh/openeco/event/PayEvent.java`
- `src/main/java/dev/alexisbinh/openeco/event/PayCompletedEvent.java`
- `enhancements-addon/src/main/java/**`
- `enhancements-addon/src/test/java/**`

Tasks:

1. Add `currencyId` accessors to balance and pay events.
2. Update the enhancements addon to operate on the relevant currency.
3. Decide whether enhancement rules should apply to all currencies or only a configured subset.
   Recommended first pass: all currencies, with optional future filtering.
4. Update addon tests so they do not assume one global balance implicitly.

Exit criteria:

- listeners can distinguish which currency changed
- bundled addon behavior is explicit and tested for non-default currencies

### Phase 7: Documentation, Migration Notes, And Release Guardrails

Goal: ship the feature without leaving operators with ambiguous upgrade behavior.

Files likely involved:

- `README.md`
- `docs/api.md`
- `docs/configuration.md`
- `docs/placeholders.md`
- `docs/development.md`
- `docs/production.md`

Tasks:

1. Document the migration path from `currency.*` to `currencies.*`.
2. Document that legacy Vault v1 remains default-currency only.
3. Document new command syntax and placeholders.
4. Document that old addons keep seeing the default currency unless they move to the new API.
5. Add release notes for DB migration behavior and rollback expectations.

Exit criteria:

- server owners can upgrade without guessing where old balances went
- addon authors understand which API to target

## Test Plan

The existing test suite is strong enough to use as the migration safety net, but it will need expansion in several places.

Add or update tests for:

1. Config parsing
   - legacy one-currency config still works
   - new multi-currency config validates duplicates, missing default, and bad precision limits

2. Schema migration
   - H2 legacy `accounts.balance` migrates into `account_balances`
   - SQLite legacy `accounts.balance` migrates into `account_balances`
   - old `transactions` rows gain the default `currency_id`

3. Service behavior
   - default-currency wrappers preserve old semantics
   - non-default currency deposit, withdraw, set, reset, and pay work
   - balance caps and scaling are evaluated per currency
   - pay cooldown stays global if that decision is kept

4. Leaderboard and history
   - per-currency baltop ordering
   - history pagination by currency
   - old history formatting still works for default currency

5. API compatibility
   - `OpenEcoApi` keeps returning default-currency results
   - `OpenEcoMultiCurrencyApi` exposes non-default currencies correctly

6. Vault compatibility
   - Vault v1 remains default-currency only
   - VaultUnlocked v2 currency parameter is honored

7. Addon behavior
   - interest, pay limit, and perm cap do not silently apply to the wrong currency

## Suggested Delivery Order

Use small, reviewable PRs instead of one large branch.

1. PR 1: config catalog and internal currency definitions
2. PR 2: DB schema migration and repository refactor
3. PR 3: currency-aware service internals with default-currency wrappers
4. PR 4: API v2 and event currency fields
5. PR 5: Vault v2, commands, placeholders, leaderboard, and history
6. PR 6: enhancements addon updates, docs, and release notes

## Risks To Watch

1. Dirty flush complexity rises once one account can have many changed balances.
   Do not lose the current guarantee that queued history writes drain before balances persist.

2. Leaderboard cache invalidation becomes currency-scoped.
   A global cache will either become stale or too expensive.

3. Old history rows are ambiguous until `currency_id` is backfilled.
   Migration must be automatic and deterministic.

4. API ambiguity will leak everywhere if both old and new methods exist without strong naming.
   Avoid adding too many overloaded methods to the old interface.

5. Addon and event listeners may silently act on the default currency unless currency context is explicit.

## Recommended First Implementation Scope

To keep the first release realistic, I would limit the scope to this:

- multiple configured currencies
- per-currency balance, precision, starting balance, and max balance
- same-currency transfers only
- default-currency compatibility for old API, old commands, old placeholders, and legacy Vault v1
- full named-currency support only in VaultUnlocked v2 and the new addon API

I would explicitly leave these out of v1:

- cross-currency conversion
- exchange rates
- per-world currencies
- bank accounts
- runtime currency creation outside config reload/restart