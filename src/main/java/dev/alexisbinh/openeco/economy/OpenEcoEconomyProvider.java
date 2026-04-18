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

import dev.alexisbinh.openeco.api.BalanceCheckResult;
import dev.alexisbinh.openeco.service.AccountService;
import dev.alexisbinh.openeco.service.EconomyOperationResponse;
import net.milkbowl.vault2.economy.Economy;
import net.milkbowl.vault2.economy.EconomyResponse;

import java.math.BigDecimal;
import java.util.*;

public class OpenEcoEconomyProvider implements Economy {

    private final AccountService service;

    public OpenEcoEconomyProvider(AccountService service) {
        this.service = service;
    }

    // ── Basic info ────────────────────────────────────────────────────────────

    @Override public boolean isEnabled() { return true; }
    @Override public String getName() { return "openeco"; }
    @Override public boolean hasSharedAccountSupport() { return false; }
    @Override public boolean hasMultiCurrencySupport() { return true; }

    @Override
    public int fractionalDigits(String pluginName) {
        return service.getFractionalDigits();
    }

    @Override
    public int fractionalDigits(String pluginName, String currency) {
        return service.hasCurrency(currency) ? service.getFractionalDigits(currency) : service.getFractionalDigits();
    }

    // ── Format ────────────────────────────────────────────────────────────────

    @Override
    public String format(BigDecimal amount) {
        return service.format(amount);
    }

    @Override
    public String format(String pluginName, BigDecimal amount) {
        return service.format(amount);
    }

    @Override
    public String format(BigDecimal amount, String currency) {
        return service.format(amount, currency);
    }

    @Override
    public String format(String pluginName, BigDecimal amount, String currency) {
        return service.format(amount, currency);
    }

    // ── Currency ──────────────────────────────────────────────────────────────

    @Override
    public boolean hasCurrency(String currency) {
        return service.hasCurrency(currency);
    }

    @Override
    public String getDefaultCurrency(String pluginName) {
        return service.getCurrencyId();
    }

    @Override
    public String defaultCurrencyNameSingular(String pluginName) {
        return service.getCurrencySingular();
    }

    @Override
    public String defaultCurrencyNamePlural(String pluginName) {
        return service.getCurrencyPlural();
    }

    @Override
    public Collection<String> currencies() {
        return service.getCurrencyIds();
    }

    // ── Account management ────────────────────────────────────────────────────

    @Override
    public boolean createAccount(UUID accountID, String name) {
        return service.createAccount(accountID, name);
    }

    @Override
    public boolean createAccount(UUID accountID, String name, boolean player) {
        return service.createAccount(accountID, name);
    }

    @Override
    public boolean createAccount(UUID accountID, String name, String worldName) {
        return service.createAccount(accountID, name);
    }

    @Override
    public boolean createAccount(UUID accountID, String name, String worldName, boolean player) {
        return service.createAccount(accountID, name);
    }

    @Override
    public Map<UUID, String> getUUIDNameMap() {
        return service.getUUIDNameMap();
    }

    @Override
    public Optional<String> getAccountName(UUID accountID) {
        return service.getAccount(accountID).map(r -> r.getLastKnownName());
    }

    @Override
    public boolean hasAccount(UUID accountID) {
        return service.hasAccount(accountID);
    }

    @Override
    public boolean hasAccount(UUID accountID, String worldName) {
        return service.hasAccount(accountID);
    }

    @Override
    public boolean renameAccount(UUID accountID, String name) {
        return service.renameAccount(accountID, name);
    }

    @Override
    public boolean renameAccount(String pluginName, UUID accountID, String name) {
        return service.renameAccount(accountID, name);
    }

    @Override
    public boolean deleteAccount(String pluginName, UUID accountID) {
        return service.deleteAccount(accountID);
    }

    @Override
    public boolean accountSupportsCurrency(String pluginName, UUID accountID, String currency) {
        return service.hasAccount(accountID) && service.hasCurrency(currency);
    }

    @Override
    public boolean accountSupportsCurrency(String pluginName, UUID accountID, String currency, String world) {
        return service.hasAccount(accountID) && service.hasCurrency(currency);
    }

    // ── Balance ───────────────────────────────────────────────────────────────

    @Override
    public BigDecimal getBalance(String pluginName, UUID accountID) {
        return service.getBalance(accountID);
    }

    @Override
    public BigDecimal getBalance(String pluginName, UUID accountID, String world) {
        return service.getBalance(accountID);
    }

    @Override
    public BigDecimal getBalance(String pluginName, UUID accountID, String world, String currency) {
        return service.hasCurrency(currency) ? service.getBalance(accountID, currency) : BigDecimal.ZERO;
    }

    @Override
    public boolean has(String pluginName, UUID accountID, BigDecimal amount) {
        return service.has(accountID, amount);
    }

    @Override
    public boolean has(String pluginName, UUID accountID, String worldName, BigDecimal amount) {
        return service.has(accountID, amount);
    }

    @Override
    public boolean has(String pluginName, UUID accountID, String worldName, String currency, BigDecimal amount) {
        return service.has(accountID, currency, amount);
    }

    // ── Transactions ──────────────────────────────────────────────────────────

