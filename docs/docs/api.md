# Addon API

This document is for plugin developers integrating with OpenEco directly. Server owners can ignore it unless another plugin needs the OpenEco service.

## Scope

OpenEco exposes a plugin-native API for same-server integrations.

**Good for:** account lookups, balance reads/writes, transfers, history, leaderboard, currency metadata.

**Not for:** cross-server sync, async-safe thread wrapping, or distributed ledger behavior.

Methods without a `currencyId` parameter use the default currency compatibility layer. Currency-aware overloads target the named currency directly.

## Maven dependency

Published on [JitPack](https://jitpack.io/#Alexteens24/OpenEco).

Gradle (`build.gradle.kts`):

```kotlin
repositories {
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("com.github.Alexteens24:OpenEco:v1.4.7")
}
```

Use a release tag or commit hash for snapshots. Paper API remains a separate `compileOnly` dependency.

## Getting the service

Add OpenEco as a dependency in `plugin.yml`:

```yaml
depend: [OpenEco]
```

Resolve through Bukkit:

```java
RegisteredServiceProvider<OpenEcoApi> registration = Bukkit.getServicesManager()
        .getRegistration(OpenEcoApi.class);
if (registration == null) {
    throw new IllegalStateException("OpenEco API is not available");
}

OpenEcoApi api = registration.getProvider();
```

Resolve during plugin startup and fail fast if missing.

## Threading

Call mutating methods from a safe server thread:

- **Paper:** normal server thread.
- **Folia:** owning region thread for the player or entity.

The API does not move your work onto a safe thread.

## Result model

OpenEco uses result objects for normal business-rule failures (insufficient funds, account not found, balance limit, cooldown, cancelled events).

`OpenEcoApiException` is reserved for API-level failures such as history read failures.

## Accounts

| Method | Result |
|---|---|
| `hasAccount(UUID)` | `boolean` |
| `getAccount(UUID)` | `Optional<AccountSnapshot>` |
| `findByName(String)` | `Optional<AccountSnapshot>` |
| `createAccount(UUID, String)` | `AccountOperationResult` |
| `ensureAccount(UUID, String)` | `AccountOperationResult` |
| `renameAccount(UUID, String)` | `AccountOperationResult` |
| `deleteAccount(UUID)` | `AccountOperationResult` |
| `freezeAccount(UUID)` | `boolean` (returns `true` if existed and frozen) |
| `unfreezeAccount(UUID)` | `boolean` (returns `true` if existed and unfrozen) |
| `isFrozen(UUID)` | `boolean` |

`ensureAccount` creates if missing, returns `UNCHANGED` if the name matches, or attempts rename if different.

`AccountSnapshot` fields: `id`, `lastKnownName`, `balance`, `createdAt`, `updatedAt`, `frozen`.

## Balances

| Method | Result |
|---|---|
| `getBalance(UUID)` / `getBalance(UUID, currencyId)` | `BigDecimal` |
| `has(UUID, amount)` / `has(UUID, currencyId, amount)` | `boolean` |
| `canDeposit(UUID, amount)` / `canDeposit(UUID, currencyId, amount)` | `BalanceCheckResult` |
| `canWithdraw(UUID, amount)` / `canWithdraw(UUID, currencyId, amount)` | `BalanceCheckResult` |
| `deposit(UUID, amount)` / `deposit(UUID, currencyId, amount)` | `BalanceChangeResult` |
| `withdraw(UUID, amount)` / `withdraw(UUID, currencyId, amount)` | `BalanceChangeResult` |
| `setBalance(UUID, amount)` / `setBalance(UUID, currencyId, amount)` | `BalanceChangeResult` |
| `reset(UUID)` / `reset(UUID, currencyId)` | `BalanceChangeResult` |

`getBalance` returns `0` when the account does not exist. `CANCELLED` means another plugin cancelled the Bukkit event.

## Transfers

| Method | Result | Purpose |
|---|---|---|
| `canTransfer(fromId, toId, amount)` / `canTransfer(fromId, toId, currencyId, amount)` | `TransferCheckResult` | Balance-level checks only (no cooldown/tax) |
| `previewTransfer(fromId, toId, amount)` / `previewTransfer(fromId, toId, currencyId, amount)` | `TransferPreviewResult` | Full preflight including cooldown, tax, minimum |
| `transfer(fromId, toId, amount)` / `transfer(fromId, toId, currencyId, amount)` | `TransferResult` | Full transfer path with events |

`TransferResult` fields: `status`, `sent`, `received`, `tax`, `cooldownRemainingMs`.

## History

| Method | Result |
|---|---|
| `getHistory(UUID, page, pageSize)` / `getHistory(UUID, currencyId, page, pageSize)` | `HistoryPage` |
| `getHistory(UUID, page, pageSize, filter)` / `getHistory(UUID, currencyId, page, pageSize, filter)` | `HistoryPage` |
| `logCustomTransaction(UUID, amount, kind)` / `logCustomTransaction(UUID, currencyId, amount, kind)` | `void` |
| `logCustomTransaction(UUID, amount, kind, metadata)` / `logCustomTransaction(UUID, currencyId, amount, kind, metadata)` | `void` |

`HistoryFilter` supports `kind`, `fromMs`, `toMs`, `currencyId`. Use `logCustomTransaction` to record history without changing balance.

## Leaderboard

| Method | Result |
|---|---|
| `getTopAccounts(limit)` / `getTopAccounts(limit, currencyId)` | `List<AccountSnapshot>` |
| `getTopAccounts(page, pageSize)` / `getTopAccounts(page, pageSize, currencyId)` | `LeaderboardPage` |
| `getRankOf(UUID)` / `getRankOf(UUID, currencyId)` | `int` (1-based, `-1` if not found) |
| `getUUIDNameMap()` | `Map<UUID, String>` |

## Currency and formatting

| Method | Result |
|---|---|
| `getRules()` | `EconomyRulesSnapshot` |
| `getCurrencyInfo()` / `getCurrencyInfo(String)` | `CurrencyInfo` |
| `getCurrencies()` | `List<CurrencyInfo>` |
| `hasCurrency(String)` | `boolean` |
| `format(BigDecimal)` / `format(BigDecimal, String)` | `String` |

## Bukkit events

Pre-mutation (cancellable): `AccountRenameEvent`, `AccountDeleteEvent`, `BalanceChangeEvent`, `PayEvent`.

Post-success: `AccountCreateEvent`, `AccountRenamedEvent`, `AccountDeletedEvent`, `BalanceChangedEvent`, `PayCompletedEvent`.

## Practical example

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
        TransferPreviewResult preview = api.previewTransfer(
                player.getUniqueId(), bankId, price);
        if (!preview.isAllowed()) {
            return false;
        }

        TransferResult result = api.transfer(player.getUniqueId(), bankId, price);
        return result.isSuccess();
    }
}
```

For full method signatures, result status enums, and validation rules, see the `api` module source and Javadoc in the repository.
