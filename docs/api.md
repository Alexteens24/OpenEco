# OpenEco Addon API

This document is for plugin developers integrating with OpenEco directly.

Server owners can ignore this file unless another plugin needs the OpenEco service.

If you need the internal architecture, lifecycle, storage, or contributor workflow, read [Developer Guide](development.md) too.

## Scope

OpenEco exposes a plugin-native API for same-server integrations.

What this API is good for:

- account lookups
- balance reads and writes
- transfers
- history reads
- leaderboard reads
- currency metadata

What this API is not:

- cross-server sync
- an async-safe wrapper around the server thread model
- a distributed ledger

Methods without a `currencyId` parameter are the default-currency compatibility layer.
Currency-aware overloads target the named currency directly.

## Getting The Service

Add OpenEco as a dependency in `plugin.yml`:

```yaml
depend: [OpenEco]
```

The core plugin keeps the runtime plugin name `openeco` and provides the alias `OpenEco`, so this dependency remains valid without changing existing server data folders.

Resolve the service through Bukkit:

```java
RegisteredServiceProvider<OpenEcoApi> registration = Bukkit.getServicesManager()
        .getRegistration(OpenEcoApi.class);
if (registration == null) {
    throw new IllegalStateException("OpenEco API is not available");
}

OpenEcoApi api = registration.getProvider();
```

Resolve it during plugin startup and fail fast if it is missing.

## Threading

Call mutating methods from a safe server thread.

- On Paper: use the normal server thread.
- On Folia: use the owning region thread for the player or entity you are acting on.

The API does not move your work onto a safe thread for you.

## Input Validation

These validations are enforced before business-rule evaluation:

- `accountId`, `fromId`, `toId`, `amount`, and required names must not be `null`
- currency-aware overloads require a non-blank, known `currencyId`
- blank names are rejected
- names longer than 16 characters are rejected
- `TransactionMetadata.source` must be 64 characters or fewer when provided
- `TransactionMetadata.note` must be 255 characters or fewer when provided
- `has(UUID, BigDecimal)` requires `amount >= 0`
- `logCustomTransaction(...)` requires `amount > 0`
- deposit, withdraw, pay, and related prechecks reject amounts that round to `0` at the configured currency precision
- history `page` and `pageSize` must be greater than 0
- `HistoryFilter` requires `fromMs >= 0`, `toMs >= 0`, and `fromMs <= toMs`

Invalid arguments raise standard Java exceptions such as `IllegalArgumentException` or `NullPointerException`.

## Result Model

OpenEco uses result objects for normal business-rule failures.

Examples:

- insufficient funds
- account not found
- balance limit reached
- transfer cooldown
- plugin-cancelled event

`OpenEcoApiException` is reserved for API-level failures such as history read failures.

## Accounts

### Methods

| Method | Result |
|---|---|
| `hasAccount(UUID)` | `boolean` |
| `getAccount(UUID)` | `Optional<AccountSnapshot>` |
| `findByName(String)` | `Optional<AccountSnapshot>` |
| `createAccount(UUID, String)` | `AccountOperationResult` |
| `ensureAccount(UUID, String)` | `AccountOperationResult` |
| `renameAccount(UUID, String)` | `AccountOperationResult` |
| `deleteAccount(UUID)` | `AccountOperationResult` |

### Account Rules

- names are trimmed before validation
- names must be non-blank
- names must be 16 characters or fewer
- names are unique ignoring case

### ensureAccount(UUID, String)

`ensureAccount(...)` is the convenience method for addons that want an account to exist with a known name.

Behavior:

- creates the account if it does not exist yet
- returns `UNCHANGED` if the account already exists with the same name
- attempts a rename if the account exists but the supplied name is different

This is useful for player-facing addons that want one call instead of separate create-or-rename logic.

### AccountSnapshot

`AccountSnapshot` is immutable and contains:

- `id`
- `lastKnownName`
- `balance`
- `createdAt`
- `updatedAt`
- `frozen`

For `getAccount(...)` and `findByName(...)`, `balance` is the current default-currency balance.
For currency-aware leaderboard overloads, `balance` is the requested currency balance.

### AccountOperationResult

Fields:

- `status`: outcome enum
- `account`: snapshot when one is available
- `message`: human-readable detail

Success statuses:

- `CREATED`
- `RENAMED`
- `DELETED`

Non-success statuses:

- `ALREADY_EXISTS`
- `NAME_IN_USE`
- `NOT_FOUND`
- `UNCHANGED`
- `FAILED`

Notes:

