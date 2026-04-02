# SimpleEco Addon API

This document is for plugin developers integrating with SimpleEco directly.

Server owners can ignore this file unless another plugin needs the SimpleEco service.

If you need the internal architecture, lifecycle, storage, or contributor workflow, read [Developer Guide](development.md) too.

## Scope

SimpleEco exposes a plugin-native API for same-server integrations.

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

## Getting The Service

Add SimpleEco as a dependency in `plugin.yml`:

```yaml
depend: [SimpleEco]
```

Resolve the service through Bukkit:

```java
RegisteredServiceProvider<SimpleEcoApi> registration = Bukkit.getServicesManager()
        .getRegistration(SimpleEcoApi.class);
if (registration == null) {
    throw new IllegalStateException("SimpleEco API is not available");
}

SimpleEcoApi api = registration.getProvider();
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
- blank names are rejected
- names longer than 16 characters are rejected
- `TransactionMetadata.source` must be 64 characters or fewer when provided
- `TransactionMetadata.note` must be 255 characters or fewer when provided
- `has(UUID, BigDecimal)` requires `amount >= 0`
- `logCustomTransaction(...)` requires `amount > 0`
- history `page` and `pageSize` must be greater than 0
- `HistoryFilter` requires `fromMs >= 0`, `toMs >= 0`, and `fromMs <= toMs`

Invalid arguments raise standard Java exceptions such as `IllegalArgumentException` or `NullPointerException`.

## Result Model

SimpleEco uses result objects for normal business-rule failures.

Examples:

- insufficient funds
- account not found
- balance limit reached
- transfer cooldown
- plugin-cancelled event

`SimpleEcoApiException` is reserved for API-level failures such as history read failures.

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
| `has(UUID, BigDecimal)` | `boolean` |
| `canDeposit(UUID, BigDecimal)` | `BalanceCheckResult` |
| `canWithdraw(UUID, BigDecimal)` | `BalanceCheckResult` |
| `deposit(UUID, BigDecimal)` | `BalanceChangeResult` |
| `withdraw(UUID, BigDecimal)` | `BalanceChangeResult` |
| `setBalance(UUID, BigDecimal)` | `BalanceChangeResult` |
| `reset(UUID)` | `BalanceChangeResult` |

### Semantics

- `getBalance(UUID)` returns `0` when the account does not exist
- `has(UUID, BigDecimal)` is a convenience boolean and does not tell you why a check failed
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
- `ACCOUNT_NOT_FOUND`
- `INVALID_AMOUNT`
- `INSUFFICIENT_FUNDS`
- `BALANCE_LIMIT`

### BalanceChangeResult

Fields:

- `status`
- `amount`
- `previousBalance`
- `newBalance`

Possible statuses:

- `SUCCESS`
- `ACCOUNT_NOT_FOUND`
- `INVALID_AMOUNT`
- `INSUFFICIENT_FUNDS`
- `BALANCE_LIMIT`
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
| `previewTransfer(UUID, UUID, BigDecimal)` | `TransferPreviewResult` |
| `transfer(UUID, UUID, BigDecimal)` | `TransferResult` |

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
- `COOLDOWN`
- `INSUFFICIENT_FUNDS`
- `ACCOUNT_NOT_FOUND`
- `BALANCE_LIMIT`
- `CANCELLED`
- `TOO_LOW`
- `INVALID_AMOUNT`
- `SELF_TRANSFER`

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
- `COOLDOWN`
- `INSUFFICIENT_FUNDS`
- `ACCOUNT_NOT_FOUND`
- `BALANCE_LIMIT`
- `TOO_LOW`
- `INVALID_AMOUNT`
- `SELF_TRANSFER`

Notes:

- `sent`, `received`, and `tax` reflect the current configured rounding and tax rules
- `cooldownRemainingMs` is only meaningful for `COOLDOWN`
- `minimumAmount` is populated only when `status` is `TOO_LOW`; otherwise it is `null`

## History

### Methods

| Method | Result |
|---|---|
| `getHistory(UUID, int, int)` | `HistoryPage` |
| `getHistory(UUID, int, int, HistoryFilter)` | `HistoryPage` |
| `logCustomTransaction(UUID, BigDecimal, TransactionKind)` | `void` |
| `logCustomTransaction(UUID, BigDecimal, TransactionKind, TransactionMetadata)` | `void` |

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
- amount must be positive
- kind must not be `null`

If the account does not exist, the API throws `SimpleEcoApiException`.

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

When metadata is present, SimpleEco can render that history line as a custom entry instead of the built-in admin wording.

## Leaderboard And Name Map

### Methods

| Method | Result |
|---|---|
| `getTopAccounts(int)` | `List<AccountSnapshot>` |
| `getRankOf(UUID)` | `int` |
| `getUUIDNameMap()` | `Map<UUID, String>` |

Notes:

- `getTopAccounts(limit)` returns immutable account snapshots ordered from richest to poorest
- `limit` must be `>= 0`
- `getRankOf(UUID)` returns a 1-based rank, or `-1` if the account does not exist
- `getUUIDNameMap()` returns an unmodifiable snapshot of known UUID to last-known name mappings

## Currency And Formatting

### Methods

| Method | Result |
|---|---|
| `getRules()` | `EconomyRulesSnapshot` |
| `getCurrencyInfo()` | `CurrencyInfo` |
| `format(BigDecimal)` | `String` |

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

- `currency` is the same data shape returned by `getCurrencyInfo()`
- `payMinAmount` is `null` when no minimum is enforced
- `historyRetentionDays` is normalized to `-1` when history is kept forever or pruning is disabled

## Exceptions

You should expect three categories of failure:

1. Invalid arguments.
   These raise normal Java exceptions such as `IllegalArgumentException` or `NullPointerException`.
2. Normal business-rule failures.
   These are returned through result/status objects.
3. API-level failures.
   These raise `SimpleEcoApiException`.

Examples of API-level failures:

- history query failed
- filtered history query failed
- custom history write requested for a missing account

## Bukkit Events

SimpleEco also emits Bukkit events for integrations that prefer event listeners.

Available events:

- `AccountCreateEvent`
- `AccountRenameEvent`
- `AccountDeleteEvent`
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

    private SimpleEcoApi api;

    @Override
    public void onEnable() {
        RegisteredServiceProvider<SimpleEcoApi> registration = getServer()
                .getServicesManager()
                .getRegistration(SimpleEcoApi.class);
        if (registration == null) {
            throw new IllegalStateException("SimpleEco API is not available");
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
