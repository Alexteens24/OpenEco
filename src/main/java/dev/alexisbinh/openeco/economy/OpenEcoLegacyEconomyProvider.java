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

import dev.alexisbinh.openeco.service.AccountService;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import dev.alexisbinh.openeco.service.EconomyOperationResponse;

/**
 * Legacy Vault v1 Economy adapter.
 * Some plugins (e.g. ShopGUI+, SmartSpawner) query {@code net.milkbowl.vault.economy.Economy}
 * via the Bukkit ServicesManager. This adapter bridges them to openeco's AccountService.
 */
@SuppressWarnings("deprecation")
public class OpenEcoLegacyEconomyProvider implements Economy {

    private final AccountService service;
    private final Function<String, Optional<OfflinePlayer>> knownPlayerLookup;

    public OpenEcoLegacyEconomyProvider(AccountService service) {
        this(service, OpenEcoLegacyEconomyProvider::lookupKnownPlayer);
    }

    OpenEcoLegacyEconomyProvider(AccountService service,
                                   Function<String, Optional<OfflinePlayer>> knownPlayerLookup) {
        this.service = service;
        this.knownPlayerLookup = knownPlayerLookup;
    }

    // ── Basic info ────────────────────────────────────────────────────────────

    @Override public boolean isEnabled() { return true; }
    @Override public String getName() { return "openeco"; }
    @Override public boolean hasBankSupport() { return false; }
    @Override public int fractionalDigits() { return service.getFractionalDigits(); }
    @Override public String format(double amount) { return service.format(BigDecimal.valueOf(amount)); }
    @Override public String currencyNamePlural() { return service.getCurrencyPlural(); }
    @Override public String currencyNameSingular() { return service.getCurrencySingular(); }

    // ── Account queries ───────────────────────────────────────────────────────

    private Optional<UUID> accountIdOf(String playerName) {
        return service.findByName(playerName)
            .map(dev.alexisbinh.openeco.model.AccountRecord::getId);
    }

    private static Optional<OfflinePlayer> lookupKnownPlayer(String playerName) {
        if (Bukkit.getServer() == null) {
            return Optional.empty();
        }
        Player online = Bukkit.getPlayerExact(playerName);
        if (online != null) {
            return Optional.of(online);
        }
        return Optional.ofNullable(Bukkit.getOfflinePlayerIfCached(playerName));
    }

    private static EconomyResponse accountNotFound() {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Account not found");
    }

    @Override public boolean hasAccount(String playerName) { return service.findByName(playerName).isPresent(); }
    @Override public boolean hasAccount(OfflinePlayer player) { return service.hasAccount(player.getUniqueId()); }
    @Override public boolean hasAccount(String playerName, String worldName) { return hasAccount(playerName); }
    @Override public boolean hasAccount(OfflinePlayer player, String worldName) { return hasAccount(player); }

    // ── Balance queries ───────────────────────────────────────────────────────

    @Override public double getBalance(String playerName) {
        return service.findByName(playerName)
                .map(account -> account.getBalance().doubleValue())
                .orElse(0D);
    }
    @Override public double getBalance(OfflinePlayer player) { return service.getBalance(player.getUniqueId()).doubleValue(); }
    @Override public double getBalance(String playerName, String world) { return getBalance(playerName); }
    @Override public double getBalance(OfflinePlayer player, String world) { return getBalance(player); }

    @Override public boolean has(String playerName, double amount) {
        if (amount < 0) {
            return false;
        }
        BigDecimal requestedAmount = BigDecimal.valueOf(amount);
        return accountIdOf(playerName)
                .map(accountId -> service.has(accountId, requestedAmount))
                .orElse(false);
    }
    @Override public boolean has(OfflinePlayer player, double amount) { return service.has(player.getUniqueId(), BigDecimal.valueOf(amount)); }
    @Override public boolean has(String playerName, String worldName, double amount) { return has(playerName, amount); }
    @Override public boolean has(OfflinePlayer player, String worldName, double amount) { return has(player, amount); }

