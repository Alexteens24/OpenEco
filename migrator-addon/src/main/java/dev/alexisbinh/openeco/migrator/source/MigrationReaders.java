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

import dev.alexisbinh.openeco.migrator.model.MigrationSource;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class MigrationReaders {

    private static final Map<MigrationSource, EconomySourceReader> READERS = new EnumMap<>(MigrationSource.class);

    static {
        register(new EssentialsUserdataReader());
        register(new CmiDatabaseReader());
        register(new LiteEcoDatabaseReader());
        register(new XConomyDatabaseReader());
        register(new VaultEconomyReader());
        register(new BoseEconomyDatabaseReader());
    }

    private MigrationReaders() {
    }

    public static Optional<EconomySourceReader> get(MigrationSource source) {
        return Optional.ofNullable(READERS.get(source));
    }

    public static List<EconomySourceReader> all() {
        return List.copyOf(READERS.values());
    }

    private static void register(EconomySourceReader reader) {
        READERS.put(reader.source(), reader);
    }
}
