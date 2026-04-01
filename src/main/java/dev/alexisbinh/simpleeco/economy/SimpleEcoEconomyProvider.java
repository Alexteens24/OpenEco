package dev.alexisbinh.simpleeco.economy;

import dev.alexisbinh.simpleeco.service.AccountService;
import net.milkbowl.vault2.economy.Economy;
import net.milkbowl.vault2.economy.EconomyResponse;

import java.math.BigDecimal;
import java.util.*;

public class SimpleEcoEconomyProvider implements Economy {

    private final AccountService service;

    public SimpleEcoEconomyProvider(AccountService service) {
        this.service = service;
    }

    // ── Basic info ────────────────────────────────────────────────────────────

    @Override public boolean isEnabled() { return true; }
    @Override public String getName() { return "SimpleEco"; }
    @Override public boolean hasSharedAccountSupport() { return false; }
    @Override public boolean hasMultiCurrencySupport() { return false; }

    @Override
    public int fractionalDigits(String pluginName) {
        return service.getFractionalDigits();
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
        return service.format(amount);
    }

    @Override
    public String format(String pluginName, BigDecimal amount, String currency) {
        return service.format(amount);
    }

    // ── Currency ──────────────────────────────────────────────────────────────

    @Override
    public boolean hasCurrency(String currency) {
        return service.getCurrencyId().equalsIgnoreCase(currency)
            || service.getCurrencySingular().equalsIgnoreCase(currency)
            || service.getCurrencyPlural().equalsIgnoreCase(currency);
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
        return Collections.singletonList(service.getCurrencyId());
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
        return service.hasAccount(accountID) && hasCurrency(currency);
    }

    @Override
    public boolean accountSupportsCurrency(String pluginName, UUID accountID, String currency, String world) {
        return service.hasAccount(accountID) && hasCurrency(currency);
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
        return service.getBalance(accountID);
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
        return service.has(accountID, amount);
    }

    // ── Transactions ──────────────────────────────────────────────────────────

    @Override
    public EconomyResponse withdraw(String pluginName, UUID accountID, BigDecimal amount) {
        return service.withdraw(accountID, amount);
    }

    @Override
    public EconomyResponse withdraw(String pluginName, UUID accountID, String worldName, BigDecimal amount) {
        return service.withdraw(accountID, amount);
    }

    @Override
    public EconomyResponse withdraw(String pluginName, UUID accountID, String worldName, String currency, BigDecimal amount) {
        return service.withdraw(accountID, amount);
    }

    @Override
    public EconomyResponse deposit(String pluginName, UUID accountID, BigDecimal amount) {
        return service.deposit(accountID, amount);
    }

    @Override
    public EconomyResponse deposit(String pluginName, UUID accountID, String worldName, BigDecimal amount) {
        return service.deposit(accountID, amount);
    }

    @Override
    public EconomyResponse deposit(String pluginName, UUID accountID, String worldName, String currency, BigDecimal amount) {
        return service.deposit(accountID, amount);
    }

    @Override
    public EconomyResponse set(String pluginName, UUID accountID, BigDecimal amount) {
        return service.set(accountID, amount);
    }

    @Override
    public EconomyResponse set(String pluginName, UUID accountID, String worldName, BigDecimal amount) {
        return service.set(accountID, amount);
    }

    @Override
    public EconomyResponse set(String pluginName, UUID accountID, String worldName, String currency, BigDecimal amount) {
        return service.set(accountID, amount);
    }

    // ── canWithdraw / canDeposit ──────────────────────────────────────────────

    @Override
    public EconomyResponse canWithdraw(String pluginName, UUID accountID, BigDecimal amount) {
        return service.canWithdraw(accountID, amount);
    }

    @Override
    public EconomyResponse canWithdraw(String pluginName, UUID accountID, String worldName, BigDecimal amount) {
        return canWithdraw(pluginName, accountID, amount);
    }

    @Override
    public EconomyResponse canWithdraw(String pluginName, UUID accountID, String worldName, String currency, BigDecimal amount) {
        return canWithdraw(pluginName, accountID, amount);
    }

    @Override
    public EconomyResponse canDeposit(String pluginName, UUID accountID, BigDecimal amount) {
        return service.canDeposit(accountID, amount);
    }

    @Override
    public EconomyResponse canDeposit(String pluginName, UUID accountID, String worldName, BigDecimal amount) {
        return canDeposit(pluginName, accountID, amount);
    }

    @Override
    public EconomyResponse canDeposit(String pluginName, UUID accountID, String worldName, String currency, BigDecimal amount) {
        return canDeposit(pluginName, accountID, amount);
    }

    // ── Shared accounts (not supported) ──────────────────────────────────────

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
