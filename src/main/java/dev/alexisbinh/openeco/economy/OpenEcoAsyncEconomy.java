/*
 * Copyright 2026 alexisbinh
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.alexisbinh.openeco.economy;

import dev.alexisbinh.openeco.api.AccountOperationResult;
import dev.alexisbinh.openeco.api.AccountTransferResult;
import dev.alexisbinh.openeco.api.BalanceChangeResult;
import dev.alexisbinh.openeco.api.OpenEcoAsyncApi;
import net.milkbowl.vault2.economy.AccountPermission;
import net.milkbowl.vault2.economy.AsyncEconomy;
import net.milkbowl.vault2.economy.EconomyResponse;
import net.milkbowl.vault2.economy.MultiEconomyResponse;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Async facade over {@link OpenEcoEconomyProvider} for VaultUnlocked 2.20+. */
final class OpenEcoAsyncEconomy implements AsyncEconomy {

    private final OpenEcoEconomyProvider sync;
    private final OpenEcoAsyncApi asyncApi;
    private final String defaultCurrency;

    OpenEcoAsyncEconomy(OpenEcoEconomyProvider sync, OpenEcoAsyncApi asyncApi, String defaultCurrency) {
        this.sync = sync;
        this.asyncApi = asyncApi;
        this.defaultCurrency = defaultCurrency;
    }

    @Override
    public @NotNull CompletableFuture<Boolean> createAccount(@NotNull UUID accountID, @NotNull String name, boolean player) {
        return future(asyncApi.createAccount(accountID, name)).thenApply(OpenEcoAsyncEconomy::accountWriteSucceeded);
    }

    @Override
    public @NotNull CompletableFuture<Boolean> createAccount(
            @NotNull UUID accountID, @NotNull String name, @NotNull String worldName, boolean player) {
        return future(asyncApi.createAccount(accountID, name)).thenApply(OpenEcoAsyncEconomy::accountWriteSucceeded);
    }

    @Override
    public @NotNull CompletableFuture<Map<UUID, String>> getUUIDNameMap() {
        return completed(sync.getUUIDNameMap());
    }

    @Override
    public @NotNull CompletableFuture<Optional<String>> getAccountName(@NotNull UUID accountID) {
        return completed(sync.getAccountName(accountID));
    }

    @Override
    public @NotNull CompletableFuture<Boolean> hasAccount(@NotNull UUID accountID) {
        return completed(sync.hasAccount(accountID));
    }

    @Override
    public @NotNull CompletableFuture<Boolean> hasAccount(@NotNull UUID accountID, @NotNull String worldName) {
        return completed(sync.hasAccount(accountID, worldName));
    }

    @Override
    public @NotNull CompletableFuture<Boolean> renameAccount(
            @NotNull String pluginName, @NotNull UUID accountID, @NotNull String name) {
        return future(asyncApi.renameAccount(accountID, name)).thenApply(OpenEcoAsyncEconomy::accountWriteSucceeded);
    }

    @Override
    public @NotNull CompletableFuture<Boolean> deleteAccount(@NotNull String pluginName, @NotNull UUID accountID) {
        return future(asyncApi.deleteAccount(accountID)).thenApply(OpenEcoAsyncEconomy::accountWriteSucceeded);
    }

    @Override
    public @NotNull CompletableFuture<Boolean> accountSupportsCurrency(
            @NotNull String pluginName, @NotNull UUID accountID, @NotNull String currency) {
        return completed(sync.accountSupportsCurrency(pluginName, accountID, currency));
    }

    @Override
    public @NotNull CompletableFuture<Boolean> accountSupportsCurrency(
            @NotNull String pluginName, @NotNull UUID accountID, @NotNull String currency, @NotNull String world) {
        return completed(sync.accountSupportsCurrency(pluginName, accountID, currency, world));
    }

    @Override
    public @NotNull CompletableFuture<BigDecimal> balance(@NotNull String pluginName, @NotNull UUID accountID) {
        return completed(sync.balance(pluginName, accountID));
    }

