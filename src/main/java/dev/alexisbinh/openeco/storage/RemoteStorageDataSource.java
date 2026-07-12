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

public final class RemoteStorageDataSource {

    private RemoteStorageDataSource() {
    }

    public static HikariDataSource create(DatabaseDialect dialect, FileConfiguration config) {
        if (dialect.isLocal()) {
            throw new IllegalArgumentException("Remote datasource requested for local dialect: " + dialect);
        }

        String section = dialect.name().toLowerCase(Locale.ROOT);
        String host = config.getString("storage." + section + ".host", "localhost");
        int defaultPort = (dialect == DatabaseDialect.POSTGRESQL) ? 5432 : 3306;
        int port = config.getInt("storage." + section + ".port", defaultPort);
        String database = config.getString("storage." + section + ".database", "openeco");
        String username = config.getString("storage." + section + ".username", "root");
        String password = config.getString("storage." + section + ".password", "");
        int poolSize = config.getInt("storage." + section + ".pool-size", 10);

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(jdbcUrl(dialect, host, port, database));
        cfg.setUsername(username);
        cfg.setPassword(password);
        cfg.setMaximumPoolSize(poolSize);
        cfg.setMinimumIdle(Math.min(2, poolSize));
        cfg.setConnectionTimeout(10_000);
        cfg.setPoolName("OpenEco-" + dialect.name());
        cfg.setDriverClassName(driverClassName(dialect));
        return new HikariDataSource(cfg);
    }

    static String jdbcUrl(DatabaseDialect dialect, String host, int port, String database) {
        return switch (dialect) {
            case MYSQL -> "jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?useUnicode=true&characterEncoding=UTF-8&useSSL=false&serverTimezone=UTC"
                    + "&allowPublicKeyRetrieval=true&useCursorFetch=true";
            case MARIADB -> "jdbc:mariadb://" + host + ":" + port + "/" + database
                    + "?useUnicode=true&characterEncoding=UTF-8&allowPublicKeyRetrieval=true";
            case POSTGRESQL -> "jdbc:postgresql://" + host + ":" + port + "/" + database;
            default -> throw new IllegalStateException("Unexpected remote dialect: " + dialect);
        };
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