- `ALREADY_EXISTS` usually includes the current account snapshot.
- `NAME_IN_USE` may include the current account snapshot on rename, but not on create.
- `FAILED` means the API could not complete the operation cleanly even if the input itself was valid.

### Example

```java
AccountOperationResult result = api.createAccount(playerId, playerName);
if (result.isSuccess()) {
    return;
}

switch (result.status()) {
    case ALREADY_EXISTS, UNCHANGED -> {
        return;
    }
    case NAME_IN_USE -> {
        getLogger().warning("Name collision for " + playerName);
    }
    default -> {
        throw new IllegalStateException(result.message());
    }
}
```

## Balances

### Methods

| Method | Result |
|---|---|
| `getBalance(UUID)` | `BigDecimal` |
| `getBalance(UUID, String)` | `BigDecimal` |
| `has(UUID, BigDecimal)` | `boolean` |
| `has(UUID, String, BigDecimal)` | `boolean` |
| `canDeposit(UUID, BigDecimal)` | `BalanceCheckResult` |
| `canDeposit(UUID, String, BigDecimal)` | `BalanceCheckResult` |
| `canWithdraw(UUID, BigDecimal)` | `BalanceCheckResult` |
| `canWithdraw(UUID, String, BigDecimal)` | `BalanceCheckResult` |
| `deposit(UUID, BigDecimal)` | `BalanceChangeResult` |
| `deposit(UUID, String, BigDecimal)` | `BalanceChangeResult` |
| `withdraw(UUID, BigDecimal)` | `BalanceChangeResult` |
| `withdraw(UUID, String, BigDecimal)` | `BalanceChangeResult` |
| `setBalance(UUID, BigDecimal)` | `BalanceChangeResult` |
| `setBalance(UUID, String, BigDecimal)` | `BalanceChangeResult` |
| `reset(UUID)` | `BalanceChangeResult` |
| `reset(UUID, String)` | `BalanceChangeResult` |

### Semantics

- `getBalance(UUID)` returns `0` when the account does not exist
- methods without `currencyId` operate on the current default currency
- currency-aware overloads operate on the requested currency
- `has(UUID, BigDecimal)` is a convenience boolean and does not tell you why a check failed
- `has(UUID, BigDecimal)` applies the configured currency precision before comparing balances; positive probe amounts that round to `0` return `false`
- `canDeposit(...)` and `canWithdraw(...)` tell you whether the operation would succeed without mutating state
- `deposit(...)`, `withdraw(...)`, `setBalance(...)`, and `reset(...)` attempt the mutation and return a status object

### BalanceCheckResult

Fields:

- `status`
- `amount`
- `currentBalance`
- `resultingBalance`

`amount` is the validated request amount after the plugin applies its numeric rules.

Possible statuses:

- `ALLOWED`
- `UNKNOWN_CURRENCY`
- `ACCOUNT_NOT_FOUND`
- `INVALID_AMOUNT`
- `INSUFFICIENT_FUNDS`
- `BALANCE_LIMIT`
- `FROZEN`

### BalanceChangeResult

Fields:

- `status`
- `amount`
- `previousBalance`
- `newBalance`

Possible statuses:

- `SUCCESS`
- `UNKNOWN_CURRENCY`
- `ACCOUNT_NOT_FOUND`
- `INVALID_AMOUNT`
- `INSUFFICIENT_FUNDS`
- `BALANCE_LIMIT`
- `FROZEN`
- `CANCELLED`

`CANCELLED` means another plugin cancelled the Bukkit event fired for the mutation.

### Example

```java
BalanceChangeResult result = api.withdraw(player.getUniqueId(), price);
if (!result.isSuccess()) {
    return false;
}
return true;
```

## Transfers

### Methods

| Method | Result |
|---|---|
| `canTransfer(UUID, UUID, BigDecimal)` | `TransferCheckResult` |
| `canTransfer(UUID, UUID, String, BigDecimal)` | `TransferCheckResult` |
| `previewTransfer(UUID, UUID, BigDecimal)` | `TransferPreviewResult` |
| `previewTransfer(UUID, UUID, String, BigDecimal)` | `TransferPreviewResult` |
| `transfer(UUID, UUID, BigDecimal)` | `TransferResult` |
| `transfer(UUID, UUID, String, BigDecimal)` | `TransferResult` |

### Semantics

`canTransfer(...)` checks only balance-level rules:

- account existence
- self-transfer
- sender balance
- recipient max balance

It does not apply cooldown or tax.

`previewTransfer(...)` is the richer preflight call.

It evaluates:

- account existence
- self-transfer
- sender balance
- recipient max balance
- pay cooldown
- minimum pay amount
- tax and received amount

