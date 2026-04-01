# SimpleEco Addon API

SimpleEco now exposes a plugin-native addon API that is independent from VaultUnlocked and does not leak JDBC exceptions or internal mutable models.

This API is intended for production plugin integrations on the same server, not for cross-server coordination or async fire-and-forget writes from arbitrary threads.

## Setup

Add SimpleEco as a dependency in your `plugin.yml`:

```yaml
depend: [SimpleEco]
```

Prefer fetching the API through Bukkit services:

```java
RegisteredServiceProvider<SimpleEcoApi> registration = Bukkit.getServicesManager()
        .getRegistration(SimpleEcoApi.class);
if (registration == null) {
    throw new IllegalStateException("SimpleEco API is not available");
}

SimpleEcoApi api = registration.getProvider();
```

`SimpleEcoPlugin#getApi()` still exists as a convenience method, but the service contract is the primary integration surface.

Call mutating methods from a normal server thread on Paper or from the owning region thread on Folia. The API does not marshal async addon calls onto a safe thread for you.

Production guidance:

- Resolve the service during your plugin startup and fail fast if it is missing.
- Treat result objects as authoritative. Do not infer success from side effects.
- Handle `NAME_IN_USE`, `FAILED`, `CANCELLED`, and `SELF_TRANSFER` explicitly in your integration code.
- Do not build features that assume SimpleEco is a shared cross-server ledger.

---

## Core Contract

Main interface: `dev.alexisbinh.simpleeco.api.SimpleEcoApi`

### Accounts

| Method | Result |
|---|---|
| `hasAccount(UUID)` | `boolean` |
| `getAccount(UUID)` | `Optional<AccountSnapshot>` |
| `findByName(String)` | `Optional<AccountSnapshot>` |
| `createAccount(UUID, String)` | `AccountOperationResult` |
| `renameAccount(UUID, String)` | `AccountOperationResult` |
| `deleteAccount(UUID)` | `AccountOperationResult` |

`AccountSnapshot` is immutable and contains:

- `id`
- `lastKnownName`
- `balance`
- `createdAt`
- `updatedAt`

`AccountOperationResult.Status` values:

- `CREATED`
- `RENAMED`
- `DELETED`
- `ALREADY_EXISTS`
- `NAME_IN_USE`
- `NOT_FOUND`
- `UNCHANGED`
- `FAILED`

Account names must be non-blank, trimmed, and at most 16 characters long. Create and rename operations also reject names that are already in use by another account, case-insensitively.

If your addon mirrors player names into accounts, validate or sanitize those names before treating account creation or rename failures as unexpected.

### Balance operations

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

`BalanceCheckResult.Status` values:

- `ALLOWED`
- `ACCOUNT_NOT_FOUND`
- `INVALID_AMOUNT`
- `INSUFFICIENT_FUNDS`
- `BALANCE_LIMIT`

`BalanceChangeResult.Status` values:

- `SUCCESS`
- `ACCOUNT_NOT_FOUND`
- `INVALID_AMOUNT`
- `INSUFFICIENT_FUNDS`
- `BALANCE_LIMIT`
- `CANCELLED`

### Transfers

```java
TransferResult result = api.transfer(fromId, toId, amount);
```

`TransferResult.Status` values:

- `SUCCESS`
- `COOLDOWN`
- `INSUFFICIENT_FUNDS`
- `ACCOUNT_NOT_FOUND`
- `BALANCE_LIMIT`
- `CANCELLED`
- `TOO_LOW`
- `INVALID_AMOUNT`
- `SELF_TRANSFER`

### History and leaderboard

```java
HistoryPage history = api.getHistory(playerId, 1, 20);
List<AccountSnapshot> top = api.getTopAccounts(10);
```

`HistoryPage` contains the requested page, page size, total entry count, total page count, and immutable `TransactionSnapshot` entries.

`TransactionSnapshot` uses public enum `TransactionKind`:

- `GIVE`
- `TAKE`
- `SET`
- `RESET`
- `PAY_SENT`
- `PAY_RECEIVED`

### Currency metadata

```java
CurrencyInfo currency = api.getCurrencyInfo();
String formatted = api.format(new BigDecimal("1250.50"));
```

`CurrencyInfo` includes:

- `id`
- `singularName`
- `pluralName`
- `fractionalDigits`
- `startingBalance`
- `maxBalance` (nullable when unlimited)

### Error handling

`SimpleEcoApiException` is only used for API-level exceptional failures such as history lookup errors. Normal business-rule rejections are represented through result/status objects instead of exceptions.

---

## Events

All events are in `dev.alexisbinh.simpleeco.event`.

### AccountCreateEvent

Fired after a new account is created. Not cancellable.

### AccountRenameEvent

Fired before an account rename is applied. Cancellable.

### AccountDeleteEvent

Fired before an account is deleted. Cancellable.

### BalanceChangeEvent

Fired before give, take, set, and reset operations. Cancellable.

### PayEvent

Fired before a transfer is processed. Cancellable.

---

## Example

```java
public class ShopPlugin extends JavaPlugin {

    private SimpleEcoApi eco;

    @Override
    public void onEnable() {
        RegisteredServiceProvider<SimpleEcoApi> registration = getServer()
                .getServicesManager()
                .getRegistration(SimpleEcoApi.class);
        if (registration == null) {
            getLogger().severe("SimpleEco API not found! Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        eco = registration.getProvider();
    }

    public boolean charge(Player player, BigDecimal price) {
        BalanceChangeResult result = eco.withdraw(player.getUniqueId(), price);
        return result.isSuccess();
    }
}
```

For production integrations, prefer explicit result handling over a bare boolean when the caller needs to distinguish between insufficient funds, invalid input, plugin cancellation, or balance caps.
