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

package dev.alexisbinh.openeco.migrator;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Imported error text comes from foreign databases and can contain legacy formatting codes or
 * MiniMessage tags. It must be rendered literally, never parsed.
 */
class EconomyMigrationBridgeImplFormattingTest {

    private static final String HOSTILE = "name \u00a7cSteve <click:run_command:'/op Steve'>run</click>";

    @Test
    void errorLineRendersImportedTextLiterally() {
        Component message = EconomyMigrationBridgeImpl.errorLine(HOSTILE);

        assertEquals("- " + HOSTILE, PlainTextComponentSerializer.plainText().serialize(message));
        assertFalse(hasClick(message), "imported text must not be parsed into a click event");
    }

    @Test
    void errorLineFallsBackForNull() {
        assertEquals("- unknown error",
                PlainTextComponentSerializer.plainText().serialize(EconomyMigrationBridgeImpl.errorLine(null)));
    }

    private static boolean hasClick(Component component) {
        if (component.clickEvent() != null) {
            return true;
        }
        for (Component child : component.children()) {
            if (hasClick(child)) {
                return true;
            }
        }
        return false;
    }
}