It does not know whether another plugin will later cancel `PayEvent`.

`transfer(...)` performs the full transfer path including cooldown, tax, and cancellable events.

### TransferCheckResult

Fields:

- `status`
- `amount`

Statuses:

- `ALLOWED`
- `UNKNOWN_CURRENCY`
- `ACCOUNT_NOT_FOUND`
- `INVALID_AMOUNT`
- `INSUFFICIENT_FUNDS`
- `BALANCE_LIMIT`
- `SELF_TRANSFER`

### TransferResult

Fields:

- `status`
- `sent`
- `received`
- `tax`
- `cooldownRemainingMs`

Statuses:

- `SUCCESS`
- `UNKNOWN_CURRENCY`
- `COOLDOWN`
- `INSUFFICIENT_FUNDS`
- `ACCOUNT_NOT_FOUND`
- `BALANCE_LIMIT`
- `CANCELLED`
- `TOO_LOW`
- `INVALID_AMOUNT`
- `SELF_TRANSFER`
- `FROZEN`

Notes:

- `sent` is what the sender loses
- `received` is what the receiver gains
- `tax` is the difference when tax is enabled
- `cooldownRemainingMs` is only meaningful for `COOLDOWN`

### TransferPreviewResult

Fields:

- `status`
- `sent`
- `received`
- `tax`
- `cooldownRemainingMs`
- `minimumAmount`

Statuses:

- `ALLOWED`
- `UNKNOWN_CURRENCY`
- `COOLDOWN`
- `INSUFFICIENT_FUNDS`
- `ACCOUNT_NOT_FOUND`
- `BALANCE_LIMIT`
- `TOO_LOW`
- `INVALID_AMOUNT`
- `SELF_TRANSFER`
- `FROZEN`

Notes:

- `sent`, `received`, and `tax` reflect the current configured rounding and tax rules
- `cooldownRemainingMs` is only meaningful for `COOLDOWN`
- `minimumAmount` is populated only when `status` is `TOO_LOW`; otherwise it is `null`

## History

### Methods

| Method | Result |
|---|---|
| `getHistory(UUID, int, int)` | `HistoryPage` |
| `getHistory(UUID, String, int, int)` | `HistoryPage` |
| `getHistory(UUID, int, int, HistoryFilter)` | `HistoryPage` |
| `getHistory(UUID, String, int, int, HistoryFilter)` | `HistoryPage` |
| `logCustomTransaction(UUID, BigDecimal, TransactionKind)` | `void` |
| `logCustomTransaction(UUID, String, BigDecimal, TransactionKind)` | `void` |
| `logCustomTransaction(UUID, BigDecimal, TransactionKind, TransactionMetadata)` | `void` |
| `logCustomTransaction(UUID, String, BigDecimal, TransactionKind, TransactionMetadata)` | `void` |

### HistoryPage

Fields:

- `page`
- `pageSize`
- `totalEntries`
- `totalPages`
- `entries`

Notes:

- `entries` is immutable
- `totalPages` is `0` when there are no matching entries

### TransactionSnapshot

Each history entry contains:

- `kind`
- `counterpartId`
- `targetId`
- `currencyId`
- `amount`
- `balanceBefore`
- `balanceAfter`
- `timestamp`
- `source`
- `note`

Kinds:

- `GIVE`
- `TAKE`
- `SET`
- `RESET`
- `PAY_SENT`
- `PAY_RECEIVED`

### HistoryFilter

`HistoryFilter.NONE` means no filtering.

Builder fields:

- `kind(TransactionKind)`
- `fromMs(long)`
- `toMs(long)`
- `currencyId(String)`

Example:

```java
HistoryFilter filter = HistoryFilter.builder()
        .kind(TransactionKind.PAY_SENT)
        .fromMs(startEpochMs)
        .toMs(endEpochMs)
        .build();

HistoryPage page = api.getHistory(playerId, 1, 20, filter);
```

### logCustomTransaction(...)

Use this when your addon wants to write a history entry without changing an account balance.

Rules:

- account must exist
- currency-aware overloads require a known currency id
- amount must be positive
- kind must not be `null`

If the account does not exist, the API throws `OpenEcoApiException`.

### TransactionMetadata

Fields:

- `source`
- `note`

Notes:

- `source` should usually identify the addon or feature that emitted the entry
- `note` is a short human-readable explanation such as a quest name or payout reason
- blank metadata fields are normalized to `null`

Example:

```java
api.logCustomTransaction(
    playerId,
    new BigDecimal("25.00"),
    TransactionKind.GIVE,
    new TransactionMetadata("QuestAddon", "Daily contract payout"));
```

