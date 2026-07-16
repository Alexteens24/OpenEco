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

package dev.alexisbinh.openeco.placeholder;

import dev.alexisbinh.openeco.service.AccountService;
import dev.alexisbinh.openeco.service.LeaderboardEntry;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class OpenEcoPlaceholderExpansion extends PlaceholderExpansion {

    private final AccountService service;
    private final String version;
    private final PlaceholderSnapshotCache snapshots = new PlaceholderSnapshotCache(10_000);

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
        if (!service.isLazyAccountModeEnabled() || !isStorageBacked(params)) {
            return resolveRequest(player, params);
        }
        if (player == null && !params.startsWith("top_")) return "";
        String fallback = fallback(params);
        if (fallback == null) return resolveRequest(player, params);
        String owner = player == null ? "global" : player.getUniqueId().toString();
        long ttl = params.startsWith("rank") || params.startsWith("top_")
                ? TimeUnit.MILLISECONDS.toNanos(service.getLeaderboardRefreshIntervalMillis())
                : TimeUnit.SECONDS.toNanos(1);
        return snapshots.get(owner + ':' + params, ttl, fallback,
                () -> resolveRequest(player, params), service::supplyAsync);
    }

    private @Nullable String resolveRequest(OfflinePlayer player, String params) {
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

            LeaderboardEntry entry = service.getLeaderboardEntry(rank, parsed.currencyId());
            if (entry == null) {
                return switch (parsed.field()) {
                    case "name" -> "---";
                    case "balance" -> "0";
                    case "balance_formatted" -> service.format(BigDecimal.ZERO, parsed.currencyId());
                    default -> "";
                };
            }
            return switch (parsed.field()) {
                case "name" -> entry.name();
                case "balance" -> entry.balance().toPlainString();
                case "balance_formatted" -> service.format(entry.balance(), parsed.currencyId());
                default -> "";
            };
        }

        return null; // unknown placeholder
    }

    private boolean isStorageBacked(String params) {
        return params.equals("balance")
                || params.equals("balance_formatted")
                || params.startsWith("balance_")
                || params.equals("rank")
                || params.startsWith("rank_")
                || params.equals("frozen")
                || params.startsWith("top_");
    }

    private @Nullable String fallback(String params) {
        if (params.equals("balance")) return "0";
        if (params.equals("balance_formatted")) return service.format(BigDecimal.ZERO);
        if (params.startsWith("balance_formatted_")) {
            String currencyId = params.substring("balance_formatted_".length());
            return service.hasCurrency(currencyId) ? service.format(BigDecimal.ZERO, currencyId) : null;
        }
        if (params.startsWith("balance_")) {
            String currencyId = params.substring("balance_".length());
            return service.hasCurrency(currencyId) ? "0" : null;
        }
        if (params.equals("rank") || params.startsWith("rank_")) return "";
        if (params.equals("frozen")) return "false";
        if (params.startsWith("top_")) {
            String rest = params.substring(4);
            int underscore = rest.indexOf('_');
            if (underscore < 1) return null;
            ParsedTopField parsed = parseTopField(rest.substring(underscore + 1));
            if (parsed == null) return null;
            return switch (parsed.field()) {
                case "name" -> "---";
                case "balance" -> "0";
                case "balance_formatted" -> service.format(BigDecimal.ZERO, parsed.currencyId());
                default -> null;
            };
        }
        return null;
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
