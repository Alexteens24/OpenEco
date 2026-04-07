package dev.alexisbinh.openeco.placeholder;

import dev.alexisbinh.openeco.model.AccountRecord;
import dev.alexisbinh.openeco.service.AccountService;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.List;

public class OpenEcoPlaceholderExpansion extends PlaceholderExpansion {

    private final AccountService service;
    private final String version;

    public OpenEcoPlaceholderExpansion(AccountService service, String version) {
        this.service = service;
        this.version = version;
    }

    @Override
    public @NotNull String getIdentifier() { return "openeco"; }

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
        if (params.startsWith("balance_formatted_")) {
            if (player == null) return "";
            String currencyId = params.substring("balance_formatted_".length());
            if (!service.hasCurrency(currencyId)) return "";
            return service.format(service.getBalance(player.getUniqueId(), currencyId), currencyId);
        }
        if (params.startsWith("balance_")) {
            if (player == null) return "";
            String currencyId = params.substring("balance_".length());
            if (!service.hasCurrency(currencyId)) return "";
            return service.getBalance(player.getUniqueId(), currencyId).toPlainString();
        }
        if (params.equals("rank")) {
            if (player == null) return "";
            int rank = service.getRankOf(player.getUniqueId());
            return rank == -1 ? "" : String.valueOf(rank);
        }
        if (params.startsWith("rank_")) {
            if (player == null) return "";
            String currencyId = params.substring("rank_".length());
            if (!service.hasCurrency(currencyId)) return "";
            int rank = service.getRankOf(player.getUniqueId(), currencyId);
            return rank == -1 ? "" : String.valueOf(rank);
        }
        if (params.equals("currency_singular")) {
            return service.getCurrencySingular();
        }
        if (params.startsWith("currency_singular_")) {
            String currencyId = params.substring("currency_singular_".length());
            return service.hasCurrency(currencyId) ? service.getCurrencySingular(currencyId) : "";
        }
        if (params.equals("currency_plural")) {
            return service.getCurrencyPlural();
        }
        if (params.startsWith("currency_plural_")) {
            String currencyId = params.substring("currency_plural_".length());
            return service.hasCurrency(currencyId) ? service.getCurrencyPlural(currencyId) : "";
        }
        if (params.equals("frozen")) {
            if (player == null) return "";
            return String.valueOf(service.isFrozen(player.getUniqueId()));
        }

        // ── Baltop placeholders: top_<n>_name / top_<n>_balance ─────────────
        // e.g. %openeco_top_1_name%, %openeco_top_3_balance%
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
            String descriptor = rest.substring(underscore + 1);
            ParsedTopField parsed = parseTopField(descriptor);
            if (parsed == null) return "";

            List<AccountRecord> top = service.getBalTopSnapshot(parsed.currencyId());
            if (rank > top.size()) {
                return switch (parsed.field()) {
                    case "name" -> "---";
                    case "balance" -> "0";
                    case "balance_formatted" -> service.format(BigDecimal.ZERO, parsed.currencyId());
                    default -> "";
                };
            }
            AccountRecord entry = top.get(rank - 1);
            return switch (parsed.field()) {
                case "name" -> entry.getLastKnownName();
                case "balance" -> entry.getBalance(parsed.currencyId()).toPlainString();
                case "balance_formatted" -> service.format(entry.getBalance(parsed.currencyId()), parsed.currencyId());
                default -> "";
            };
        }

        return null; // unknown placeholder
    }

    private @Nullable ParsedTopField parseTopField(String descriptor) {
        for (String field : List.of("balance_formatted", "balance", "name")) {
            if (descriptor.equals(field)) {
                return new ParsedTopField(field, service.getCurrencyId());
            }
            if (descriptor.startsWith(field + "_")) {
                String currencyId = descriptor.substring(field.length() + 1);
                if (service.hasCurrency(currencyId)) {
                    return new ParsedTopField(field, currencyId);
                }
            }
        }
        return null;
    }

    private record ParsedTopField(String field, String currencyId) {}
}
