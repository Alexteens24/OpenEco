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

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;

public final class Messages {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private volatile FileConfiguration config;

    public Messages(FileConfiguration config) {
        this.config = config;
    }

    public void reload(FileConfiguration config) {
        this.config = config;
    }

    public void send(CommandSender sender, String key, TagResolver... resolvers) {
        sender.sendMessage(get(key, resolvers));
    }

    public Component get(String key, TagResolver... resolvers) {
        return getOrDefault(key, "<red>(missing message: " + key + ")", resolvers);
    }

    public Component getOrDefault(String key, String defaultRaw, TagResolver... resolvers) {
        String raw = config.getString("messages." + key);
        if (raw == null) {
            raw = defaultRaw;
        }
        return MM.deserialize(raw, resolvers);
    }
}
