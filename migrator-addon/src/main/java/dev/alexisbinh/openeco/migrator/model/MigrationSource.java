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

package dev.alexisbinh.openeco.migrator.model;

import java.util.Locale;
import java.util.Optional;

/**
 * Supported import sources. File/database readers work offline from plugin data;
 * {@link #VAULT} requires the legacy economy plugin to still be the active Vault provider.
 */
public enum MigrationSource {
    ESSENTIALS("EssentialsX", "Essentials userdata YAML (plugins/Essentials/userdata/*.yml)"),
    CMI("CMI", "CMI SQLite users table (plugins/CMI/*.db)"),
    LITECO("LiteEco", "LiteEco SQLite database (plugins/LiteEco/database.db)"),
    XCONOMY("XConomy", "XConomy SQLite data (plugins/XConomy/playerdata/...)"),
    VAULT("Vault", "Active Vault economy provider (source plugin must still be hooked)"),
    BOSECONOMY("BOSEconomy7", "BOSEconomy SQLite (plugins/BOSEconomy/*.db)");

    private final String displayName;
    private final String description;

    MigrationSource(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    public static Optional<MigrationSource> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        for (MigrationSource source : values()) {
            if (source.name().equalsIgnoreCase(normalized)
                    || source.displayName.equalsIgnoreCase(raw.trim())) {
                return Optional.of(source);
            }
        }
        return switch (normalized) {
            case "ess", "essentialsx", "essx" -> Optional.of(ESSENTIALS);
            case "lite_eco", "lite" -> Optional.of(LITECO);
            case "xcon", "x_conomy" -> Optional.of(XCONOMY);
            case "bose", "boseconomy" -> Optional.of(BOSECONOMY);
            default -> Optional.empty();
        };
    }
}