    @Override
    public @NotNull CompletableFuture<BigDecimal> balance(
            @NotNull String pluginName, @NotNull UUID accountID, @NotNull String world) {
        return completed(sync.balance(pluginName, accountID, world));
    }

    @Override
    public @NotNull CompletableFuture<BigDecimal> balance(
            @NotNull String pluginName, @NotNull UUID accountID, @NotNull String world, @NotNull String currency) {
        return completed(sync.balance(pluginName, accountID, world, currency));
    }

    @Override
    public @NotNull CompletableFuture<Boolean> has(
            @NotNull String pluginName, @NotNull UUID accountID, @NotNull BigDecimal amount) {
        return completed(sync.has(pluginName, accountID, amount));
    }

    @Override
    public @NotNull CompletableFuture<Boolean> has(
            @NotNull String pluginName, @NotNull UUID accountID, @NotNull String world, @NotNull BigDecimal amount) {
        return completed(sync.has(pluginName, accountID, world, amount));
    }

    @Override
    public @NotNull CompletableFuture<Boolean> has(
            @NotNull String pluginName,
            @NotNull UUID accountID,
            @NotNull String world,
            @NotNull String currency,
            @NotNull BigDecimal amount) {
        return completed(sync.has(pluginName, accountID, world, currency, amount));
    }

    @Override
    public @NotNull CompletableFuture<EconomyResponse> set(
            @NotNull String pluginName, @NotNull UUID accountID, @NotNull BigDecimal amount) {
        return future(asyncApi.setBalance(accountID, defaultCurrency, amount)).thenApply(OpenEcoAsyncEconomy::toEconomyResponse);
    }

    @Override
    public @NotNull CompletableFuture<EconomyResponse> set(
            @NotNull String pluginName, @NotNull UUID accountID, @NotNull String world, @NotNull BigDecimal amount) {
        return future(asyncApi.setBalance(accountID, defaultCurrency, amount)).thenApply(OpenEcoAsyncEconomy::toEconomyResponse);
    }

    @Override
    public @NotNull CompletableFuture<EconomyResponse> set(
            @NotNull String pluginName,
            @NotNull UUID accountID,
            @NotNull String world,
            @NotNull String currency,
            @NotNull BigDecimal amount) {
        return future(asyncApi.setBalance(accountID, currency, amount)).thenApply(OpenEcoAsyncEconomy::toEconomyResponse);
    }

    @Override
    public CompletableFuture<MultiEconomyResponse> transfer(
            @NotNull String pluginName, @NotNull UUID from, @NotNull UUID to, @NotNull BigDecimal amount) {
        return future(asyncApi.directTransfer(from, to, defaultCurrency, amount))
                .thenApply(result -> toMultiTransfer(result, from, to));
    }

    @Override
    public CompletableFuture<MultiEconomyResponse> transfer(
            @NotNull String pluginName,
            @NotNull UUID from,
            @NotNull UUID to,
            @NotNull String worldName,
            @NotNull BigDecimal amount) {
        return future(asyncApi.directTransfer(from, to, defaultCurrency, amount))
                .thenApply(result -> toMultiTransfer(result, from, to));
    }

    @Override
    public CompletableFuture<MultiEconomyResponse> transfer(
            @NotNull String pluginName,
            @NotNull UUID from,
            @NotNull UUID to,
            @NotNull String worldName,
            @NotNull String currency,
            @NotNull BigDecimal amount) {
        return future(asyncApi.directTransfer(from, to, currency, amount))
                .thenApply(result -> toMultiTransfer(result, from, to));
    }

    @Override
    public @NotNull CompletableFuture<EconomyResponse> canWithdraw(
            @NotNull String pluginName, @NotNull UUID accountID, @NotNull BigDecimal amount) {
        return completed(sync.canWithdraw(pluginName, accountID, amount));
    }

    @Override
    public @NotNull CompletableFuture<EconomyResponse> canWithdraw(
            @NotNull String pluginName, @NotNull UUID accountID, @NotNull String world, @NotNull BigDecimal amount) {
        return completed(sync.canWithdraw(pluginName, accountID, world, amount));
    }

