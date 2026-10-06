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

package dev.alexisbinh.openeco.command;

import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Runs against a real {@link SimpleCommandMap}, because the interesting behaviour is what is
 * left behind in it: {@code Command#unregister} alone does not remove a command's entries.
 */
class CommandConfiguratorTest {

    private static final Map<String, List<String>> DEFAULTS = Map.of(
            "balance", List.of("bal", "money"),
            "baltop", List.of("balancetop", "moneytop"),
            "pay", List.of(),
            "eco", List.of("economy"),
            "history", List.of("txhistory", "ecohistory"),
            "openecomigrate", List.of());

    private SimpleCommandMap map;
    private Map<String, PluginCommand> commands;
    private final List<String> warnings = new ArrayList<>();
    private Logger log;

    @BeforeEach
    void setUp() throws Exception {
        map = new SimpleCommandMap(mock(Server.class), new java.util.HashMap<>());
        Plugin plugin = mock(Plugin.class);
        Constructor<PluginCommand> constructor = PluginCommand.class.getDeclaredConstructor(String.class, Plugin.class);
        constructor.setAccessible(true);

        // What Bukkit does with the commands declared in plugin.yml, before onEnable runs.
        commands = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : DEFAULTS.entrySet()) {
            PluginCommand command = constructor.newInstance(entry.getKey(), plugin);
            command.setAliases(entry.getValue());
            map.register("openeco", command);
            commands.put(entry.getKey(), command);
        }

        log = Logger.getAnonymousLogger();
        log.setUseParentHandlers(false);
        log.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warnings.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
    }

    private void apply(String yaml) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        CommandConfigurator.apply(config, map, commands, "openeco", log);
    }

    @Test
    void missingSectionKeepsEveryDefault() throws Exception {
        apply("pay:\n  cooldown: 0\n");

        assertSame(commands.get("pay"), map.getCommand("pay"));
        assertSame(commands.get("balance"), map.getCommand("bal"));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void shippedDefaultsChangeNothing() throws Exception {
        apply("""
                commands:
                  fallback-prefix: openeco
                  balance: {enabled: true, name: balance, aliases: [bal, money]}
                  pay: {enabled: true, name: pay, aliases: []}
                  eco: {enabled: true, name: eco, aliases: [economy]}
                """);

        for (Map.Entry<String, PluginCommand> entry : commands.entrySet()) {
            assertSame(entry.getValue(), map.getCommand(entry.getKey()));
            assertSame(entry.getValue(), map.getCommand("openeco:" + entry.getKey()));
        }
        assertSame(commands.get("eco"), map.getCommand("economy"));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void renamedCommandReplacesTheOldNameAndAliases() throws Exception {
        apply("""
                commands:
                  balance:
                    name: money2
                    aliases: [m2]
                """);

        PluginCommand balance = commands.get("balance");
        assertSame(balance, map.getCommand("money2"));
        assertSame(balance, map.getCommand("m2"));
        assertSame(balance, map.getCommand("openeco:money2"));
        // The leftovers are the actual risk: the old label and its aliases must be gone.
        assertNull(map.getCommand("balance"));
        assertNull(map.getCommand("bal"));
        assertNull(map.getCommand("money"));
        assertNull(map.getCommand("openeco:balance"));
        // Untouched commands are still there.
        assertSame(commands.get("pay"), map.getCommand("pay"));
    }

    @Test
    void missingAliasesKeepThePluginYmlDefaults() throws Exception {
        apply("commands:\n  balance:\n    name: wallet\n");

        assertSame(commands.get("balance"), map.getCommand("wallet"));
        assertSame(commands.get("balance"), map.getCommand("bal"));
    }

    @Test
    void disabledCommandIsRemovedCompletely() throws Exception {
        apply("commands:\n  eco:\n    enabled: false\n");

        assertNull(map.getCommand("eco"));
        assertNull(map.getCommand("economy"));
        assertNull(map.getCommand("openeco:eco"));
        assertSame(commands.get("pay"), map.getCommand("pay"));
    }

    @Test
    void commandsCanSwapNames() throws Exception {
        apply("""
                commands:
                  pay:
                    name: eco
                    aliases: []
                  eco:
                    name: pay
                    aliases: []
                """);

        assertSame(commands.get("pay"), map.getCommand("eco"));
        assertSame(commands.get("eco"), map.getCommand("pay"));
        assertTrue(warnings.isEmpty(), () -> "unexpected warnings: " + warnings);
    }

    @Test
    void customFallbackPrefixReplacesTheDefaultOne() throws Exception {
        apply("commands:\n  fallback-prefix: MyEco\n");

        assertSame(commands.get("pay"), map.getCommand("myeco:pay"));
        assertNull(map.getCommand("openeco:pay"));
        assertSame(commands.get("pay"), map.getCommand("pay"));
    }

    @Test
    void invalidNamesFallBackToTheDefaultAndWarn() throws Exception {
        apply("""
                commands:
                  pay:
                    name: "bad name!"
                  balance:
                    aliases: [ok, "no:colon", ""]
                """);

        assertSame(commands.get("pay"), map.getCommand("pay"));
        assertSame(commands.get("balance"), map.getCommand("ok"));
        assertNull(map.getCommand("no:colon"));
        assertEquals(3, warnings.size(), () -> "warnings: " + warnings);
    }

    @Test
    void leadingSlashAndUpperCaseAreAccepted() throws Exception {
        apply("commands:\n  pay:\n    name: \"/Send\"\n");

        assertSame(commands.get("pay"), map.getCommand("send"));
    }

    @Test
    void nameTakenByAnotherPluginWarnsButKeepsTheNamespacedForm() throws Exception {
        Command other = new Command("send") {
            @Override
            public boolean execute(org.bukkit.command.CommandSender sender, String label, String[] args) {
                return true;
            }
        };
        map.register("other", other);

        apply("commands:\n  pay:\n    name: send\n");

        assertSame(other, map.getCommand("send"));
        assertNotNull(map.getCommand("openeco:send"));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("already taken")), () -> "warnings: " + warnings);
    }
}
