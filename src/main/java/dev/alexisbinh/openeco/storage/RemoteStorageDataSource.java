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

package dev.alexisbinh.openeco.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Locale;
import java.util.Set;

public final class RemoteStorageDataSource {

    private RemoteStorageDataSource() {
    }

    public static HikariDataSource create(DatabaseDialect dialect, FileConfiguration config) {
        if (dialect.isLocal()) {
            throw new IllegalArgumentException("Remote datasource requested for local dialect: " + dialect);
        }

        String section = dialect.name().toLowerCase(Locale.ROOT);
        String root = "storage." + section + ".";
        String username = config.getString(root + "username", "root");
        String password = config.getString(root + "password", "");
        int poolSize = positiveInt(config.getInt(root + "pool-size", 10), root + "pool-size", Integer.MAX_VALUE);
        int timeoutSeconds = positiveInt(
                config.getInt(root + "connection-timeout-seconds", 10),
                root + "connection-timeout-seconds", 300);
        String customUrl = config.getString(root + "jdbc-url", "");
        String jdbcUrl;
        if (customUrl != null && !customUrl.isBlank()) {
            jdbcUrl = validateCustomJdbcUrl(dialect, customUrl.trim(), root + "jdbc-url");
        } else {
            String host = requireText(config.getString(root + "host", "localhost"), root + "host");
            int defaultPort = (dialect == DatabaseDialect.POSTGRESQL) ? 5432 : 3306;
            int port = positiveInt(config.getInt(root + "port", defaultPort), root + "port", 65_535);
            String database = requireText(config.getString(root + "database", "openeco"), root + "database");
            String sslMode = validateSslMode(
                    dialect, config.getString(root + "ssl-mode", defaultSslMode(dialect)), root + "ssl-mode");
            jdbcUrl = jdbcUrl(dialect, host, port, database, sslMode);
        }

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(jdbcUrl);
        cfg.setUsername(username);
        cfg.setPassword(password);
        cfg.setMaximumPoolSize(poolSize);
        cfg.setMinimumIdle(Math.min(2, poolSize));
        cfg.setConnectionTimeout(timeoutSeconds * 1000L);
        cfg.setPoolName("OpenEco-" + dialect.name());
        cfg.setDriverClassName(driverClassName(dialect));
        return new HikariDataSource(cfg);
    }

    static String jdbcUrl(DatabaseDialect dialect, String host, int port, String database, String sslMode) {
        return switch (dialect) {
            case MYSQL -> "jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?useUnicode=true&characterEncoding=UTF-8&sslMode=" + mysqlSslMode(sslMode)
                    + "&serverTimezone=UTC"
                    + "&allowPublicKeyRetrieval=true&useCursorFetch=true";
            case MARIADB -> "jdbc:mariadb://" + host + ":" + port + "/" + database
                    + "?useUnicode=true&characterEncoding=UTF-8&sslMode=" + mariadbSslMode(sslMode)
                    + "&allowPublicKeyRetrieval=true";
            case POSTGRESQL -> "jdbc:postgresql://" + host + ":" + port + "/" + database
                    + "?sslmode=" + postgresqlSslMode(sslMode);
            default -> throw new IllegalStateException("Unexpected remote dialect: " + dialect);
        };
    }

    private static String defaultSslMode(DatabaseDialect dialect) {
        return switch (dialect) {
            case MYSQL -> "preferred";
            case MARIADB -> "disabled";
            case POSTGRESQL -> "prefer";
            default -> throw new IllegalStateException("Unexpected remote dialect: " + dialect);
        };
    }

    private static String validateSslMode(DatabaseDialect dialect, String value, String path) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        Set<String> allowed = switch (dialect) {
            case MYSQL -> Set.of("disabled", "preferred", "required", "verify-ca", "verify-full");
            case MARIADB -> Set.of("disabled", "trust", "verify-ca", "verify-full");
            case POSTGRESQL -> Set.of("disable", "disabled", "allow", "prefer", "preferred", "require", "required", "verify-ca", "verify-full");
            default -> throw new IllegalStateException("Unexpected remote dialect: " + dialect);
        };
        if (!allowed.contains(normalized)) {
            throw new IllegalArgumentException(path + " has unsupported value '" + value
                    + "' for " + dialect.name().toLowerCase(Locale.ROOT));
        }
        return normalized;
    }

    private static String mysqlSslMode(String mode) {
        return switch (mode) {
            case "disabled" -> "DISABLED";
            case "preferred" -> "PREFERRED";
            case "required" -> "REQUIRED";
            case "verify-ca" -> "VERIFY_CA";
            case "verify-full" -> "VERIFY_IDENTITY";
            default -> throw new IllegalArgumentException("Unsupported MySQL SSL mode: " + mode);
        };
    }

    private static String mariadbSslMode(String mode) {
        return "disabled".equals(mode) ? "disable" : mode;
    }

    private static String postgresqlSslMode(String mode) {
        return switch (mode) {
            case "disabled" -> "disable";
            case "preferred" -> "prefer";
            case "required" -> "require";
            default -> mode;
        };
    }

    private static String validateCustomJdbcUrl(DatabaseDialect dialect, String url, String path) {
        String prefix = switch (dialect) {
            case MYSQL -> "jdbc:mysql:";
            case MARIADB -> "jdbc:mariadb:";
            case POSTGRESQL -> "jdbc:postgresql:";
            default -> throw new IllegalStateException("Unexpected remote dialect: " + dialect);
        };
        if (!url.startsWith(prefix)) {
            throw new IllegalArgumentException(path + " must start with " + prefix);
        }
        return url;
    }

    private static int positiveInt(int value, String path, int max) {
        if (value <= 0 || value > max) {
            throw new IllegalArgumentException(path + " must be between 1 and " + max);
        }
        return value;
    }

    private static String requireText(String value, String path) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(path + " must not be blank");
        }
        return value.trim();
    }

    static String driverClassName(DatabaseDialect dialect) {
        return switch (dialect) {
            case MYSQL -> "com.mysql.cj.jdbc.Driver";
            case MARIADB -> "org.mariadb.jdbc.Driver";
            case POSTGRESQL -> "org.postgresql.Driver";
            default -> throw new IllegalStateException("Unexpected remote dialect: " + dialect);
        };
    }
}
