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

import org.bukkit.command.CommandMap;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Lets the {@code commands} section of {@code config.yml} disable a command or change its name,
 * aliases and namespace prefix.
 *
 * <p>{@code plugin.yml} stays the source of the defaults: Bukkit registers those commands before
 * {@code onEnable}, and this class only re-registers the ones the operator changed. With an absent
 * or untouched section nothing is re-registered, so a default install behaves exactly as before.
 *
 * <p>Applied once at startup. Re-registering commands while players are online would also need a
 * command-tree resend, so changes here require a restart (like {@code cross-server.enabled}).
 */
public final class CommandConfigurator {

    /** Command ids, i.e. the keys under {@code commands:} in plugin.yml and config.yml. */
    private static final List<String> COMMAND_IDS =
            List.of("balance", "baltop", "pay", "eco", "history", "openecomigrate");

    private static final Pattern VALID_LABEL = Pattern.compile("[a-z0-9_-]+");

    private CommandConfigurator() {
    }

    /** Resolved settings of one command. */
    record Spec(boolean enabled, String name, List<String> aliases) {
    }

    /** Call once, after the executors have been set on the plugin.yml commands. */
    public static void apply(JavaPlugin plugin) {
        Map<String, PluginCommand> commands = new LinkedHashMap<>();
        for (String id : COMMAND_IDS) {
            PluginCommand command = plugin.getCommand(id);
            if (command != null) {
                commands.put(id, command);
            }
        }
        apply(plugin.getConfig(), plugin.getServer().getCommandMap(), commands,
                plugin.getName().toLowerCase(Locale.ROOT), plugin.getLogger());
    }

    static void apply(ConfigurationSection config, CommandMap commandMap, Map<String, PluginCommand> commands,
                      String defaultPrefix, Logger log) {
        ConfigurationSection section = config.getConfigurationSection("commands");
        if (section == null) {
            return;
        }

        String prefix = resolvePrefix(section, defaultPrefix, log);
        Map<String, Spec> changed = new LinkedHashMap<>();
        for (Map.Entry<String, PluginCommand> entry : commands.entrySet()) {
            PluginCommand command = entry.getValue();
            Spec spec = resolve(section.getConfigurationSection(entry.getKey()), entry.getKey(),
                    command.getAliases(), log);
            boolean untouched = spec.enabled() && spec.name().equals(entry.getKey())
                    && spec.aliases().equals(command.getAliases()) && prefix.equals(defaultPrefix);
            if (!untouched) {
                changed.put(entry.getKey(), spec);
            }
        }

        // Unregister everything first, then register. Doing it per command would make two
        // commands that swap names (pay <-> eco) collide with each other's old label.
        for (String id : changed.keySet()) {
            PluginCommand command = commands.get(id);
            // Command#unregister only detaches the command; it leaves the map entries behind.
            commandMap.getKnownCommands().values().removeIf(registered -> registered == command);
            command.unregister(commandMap);
        }
        for (Map.Entry<String, Spec> entry : changed.entrySet()) {
            Spec spec = entry.getValue();
            if (!spec.enabled()) {
                log.info("Command '" + entry.getKey() + "' is disabled (commands." + entry.getKey() + ".enabled).");
                continue;
            }
            PluginCommand command = commands.get(entry.getKey());
            command.setName(spec.name());
            command.setAliases(spec.aliases());
            commandMap.register(prefix, command);
            if (commandMap.getCommand(spec.name()) != command) {
                log.warning("Command /" + spec.name() + " (commands." + entry.getKey() + ".name) is already taken by"
                        + " another plugin or command. Use /" + prefix + ":" + spec.name() + " or pick another name.");
            }
        }
    }

    static Spec resolve(ConfigurationSection section, String id, List<String> defaultAliases, Logger log) {
        if (section == null) {
            return new Spec(true, id, defaultAliases);
        }

        String path = "commands." + id;
        String name = normalise(section.getString("name", id), path + ".name", log);
        if (name == null) {
            name = id;
        }

        List<String> requested = section.contains("aliases") ? section.getStringList("aliases") : defaultAliases;
        List<String> aliases = new ArrayList<>();
        for (String raw : requested) {
            String alias = normalise(raw, path + ".aliases", log);
            if (alias != null && !alias.equals(name) && !aliases.contains(alias)) {
                aliases.add(alias);
            }
        }
        return new Spec(section.getBoolean("enabled", true), name, List.copyOf(aliases));
    }

    private static String resolvePrefix(ConfigurationSection section, String defaultPrefix, Logger log) {
        String prefix = normalise(section.getString("fallback-prefix", defaultPrefix), "commands.fallback-prefix", log);
        return prefix != null ? prefix : defaultPrefix;
    }

    /** Lower-cases, trims and drops a leading slash; returns null (with a warning) if it is unusable. */
    private static String normalise(String raw, String path, Logger log) {
        String label = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (label.startsWith("/")) {
            label = label.substring(1);
        }
        if (!VALID_LABEL.matcher(label).matches()) {
            log.warning("Ignoring invalid value '" + raw + "' in " + path
                    + ": use only letters, digits, '_' and '-'.");
            return null;
        }
        return label;
    }
}