    // ── Balance mutations ─────────────────────────────────────────────────────

    @Override public EconomyResponse withdrawPlayer(String playerName, double amount) {
        return accountIdOf(playerName)
                .map(accountId -> toV1(service.withdraw(accountId, BigDecimal.valueOf(amount))))
                .orElseGet(OpenEcoLegacyEconomyProvider::accountNotFound);
    }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) { return toV1(service.withdraw(player.getUniqueId(), BigDecimal.valueOf(amount))); }
    @Override public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) { return withdrawPlayer(playerName, amount); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) { return withdrawPlayer(player, amount); }

    @Override public EconomyResponse depositPlayer(String playerName, double amount) {
        return accountIdOf(playerName)
                .map(accountId -> toV1(service.deposit(accountId, BigDecimal.valueOf(amount))))
                .orElseGet(OpenEcoLegacyEconomyProvider::accountNotFound);
    }
    @Override public EconomyResponse depositPlayer(OfflinePlayer player, double amount) { return toV1(service.deposit(player.getUniqueId(), BigDecimal.valueOf(amount))); }
    @Override public EconomyResponse depositPlayer(String playerName, String worldName, double amount) { return depositPlayer(playerName, amount); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) { return depositPlayer(player, amount); }

    // ── Account creation ──────────────────────────────────────────────────────

    @Override
    public boolean createPlayerAccount(String playerName) {
        if (service.findByName(playerName).isPresent()) {
            return true;
        }

        Optional<OfflinePlayer> player = knownPlayerLookup.apply(playerName);
        if (player.isEmpty()) {
            return false;
        }
        String resolvedName = player.get().getName();
        if (resolvedName == null || resolvedName.isBlank()) {
            resolvedName = playerName;
        }
        return service.createAccount(player.get().getUniqueId(), resolvedName);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        String name = player.getName();
        if (name == null) {
            return false;
        }
        return service.createAccount(player.getUniqueId(), name);
    }

    @Override public boolean createPlayerAccount(String playerName, String worldName) { return false; }
    @Override public boolean createPlayerAccount(OfflinePlayer player, String worldName) { return false; }

    // ── Bank operations (unsupported) ─────────────────────────────────────────

    private static EconomyResponse notImpl() {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Bank support is not available");
    }

    @Override public EconomyResponse createBank(String name, String player) { return notImpl(); }
    @Override public EconomyResponse createBank(String name, OfflinePlayer player) { return notImpl(); }
    @Override public EconomyResponse deleteBank(String name) { return notImpl(); }
    @Override public EconomyResponse bankBalance(String name) { return notImpl(); }
    @Override public EconomyResponse bankHas(String name, double amount) { return notImpl(); }
    @Override public EconomyResponse bankWithdraw(String name, double amount) { return notImpl(); }
    @Override public EconomyResponse bankDeposit(String name, double amount) { return notImpl(); }
    @Override public EconomyResponse isBankOwner(String name, String playerName) { return notImpl(); }
    @Override public EconomyResponse isBankOwner(String name, OfflinePlayer player) { return notImpl(); }
    @Override public EconomyResponse isBankMember(String name, String playerName) { return notImpl(); }
    @Override public EconomyResponse isBankMember(String name, OfflinePlayer player) { return notImpl(); }
    @Override public List<String> getBanks() { return Collections.emptyList(); }

    private static EconomyResponse toV1(EconomyOperationResponse response) {
        EconomyResponse.ResponseType type = switch (response.type()) {
            case SUCCESS -> EconomyResponse.ResponseType.SUCCESS;
            case NOT_IMPLEMENTED -> EconomyResponse.ResponseType.NOT_IMPLEMENTED;
            default -> EconomyResponse.ResponseType.FAILURE;
        };
        return new EconomyResponse(
                response.amount().doubleValue(),
                response.balance().doubleValue(),
                type,
                response.errorMessage());
    }

}