When metadata is present, OpenEco can render that history line as a custom entry instead of the built-in admin wording.

## Leaderboard And Name Map

### Methods

| Method | Result |
|---|---|
| `getTopAccounts(int)` | `List<AccountSnapshot>` |
| `getTopAccounts(int, String)` | `List<AccountSnapshot>` |
| `getTopAccounts(int, int)` | `LeaderboardPage` |
| `getTopAccounts(int, int, String)` | `LeaderboardPage` |
| `getRankOf(UUID)` | `int` |
| `getRankOf(UUID, String)` | `int` |
| `getUUIDNameMap()` | `Map<UUID, String>` |

Notes:

- `getTopAccounts(limit)` returns immutable account snapshots ordered from richest to poorest
- currency-aware leaderboard overloads return snapshots whose `balance` field matches the requested currency
- `limit` must be `>= 0`
- `getRankOf(UUID)` returns a 1-based rank, or `-1` if the account does not exist
- `getUUIDNameMap()` returns an unmodifiable snapshot of known UUID to last-known name mappings

## Currency And Formatting

### Methods

| Method | Result |
|---|---|
| `getRules()` | `EconomyRulesSnapshot` |
| `getCurrencyInfo()` | `CurrencyInfo` |
| `getCurrencyInfo(String)` | `CurrencyInfo` |
| `getCurrencies()` | `List<CurrencyInfo>` |
| `hasCurrency(String)` | `boolean` |
| `format(BigDecimal)` | `String` |
| `format(BigDecimal, String)` | `String` |

### CurrencyInfo

Fields:

- `id`
- `singularName`
- `pluralName`
- `fractionalDigits`
- `startingBalance`
- `maxBalance`

Use `hasMaxBalance()` before reading `maxBalance` as a hard limit.

### EconomyRulesSnapshot

Fields:

- `currency`
- `payCooldownMs`
- `payTaxRate`
- `payMinAmount`
- `balTopCacheTtlMs`
- `historyRetentionDays`

Notes:

- `currency` is the same data shape returned by `getCurrencyInfo()` for the current default currency
- `payMinAmount` is `null` when no minimum is enforced
- `historyRetentionDays` is normalized to `-1` when history is kept forever or pruning is disabled

## Exceptions

You should expect three categories of failure:

1. Invalid arguments.
   These raise normal Java exceptions such as `IllegalArgumentException` or `NullPointerException`.
2. Normal business-rule failures.
   These are returned through result/status objects.
3. API-level failures.
   These raise `OpenEcoApiException`.

Examples of API-level failures:

- history query failed
- filtered history query failed
- custom history write requested for a missing account

## Bukkit Events

OpenEco also emits Bukkit events for integrations that prefer event listeners.

Available events:

- `AccountCreateEvent`
- `AccountRenameEvent`
- `AccountRenamedEvent`
- `AccountDeleteEvent`
- `AccountDeletedEvent`
- `BalanceChangeEvent`
- `BalanceChangedEvent`
- `PayEvent`
- `PayCompletedEvent`

Pre-mutation events:

- `AccountRenameEvent`
- `AccountDeleteEvent`
- `BalanceChangeEvent`
- `PayEvent`

These are cancellable.

Post-success events:

- `AccountCreateEvent`
- `AccountRenamedEvent`
- `AccountDeletedEvent`
- `BalanceChangedEvent`
- `PayCompletedEvent`

These are not cancellable and fire only after the in-memory mutation succeeded.

## Event Listener Example

```java
@EventHandler
public void onPayCompleted(PayCompletedEvent event) {
    getLogger().info(
        event.getFromId() + " sent " + event.getSent()
            + " to " + event.getToId()
            + " (receiver got " + event.getReceived() + ")");
}
```

## Practical Example

```java
public final class ShopBridge extends JavaPlugin {

    private OpenEcoApi api;

    @Override
    public void onEnable() {
        RegisteredServiceProvider<OpenEcoApi> registration = getServer()
                .getServicesManager()
                .getRegistration(OpenEcoApi.class);
        if (registration == null) {
            throw new IllegalStateException("OpenEco API is not available");
        }
        api = registration.getProvider();
    }

    public boolean charge(Player player, UUID bankId, BigDecimal price) {
        TransferPreviewResult preview = api.previewTransfer(player.getUniqueId(), bankId, price);
        if (!preview.isAllowed()) {
            return false;
        }

        TransferResult result = api.transfer(player.getUniqueId(), bankId, price);
        if (!result.isSuccess()) {
            return false;
        }
        return true;
    }
}
```
