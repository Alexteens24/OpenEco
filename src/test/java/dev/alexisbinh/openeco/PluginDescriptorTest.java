package dev.alexisbinh.openeco;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginDescriptorTest {

    @Test
    void pluginDescriptorPointsAtRealBootstrapClassAndProvidesCompatibilityAlias() throws Exception {
        YamlConfiguration descriptor = loadPluginDescriptor();

        assertEquals("dev.alexisbinh.openeco.OpenEcoPlugin", descriptor.getString("main"));
        assertEquals("openeco", descriptor.getString("name"));
        assertTrue(descriptor.getStringList("provides").contains("OpenEco"));
    }

    private static YamlConfiguration loadPluginDescriptor() throws Exception {
        try (InputStream stream = PluginDescriptorTest.class.getClassLoader().getResourceAsStream("plugin.yml")) {
            assertNotNull(stream, "plugin.yml must be present on the test classpath");
            YamlConfiguration descriptor = new YamlConfiguration();
            descriptor.loadFromString(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            return descriptor;
        }
    }
}