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

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginDescriptorTest {

    private static final Pattern COMPILE_ONLY_COORDINATE = Pattern.compile(
            "compileOnly\\(\"([^:\"]+):([^:\"]+):([^\"]+)\"");

    @Test
    void pluginDescriptorPointsAtRealBootstrapClassAndProvidesCompatibilityAlias() throws Exception {
        YamlConfiguration descriptor = loadPluginDescriptor();

        assertEquals("dev.alexisbinh.openeco.OpenEcoPlugin", descriptor.getString("main"));
        assertEquals("openeco", descriptor.getString("name"));
        assertTrue(descriptor.getStringList("provides").contains("OpenEco"));
    }

    @Test
    void pluginYmlRuntimeLibrariesMatchBuildGradleVersions() throws Exception {
        Map<String, String> buildVersions = extractCompileOnlyVersions(Path.of("build.gradle.kts"));
        YamlConfiguration descriptor = loadPluginDescriptor();

        for (String coordinate : descriptor.getStringList("libraries")) {
            String[] parts = coordinate.split(":");
            assertEquals(3, parts.length, "library coordinate must be group:artifact:version: " + coordinate);
            String key = parts[0] + ":" + parts[1];
            assertTrue(buildVersions.containsKey(key),
                    () -> "plugin.yml declares " + key + " but build.gradle.kts has no compileOnly entry");
            assertEquals(buildVersions.get(key), parts[2],
                    () -> "plugin.yml version for " + key
                            + " must match build.gradle.kts (Dependabot does not update plugin.yml)");
        }
    }

    private static Map<String, String> extractCompileOnlyVersions(Path buildFile) throws Exception {
        Map<String, String> versions = new HashMap<>();
        String content = Files.readString(buildFile);
        Matcher matcher = COMPILE_ONLY_COORDINATE.matcher(content);
        while (matcher.find()) {
            versions.put(matcher.group(1) + ":" + matcher.group(2), matcher.group(3));
        }
        return versions;
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