    @Override
    public @NotNull CompletableFuture<EconomyResponse> canWithdraw(
            @NotNull String pluginName,
            @NotNull UUID accountID,
            @NotNull String world,
            @NotNull String currency,
            @NotNull BigDecimal amount) {
        return completed(sync.canWithdraw(pluginName, accountID, world, currency, amount));
    }

    @Override
    public @NotNull CompletableFuture<EconomyResponse> withdraw(
            @NotNull String pluginName, @NotNull UUID accountID, @NotNull BigDecimal amount) {
        return future(asyncApi.withdraw(accountID, defaultCurrency, amount)).thenApply(OpenEcoAsyncEconomy::toEconomyResponse);
    }

    @Override
    public @NotNull CompletableFuture<EconomyResponse> withdraw(
            @NotNull String pluginName, @NotNull UUID accountID, @NotNull String world, @NotNull BigDecimal amount) {
        return future(asyncApi.withdraw(accountID, defaultCurrency, amount)).thenApply(OpenEcoAsyncEconomy::toEconomyResponse);
    }

    @Override
    public @NotNull CompletableFuture<EconomyResponse> withdraw(
            @NotNull String pluginName,
            @NotNull UUID accountID,
            @NotNull String world,
            @NotNull String currency,
            @NotNull BigDecimal amount) {
        return future(asyncApi.withdraw(accountID, currency, amount)).thenApply(OpenEcoAsyncEconomy::toEconomyResponse);
    }

    @Override
    public @NotNull CompletableFuture<EconomyResponse> canDeposit(
            @NotNull String pluginName, @NotNull UUID accountID, @NotNull BigDecimal amount) {
        return completed(sync.canDeposit(pluginName, accountID, amount));
    }

    @Override
    public @NotNull CompletableFuture<EconomyResponse> canDeposit(
            @NotNull String pluginName, @NotNull UUID accountID, @NotNull String world, @NotNull BigDecimal amount) {
        return completed(sync.canDeposit(pluginName, accountID, world, amount));
    }

    @Override
    public @NotNull CompletableFuture<EconomyResponse> canDeposit(
            @NotNull String pluginName,
            @NotNull UUID accountID,
            @NotNull String world,
            @NotNull String currency,
            @NotNull BigDecimal amount) {
        return completed(sync.canDeposit(pluginName, accountID, world, currency, amount));
    }

    @Override
    public @NotNull CompletableFuture<EconomyResponse> deposit(
            @NotNull String pluginName, @NotNull UUID accountID, @NotNull BigDecimal amount) {
        return future(asyncApi.deposit(accountID, defaultCurrency, amount)).thenApply(OpenEcoAsyncEconomy::toEconomyResponse);
    }

    @Override
    public @NotNull CompletableFuture<EconomyResponse> deposit(
            @NotNull String pluginName, @NotNull UUID accountID, @NotNull String world, @NotNull BigDecimal amount) {
        return future(asyncApi.deposit(accountID, defaultCurrency, amount)).thenApply(OpenEcoAsyncEconomy::toEconomyResponse);
    }

    @Override
    public @NotNull CompletableFuture<EconomyResponse> deposit(
            @NotNull String pluginName,
            @NotNull UUID accountID,
            @NotNull String world,
            @NotNull String currency,
            @NotNull BigDecimal amount) {
        return future(asyncApi.deposit(accountID, currency, amount)).thenApply(OpenEcoAsyncEconomy::toEconomyResponse);
    }

    @Override
    public @NotNull CompletableFuture<Boolean> createSharedAccount(
            @NotNull String pluginName, @NotNull UUID accountID, @NotNull String name, @NotNull UUID owner) {
        return completed(sync.createSharedAccount(pluginName, accountID, name, owner));
    }

    @Override
    public @NotNull CompletableFuture<List<UUID>> accountsWithOwnerOf(
            @NotNull String pluginName, @NotNull UUID accountID) {
        return completed(sync.accountsWithOwnerOf(pluginName, accountID));
    }

    @Override
    public @NotNull CompletableFuture<List<UUID>> accountsWithMembershipTo(
            @NotNull String pluginName, @NotNull UUID accountID) {
        return completed(sync.accountsWithMembershipTo(pluginName, accountID));
    }

