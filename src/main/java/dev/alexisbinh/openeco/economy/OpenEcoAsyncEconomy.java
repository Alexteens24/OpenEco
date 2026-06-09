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

    OpenEcoAsyncEconomy(OpenEcoEconomyProvider sync) {
        this.sync = sync;
    }

    @Override
    public @NotNull CompletableFuture<Boolean> createAccount(@NotNull UUID accountID, @NotNull String name, boolean player) {
        return completed(sync.createAccount(accountID, name, player));
    }

    @Override
    public @NotNull CompletableFuture<Boolean> createAccount(
            @NotNull UUID accountID, @NotNull String name, @NotNull String worldName, boolean player) {
        return completed(sync.createAccount(accountID, name, worldName, player));
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
        return completed(sync.renameAccount(pluginName, accountID, name));
    }

    @Override
    public @NotNull CompletableFuture<Boolean> deleteAccount(@NotNull String pluginName, @NotNull UUID accountID) {
        return completed(sync.deleteAccount(pluginName, accountID));
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
        return completed(sync.set(pluginName, accountID, amount));
    }

    @Override
    public @NotNull CompletableFuture<EconomyResponse> set(
            @NotNull String pluginName, @NotNull UUID accountID, @NotNull String world, @NotNull BigDecimal amount) {
        return completed(sync.set(pluginName, accountID, world, amount));
    }

    @Override
    public @NotNull CompletableFuture<EconomyResponse> set(
            @NotNull String pluginName,
            @NotNull UUID accountID,
            @NotNull String world,
            @NotNull String currency,
            @NotNull BigDecimal amount) {
        return completed(sync.set(pluginName, accountID, world, currency, amount));
    }

    @Override
    public CompletableFuture<MultiEconomyResponse> transfer(
            @NotNull String pluginName, @NotNull UUID from, @NotNull UUID to, @NotNull BigDecimal amount) {
        return completed(sync.transfer(pluginName, from, to, amount));
    }

    @Override
    public CompletableFuture<MultiEconomyResponse> transfer(
            @NotNull String pluginName,
            @NotNull UUID from,
            @NotNull UUID to,
            @NotNull String worldName,
            @NotNull BigDecimal amount) {
        return completed(sync.transfer(pluginName, from, to, worldName, amount));
    }

    @Override
    public CompletableFuture<MultiEconomyResponse> transfer(
            @NotNull String pluginName,
            @NotNull UUID from,
            @NotNull UUID to,
            @NotNull String worldName,
            @NotNull String currency,
            @NotNull BigDecimal amount) {
        return completed(sync.transfer(pluginName, from, to, worldName, currency, amount));
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
        return completed(sync.withdraw(pluginName, accountID, amount));
    }

    @Override
    public @NotNull CompletableFuture<EconomyResponse> withdraw(
            @NotNull String pluginName, @NotNull UUID accountID, @NotNull String world, @NotNull BigDecimal amount) {
        return completed(sync.withdraw(pluginName, accountID, world, amount));
    }

    @Override
    public @NotNull CompletableFuture<EconomyResponse> withdraw(
            @NotNull String pluginName,
            @NotNull UUID accountID,
            @NotNull String world,
            @NotNull String currency,
            @NotNull BigDecimal amount) {
        return completed(sync.withdraw(pluginName, accountID, world, currency, amount));
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
        return completed(sync.deposit(pluginName, accountID, amount));
    }

    @Override
    public @NotNull CompletableFuture<EconomyResponse> deposit(
            @NotNull String pluginName, @NotNull UUID accountID, @NotNull String world, @NotNull BigDecimal amount) {
        return completed(sync.deposit(pluginName, accountID, world, amount));
    }

    @Override
    public @NotNull CompletableFuture<EconomyResponse> deposit(
            @NotNull String pluginName,
            @NotNull UUID accountID,
            @NotNull String world,
            @NotNull String currency,
            @NotNull BigDecimal amount) {
        return completed(sync.deposit(pluginName, accountID, world, currency, amount));
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
}
