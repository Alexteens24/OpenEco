package dev.alexisbinh.simpleeco;

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
        String raw = config.getString("messages." + key, "<red>(missing message: " + key + ")");
        return MM.deserialize(raw, resolvers);
    }
}