    @Override
    public @NotNull CompletableFuture<List<UUID>> accountsWithAccessTo(
            @NotNull String pluginName, @NotNull UUID accountID, @NotNull AccountPermission... permissions) {
        return completed(sync.accountsWithAccessTo(pluginName, accountID, permissions));
    }

    @Override
    public @NotNull CompletableFuture<Boolean> isAccountOwner(
            @NotNull String pluginName, @NotNull UUID accountID, @NotNull UUID uuid) {
        return completed(sync.isAccountOwner(pluginName, accountID, uuid));
    }

    @Override
    public @NotNull CompletableFuture<Boolean> setOwner(
            @NotNull String pluginName, @NotNull UUID accountID, @NotNull UUID uuid) {
        return completed(sync.setOwner(pluginName, accountID, uuid));
    }

    @Override
    public @NotNull CompletableFuture<Boolean> isAccountMember(
            @NotNull String pluginName, @NotNull UUID accountID, @NotNull UUID uuid) {
        return completed(sync.isAccountMember(pluginName, accountID, uuid));
    }

    @Override
    public @NotNull CompletableFuture<Boolean> addAccountMember(
            @NotNull String pluginName, @NotNull UUID accountID, @NotNull UUID uuid) {
        return completed(sync.addAccountMember(pluginName, accountID, uuid));
    }

    @Override
    public @NotNull CompletableFuture<Boolean> addAccountMember(
            @NotNull String pluginName,
            @NotNull UUID accountID,
            @NotNull UUID uuid,
            @NotNull AccountPermission... initialPermissions) {
        return completed(sync.addAccountMember(pluginName, accountID, uuid, initialPermissions));
    }

    @Override
    public @NotNull CompletableFuture<Boolean> removeAccountMember(
            @NotNull String pluginName, @NotNull UUID accountID, @NotNull UUID uuid) {
        return completed(sync.removeAccountMember(pluginName, accountID, uuid));
    }

    @Override
    public @NotNull CompletableFuture<Boolean> hasAccountPermission(
            @NotNull String pluginName,
            @NotNull UUID accountID,
            @NotNull UUID uuid,
            @NotNull AccountPermission permission) {
        return completed(sync.hasAccountPermission(pluginName, accountID, uuid, permission));
    }

    @Override
    public @NotNull CompletableFuture<Boolean> updateAccountPermission(
            @NotNull String pluginName,
            @NotNull UUID accountID,
            @NotNull UUID uuid,
            @NotNull AccountPermission permission,
            boolean value) {
        return completed(sync.updateAccountPermission(pluginName, accountID, uuid, permission, value));
    }

    private static <T> CompletableFuture<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }

    private static boolean accountWriteSucceeded(AccountOperationResult result) {
        return result.isSuccess() || result.status() == AccountOperationResult.Status.ALREADY_EXISTS
                || result.status() == AccountOperationResult.Status.UNCHANGED;
    }

    private static EconomyResponse toEconomyResponse(BalanceChangeResult result) {
        EconomyResponse.ResponseType type = result.isSuccess()
                ? EconomyResponse.ResponseType.SUCCESS : EconomyResponse.ResponseType.FAILURE;
        return new EconomyResponse(result.amount(), result.newBalance(), type,
                result.isSuccess() ? "" : result.status().name());
    }

    private static MultiEconomyResponse toMultiTransfer(AccountTransferResult result, UUID from, UUID to) {
        if (result.isSuccess()) {
            MultiEconomyResponse response = new MultiEconomyResponse(
                    result.amount(), EconomyResponse.ResponseType.SUCCESS, "");
            response.addBalance(from, result.fromBalance());
            response.addBalance(to, result.toBalance());
            return response;
        }
        return new MultiEconomyResponse(
                result.amount(), EconomyResponse.ResponseType.FAILURE, result.message());
    }

    private static <T> CompletableFuture<T> future(java.util.concurrent.CompletionStage<T> stage) {
        return stage.toCompletableFuture();
    }
}
