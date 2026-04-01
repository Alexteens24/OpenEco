package dev.alexisbinh.simpleeco.economy;

import dev.alexisbinh.simpleeco.service.AccountService;
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

/**
 * Legacy Vault v1 Economy adapter.
 * Some plugins (e.g. ShopGUI+, SmartSpawner) query {@code net.milkbowl.vault.economy.Economy}
 * via the Bukkit ServicesManager. This adapter bridges them to SimpleEco's AccountService.
 */
@SuppressWarnings("deprecation")
public class SimpleEcoLegacyEconomyProvider implements Economy {

    private final AccountService service;

    public SimpleEcoLegacyEconomyProvider(AccountService service) {
        this.service = service;
    }

    // ── Basic info ────────────────────────────────────────────────────────────

    @Override public boolean isEnabled() { return true; }
    @Override public String getName() { return "SimpleEco"; }
    @Override public boolean hasBankSupport() { return false; }
    @Override public int fractionalDigits() { return service.getFractionalDigits(); }
    @Override public String format(double amount) { return service.format(BigDecimal.valueOf(amount)); }
    @Override public String currencyNamePlural() { return service.getCurrencyPlural(); }
    @Override public String currencyNameSingular() { return service.getCurrencySingular(); }

    // ── Account queries ───────────────────────────────────────────────────────

    private Optional<UUID> accountIdOf(String playerName) {
        Optional<UUID> resolvedByName = service.findByName(playerName)
                .map(dev.alexisbinh.simpleeco.model.AccountRecord::getId);
        if (resolvedByName.isPresent()) {
            return resolvedByName;
        }

        return knownPlayer(playerName)
                .map(OfflinePlayer::getUniqueId)
                .filter(service::hasAccount);
    }

    private Optional<OfflinePlayer> knownPlayer(String playerName) {
        if (Bukkit.getServer() == null) {
            return Optional.empty();
        }
        Player online = Bukkit.getPlayerExact(playerName);
        if (online != null) {
            return Optional.of(online);
        }
        return Optional.ofNullable(Bukkit.getOfflinePlayerIfCached(playerName));
    }

    private Optional<OfflinePlayer> resolvePlayer(String playerName) {
        Optional<OfflinePlayer> known = knownPlayer(playerName);
        if (known.isPresent()) {
            return known;
        }
        if (Bukkit.getServer() == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(Bukkit.getOfflinePlayer(playerName));
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
        return service.findByName(playerName)
                .map(account -> account.getBalance().compareTo(requestedAmount) >= 0)
                .orElse(false);
    }
    @Override public boolean has(OfflinePlayer player, double amount) { return service.has(player.getUniqueId(), BigDecimal.valueOf(amount)); }
    @Override public boolean has(String playerName, String worldName, double amount) { return has(playerName, amount); }
    @Override public boolean has(OfflinePlayer player, String worldName, double amount) { return has(player, amount); }

    // ── Balance mutations ─────────────────────────────────────────────────────

    @Override public EconomyResponse withdrawPlayer(String playerName, double amount) {
        return accountIdOf(playerName)
                .map(accountId -> toV1(service.withdraw(accountId, BigDecimal.valueOf(amount))))
                .orElseGet(SimpleEcoLegacyEconomyProvider::accountNotFound);
    }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) { return toV1(service.withdraw(player.getUniqueId(), BigDecimal.valueOf(amount))); }
    @Override public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) { return withdrawPlayer(playerName, amount); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) { return withdrawPlayer(player, amount); }

    @Override public EconomyResponse depositPlayer(String playerName, double amount) {
        return accountIdOf(playerName)
                .map(accountId -> toV1(service.deposit(accountId, BigDecimal.valueOf(amount))))
                .orElseGet(SimpleEcoLegacyEconomyProvider::accountNotFound);
    }
    @Override public EconomyResponse depositPlayer(OfflinePlayer player, double amount) { return toV1(service.deposit(player.getUniqueId(), BigDecimal.valueOf(amount))); }
    @Override public EconomyResponse depositPlayer(String playerName, String worldName, double amount) { return depositPlayer(playerName, amount); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) { return depositPlayer(player, amount); }

    // ── Account creation ──────────────────────────────────────────────────────

    @Override
    public boolean createPlayerAccount(String playerName) {
        Optional<OfflinePlayer> player = resolvePlayer(playerName);
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

    @Override public boolean createPlayerAccount(String playerName, String worldName) { return createPlayerAccount(playerName); }
    @Override public boolean createPlayerAccount(OfflinePlayer player, String worldName) { return createPlayerAccount(player); }

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

    // ── Conversion helper ─────────────────────────────────────────────────────

    private static EconomyResponse toV1(net.milkbowl.vault2.economy.EconomyResponse r) {
        EconomyResponse.ResponseType type = r.transactionSuccess()
                ? EconomyResponse.ResponseType.SUCCESS
                : EconomyResponse.ResponseType.FAILURE;
        return new EconomyResponse(r.amount.doubleValue(), r.balance.doubleValue(), type, r.errorMessage);
    }
}
