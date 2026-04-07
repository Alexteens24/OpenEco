package dev.alexisbinh.openeco;

import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MessagesTest {

    @Test
    void getOrDefaultUsesProvidedFallbackWhenMessageKeyIsMissing() {
        Messages messages = new Messages(new YamlConfiguration());

        Component component = messages.getOrDefault("history-custom", "fallback text");

        assertEquals(Component.text("fallback text"), component);
    }
}