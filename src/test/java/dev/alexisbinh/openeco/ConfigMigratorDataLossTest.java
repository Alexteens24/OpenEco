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

package dev.alexisbinh.openeco;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The config migrator rewrites an operator's {@code config.yml} in place, so it has to be
 * lossless in both directions: no configuration key may disappear, and no comment may vanish.
 */
class ConfigMigratorDataLossTest {

    private static final String BUNDLED_HEADER = """
            # Copyright 2026 alexisbinh
            # Licensed under the Apache License, Version 2.0 (the "License")
            """;

    // ── R-13: a key named "currency" below the root belongs to another plugin ─────

    @Test
    void keepsNestedCurrencySectionBelongingToAnotherPlugin() {
        YamlConfiguration current = load("""
                currency:
                  id: openeco
                  decimal-digits: 2
                my-shop:
                  currency:
                    symbol: "$"
                    prefix: "Shop"
                  enabled: true
                """);
        YamlConfiguration rewritten = migrate(current);

        assertEquals("$", rewritten.getString("my-shop.currency.symbol"),
                "nested currency key must survive the migration");
        assertEquals("Shop", rewritten.getString("my-shop.currency.prefix"));
        assertTrue(rewritten.getBoolean("my-shop.enabled"));
    }

    @Test
    void keepsDeeplyNestedCurrencyKeys() {
        YamlConfiguration current = load("""
                a:
                  b:
                    currency: keep-me
                    c:
                      currency: also-keep-me
                """);
        YamlConfiguration rewritten = migrate(current);

        assertEquals("keep-me", rewritten.getString("a.b.currency"));
        assertEquals("also-keep-me", rewritten.getString("a.b.c.currency"));
    }

    @Test
    void rootCurrencySectionIsStillReplacedByCurrenciesDefinitions() {
        YamlConfiguration current = load("""
                currency:
                  id: credits
                  name-singular: Credit
                  name-plural: Credits
                  decimal-digits: 3
                  starting-balance: 25.0
                  max-balance: 1000.0
                """);
        YamlConfiguration rewritten = migrate(current);

        assertFalse(rewritten.contains("currency"), "legacy root currency must be removed");
        assertEquals("credits", rewritten.getString("currencies.default"));
        assertEquals("Credit", rewritten.getString("currencies.definitions.credits.name-singular"));
        assertEquals(3, rewritten.getInt("currencies.definitions.credits.decimal-digits"));
        assertEquals(1000.0, rewritten.getDouble("currencies.definitions.credits.max-balance"), 1e-9);
    }

    @Test
    void legacyCurrencyIsNotLostWhenCurrenciesSectionAlreadyExists() {
        YamlConfiguration current = load("""
                currency:
                  id: credits
                  starting-balance: 25.0
                  decimal-digits: 3
                currencies:
                  default: gems
                  definitions:
                    gems:
                      decimal-digits: 0
                """);
        YamlConfiguration rewritten = migrate(current);

        assertEquals("gems", rewritten.getString("currencies.default"), "existing default must win");
        assertEquals(3, rewritten.getInt("currencies.definitions.credits.decimal-digits"),
                "legacy definition must still be migrated");
        assertEquals(25.0, rewritten.getDouble("currencies.definitions.credits.starting-balance"), 1e-9);
        assertFalse(rewritten.contains("currency"));
    }

    // ── R-14: comments survive the rewrite ──────────────────────────────────────

    @Test
    void preservesOperatorCommentsOnUnchangedConfig() {
        YamlConfiguration current = load("""
                # my own header
                # second header line
                storage:
                  type: sqlite
                  # I need this comment
                  sqlite:
                    file: economy.db
                """);
        YamlConfiguration rewritten = migrate(current);

        // A comment block attaches to the key that follows it, here the sqlite section.
        List<String> comments = rewritten.getComments("storage.sqlite");
        assertTrue(comments.contains("I need this comment"), "operator comment was dropped: " + comments);
        assertTrue(rewritten.getComments("storage").contains("second header line"),
                "section comments were dropped");
        assertTrue(rewritten.getComments("storage.type").isEmpty(),
                "a key without a comment must not inherit one");
    }

    @Test
    void bundledCommentsAreReappliedToKeysTheOperatorNeverTouched() {
        YamlConfiguration current = load("""
                storage:
                  type: sqlite
                """);
        YamlConfiguration rewritten = migrate(current);

        List<String> comments = rewritten.getComments("storage.sqlite");
        assertTrue(comments.stream().anyMatch(line -> line.contains("sqlite | h2")),
                "bundled comment missing, got: " + comments);
    }