    @Override
    public EconomyResponse withdraw(String pluginName, UUID accountID, BigDecimal amount) {
        return toV2(service.withdraw(accountID, amount));
    }

    @Override
    public EconomyResponse withdraw(String pluginName, UUID accountID, String worldName, BigDecimal amount) {
        return toV2(service.withdraw(accountID, amount));
    }

    @Override
    public EconomyResponse withdraw(String pluginName, UUID accountID, String worldName, String currency, BigDecimal amount) {
        return toV2(service.withdraw(accountID, currency, amount));
    }

    @Override
    public EconomyResponse deposit(String pluginName, UUID accountID, BigDecimal amount) {
        return toV2(service.deposit(accountID, amount));
    }

    @Override
    public EconomyResponse deposit(String pluginName, UUID accountID, String worldName, BigDecimal amount) {
        return toV2(service.deposit(accountID, amount));
    }

    @Override
    public EconomyResponse deposit(String pluginName, UUID accountID, String worldName, String currency, BigDecimal amount) {
        return toV2(service.deposit(accountID, currency, amount));
    }

    @Override
    public EconomyResponse set(String pluginName, UUID accountID, BigDecimal amount) {
        return toV2(service.set(accountID, amount));
    }

    @Override
    public EconomyResponse set(String pluginName, UUID accountID, String worldName, BigDecimal amount) {
        return toV2(service.set(accountID, amount));
    }

    @Override
    public EconomyResponse set(String pluginName, UUID accountID, String worldName, String currency, BigDecimal amount) {
        return toV2(service.set(accountID, currency, amount));
    }

    // ── canWithdraw / canDeposit ──────────────────────────────────────────────

    @Override
    public EconomyResponse canWithdraw(String pluginName, UUID accountID, BigDecimal amount) {
        return toVaultResponse(service.canWithdraw(accountID, amount));
    }

    @Override
    public EconomyResponse canWithdraw(String pluginName, UUID accountID, String worldName, BigDecimal amount) {
        return canWithdraw(pluginName, accountID, amount);
    }

    @Override
    public EconomyResponse canWithdraw(String pluginName, UUID accountID, String worldName, String currency, BigDecimal amount) {
        return toVaultResponse(service.canWithdraw(accountID, currency, amount));
    }

    @Override
    public EconomyResponse canDeposit(String pluginName, UUID accountID, BigDecimal amount) {
        return toVaultResponse(service.canDeposit(accountID, amount));
    }

    @Override
    public EconomyResponse canDeposit(String pluginName, UUID accountID, String worldName, BigDecimal amount) {
        return canDeposit(pluginName, accountID, amount);
    }

    @Override
    public EconomyResponse canDeposit(String pluginName, UUID accountID, String worldName, String currency, BigDecimal amount) {
        return toVaultResponse(service.canDeposit(accountID, currency, amount));
    }

    // ── Shared accounts (not supported) ──────────────────────────────────────

    private static EconomyResponse toVaultResponse(BalanceCheckResult result) {
        if (result.isAllowed()) {
            return new EconomyResponse(result.amount(), result.currentBalance(), EconomyResponse.ResponseType.SUCCESS, "");
        }
        String message = switch (result.status()) {
            case UNKNOWN_CURRENCY -> "Unknown currency";
            case ACCOUNT_NOT_FOUND -> "Account not found";
            case INVALID_AMOUNT -> "Amount must be positive";
            case INSUFFICIENT_FUNDS -> "Insufficient funds";
            case BALANCE_LIMIT -> "Balance limit reached";
            case FROZEN -> "Account is frozen";
            default -> "Operation failed";
        };
        return new EconomyResponse(result.amount(), result.currentBalance(), EconomyResponse.ResponseType.FAILURE, message);
    }

    private static EconomyResponse toV2(EconomyOperationResponse response) {
        EconomyResponse.ResponseType type = switch (response.type()) {
            case SUCCESS -> EconomyResponse.ResponseType.SUCCESS;
            case NOT_IMPLEMENTED -> EconomyResponse.ResponseType.NOT_IMPLEMENTED;
            default -> EconomyResponse.ResponseType.FAILURE;
        };
        return new EconomyResponse(
                response.amount(),
                response.balance(),
                type,
                response.errorMessage());
    }

    @Override public boolean createSharedAccount(String p, UUID accountID, String name, UUID owner) { return false; }
    @Override public boolean isAccountOwner(String p, UUID accountID, UUID uuid) { return false; }
    @Override public boolean setOwner(String p, UUID accountID, UUID uuid) { return false; }
    @Override public boolean isAccountMember(String p, UUID accountID, UUID uuid) { return false; }
    @Override public boolean addAccountMember(String p, UUID accountID, UUID uuid) { return false; }
    @Override public boolean addAccountMember(String p, UUID accountID, UUID uuid,
            net.milkbowl.vault2.economy.AccountPermission... perms) { return false; }
    @Override public boolean removeAccountMember(String p, UUID accountID, UUID uuid) { return false; }
    @Override public boolean hasAccountPermission(String p, UUID accountID, UUID uuid,
            net.milkbowl.vault2.economy.AccountPermission permission) { return false; }
    @Override public boolean updateAccountPermission(String p, UUID accountID, UUID uuid,
            net.milkbowl.vault2.economy.AccountPermission permission, boolean value) { return false; }
}
