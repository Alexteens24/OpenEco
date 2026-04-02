package dev.alexisbinh.simpleeco.placeholder;

import dev.alexisbinh.simpleeco.model.AccountRecord;
import dev.alexisbinh.simpleeco.service.AccountService;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class SimpleEcoPlaceholderExpansion extends PlaceholderExpansion {

    private final AccountService service;
    private final String version;

    public SimpleEcoPlaceholderExpansion(AccountService service, String version) {
        this.service = service;
        this.version = version;
    }

    @Override
    public @NotNull String getIdentifier() { return "simpleeco"; }

    @Override
    public @NotNull String getAuthor() { return "alexisbinh"; }

    @Override
    public @NotNull String getVersion() { return version; }

    @Override
    public boolean persist() { return true; }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        // ── Player-specific placeholders ─────────────────────────────────────
        if (params.equals("balance")) {
            if (player == null) return "";
            return service.getBalance(player.getUniqueId()).toPlainString();
        }
        if (params.equals("balance_formatted")) {
            if (player == null) return "";
            return service.format(service.getBalance(player.getUniqueId()));
        }
        if (params.equals("currency_singular")) {
            return service.getCurrencySingular();
        }
        if (params.equals("currency_plural")) {
            return service.getCurrencyPlural();
        }

        // ── Baltop placeholders: top_<n>_name / top_<n>_balance ─────────────
        // e.g. %simpleeco_top_1_name%, %simpleeco_top_3_balance%
        if (params.startsWith("top_")) {
            String rest = params.substring(4); // "1_name" or "3_balance"
            int underscore = rest.indexOf('_');
            if (underscore < 1) return "";
            int rank;
            try {
                rank = Integer.parseInt(rest.substring(0, underscore));
            } catch (NumberFormatException e) {
                return "";
            }
            if (rank < 1) return "";
            String field = rest.substring(underscore + 1);

            List<AccountRecord> top = service.getBalTopSnapshot();
            if (rank > top.size()) return field.equals("name") ? "---" : "0";
            AccountRecord entry = top.get(rank - 1);
            return switch (field) {
                case "name" -> entry.getLastKnownName();
                case "balance" -> entry.getBalance().toPlainString();
                case "balance_formatted" -> service.format(entry.getBalance());
                default -> "";
            };
        }

        return null; // unknown placeholder
    }
}
