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

package dev.alexisbinh.openeco.migrator.source;

import dev.alexisbinh.openeco.migrator.model.ForeignAccount;
import dev.alexisbinh.openeco.migrator.model.MigrationSource;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

public final class EssentialsUserdataReader implements EconomySourceReader {

    @Override
    public MigrationSource source() {
        return MigrationSource.ESSENTIALS;
    }

    @Override
    public String describeLocation(MigrationContext context) {
        return resolveUserdataFolder(context).toString();
    }

    @Override
    public boolean isAvailable(MigrationContext context) {
        Path folder = resolveUserdataFolder(context);
        return Files.isDirectory(folder);
    }

    @Override
    public List<ForeignAccount> read(MigrationContext context) throws IOException {
        Path folder = resolveUserdataFolder(context);
        if (!Files.isDirectory(folder)) {
            throw new IOException("Essentials userdata folder not found: " + folder);
        }

        List<ForeignAccount> accounts = new ArrayList<>();
        try (Stream<Path> files = Files.list(folder)) {
            files.filter(path -> path.getFileName().toString().endsWith(".yml"))
                    .forEach(path -> readFile(path, accounts));
        }
        return List.copyOf(accounts);
    }

    private static Path resolveUserdataFolder(MigrationContext context) {
        Path defaultPath = context.pluginsFolder().resolve("Essentials").resolve("userdata");
        return context.resolveOverride("essentials-userdata", defaultPath);
    }

    private static void readFile(Path file, List<ForeignAccount> accounts) {
        String fileName = file.getFileName().toString();
        String uuidPart = fileName.substring(0, fileName.length() - 4);
        UUID id = SqliteSupport.parseUuid(uuidPart);
        if (id == null) {
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        if (!yaml.contains("money")) {
            return;
        }

        BigDecimal balance = readMoney(yaml);

        String name = yaml.getString("last-account-name");
        if (name == null || name.isBlank()) {
            name = yaml.getString("lastAccountName");
        }
        if (name == null || name.isBlank()) {
            name = uuidPart.substring(0, Math.min(8, uuidPart.length()));
        }
        name = sanitizeName(name);
        accounts.add(new ForeignAccount(id, name, balance));
    }

    private static BigDecimal readMoney(YamlConfiguration yaml) {
        Object raw = yaml.get("money");
        if (raw instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (raw instanceof String text && !text.isBlank()) {
            return new BigDecimal(text.trim());
        }
        return BigDecimal.valueOf(yaml.getDouble("money"));
    }

    private static String sanitizeName(String name) {
        String trimmed = name.trim();
        if (trimmed.length() > 16) {
            return trimmed.substring(0, 16);
        }
        return trimmed;
    }
}