    @Test
    void commentsArePreservedEvenWhenTheSchemaGrew() {
        YamlConfiguration current = load("""
                # keep the header
                persistence:
                  autosave-interval-seconds: 45
                """);
        Set<String> before = ConfigMigrator.keyPaths(current);
        YamlConfiguration rewritten = migrate(current);

        assertFalse(ConfigMigrator.keyPaths(rewritten).equals(before),
                "new template keys should be added");
        assertTrue(rewritten.getComments("persistence").contains("keep the header"),
                "comments must survive a real upgrade, got: "
                        + rewritten.getComments("persistence"));
        assertTrue(rewritten.getComments("persistence.autosave-interval-seconds").stream()
                        .anyMatch(line -> line.contains("background saves")),
                "bundled comments for new keys must be reapplied, got: "
                        + rewritten.getComments("persistence.autosave-interval-seconds"));
    }

    // ── R-14b: the rewrite gate only fires on real schema changes ───────────────

@Test
    void freshInstallWithBundledConfigNeedsNoRewrite() throws Exception {
        YamlConfiguration bundled = loadBundled();
        Set<String> before = ConfigMigrator.keyPaths(bundled);
        ConfigMigrator.rewrite(bundled, loadBundled());

        assertEquals(before, ConfigMigrator.keyPaths(bundled),
                "a config identical to the bundled one must gain no keys, so nothing is written");
    }

    @Test
    void bundledCommentCountSurvivesARoundTrip() throws Exception {
        YamlConfiguration bundled = loadBundled();
        long before = countCommentLines(bundled.saveToString());

        YamlConfiguration rewritten = ConfigMigrator.rewrite(bundled, loadBundled());
        long after = countCommentLines(rewritten.saveToString());

        assertTrue(before > 0, "bundled config should carry comments");
        assertEquals(before, after,
                "a round trip through the migrator must not drop comment lines");
    }

    @Test
    void renameIsStillDetectedAsAStructuralChange() {
        YamlConfiguration legacy = load("""
                autosave-interval: 45
                """);
        Set<String> before = ConfigMigrator.keyPaths(legacy);
        YamlConfiguration rewritten = migrate(legacy);

        assertFalse(rewritten.contains("autosave-interval"));
        assertEquals(45, rewritten.getInt("persistence.autosave-interval-seconds"));
        assertFalse(ConfigMigrator.keyPaths(rewritten).equals(before),
                "a renamed setting plus added defaults must trigger a rewrite");
    }

    @Test
    void rewriteIsIdempotent() {
        YamlConfiguration current = load("""
                currency:
                  id: credits
                  decimal-digits: 3
                autosave-interval: 45
                my-shop:
                  currency:
                    symbol: "$"
                """);
        YamlConfiguration template = load(minimalTemplate());

        Set<String> before = ConfigMigrator.keyPaths(current);
        YamlConfiguration once = migrate(current, template);
        Set<String> afterFirst = ConfigMigrator.keyPaths(once);
        YamlConfiguration twice = migrate(once, template);

        assertEquals(afterFirst, ConfigMigrator.keyPaths(twice),
                "re-running the migrator must not add anything");
        assertFalse(afterFirst.equals(before));
        assertEquals("$", twice.getString("my-shop.currency.symbol"));
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private static YamlConfiguration load(String yaml) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(yaml);
        } catch (InvalidConfigurationException error) {
            throw new IllegalStateException("test fixture is not valid YAML", error);
        }
        return config;
    }

    private static YamlConfiguration migrate(YamlConfiguration current) {
        return migrate(current, null);
    }

    private static YamlConfiguration migrate(YamlConfiguration current, YamlConfiguration template) {
        return ConfigMigrator.rewrite(current, template != null ? template : load(minimalTemplate()));
    }

    private static YamlConfiguration loadBundled() throws Exception {
        try (InputStream stream = ConfigMigratorDataLossTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(stream, "bundled config.yml must be on the test classpath");
            return YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
    }

    private static long countCommentLines(String yaml) {
        return yaml.lines().filter(line -> line.stripLeading().startsWith("#")).count();
    }

    private static String minimalTemplate() {
        return BUNDLED_HEADER + """
                storage:
                  type: sqlite
                  # sqlite | h2 | mysql | mariadb | postgresql
                  sqlite:
                    file: economy.db
                persistence:
                  # Seconds between automatic background saves.
                  autosave-interval-seconds: 30
                  transaction-history-drain-timeout-seconds: 10
                currencies:
                  default: openeco
                  definitions:
                    openeco:
                      name-singular: Dollar
                      name-plural: Dollars
                      decimal-digits: 2
                      starting-balance: 0.0
                      max-balance: -1
                """;
    }
}