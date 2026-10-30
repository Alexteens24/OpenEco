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

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Migration output includes exception messages and imported data, which may contain legacy
 * formatting codes or MiniMessage tags. Concatenating that text into the template either threw
 * a parse exception (masking the real migration error) or rendered it as components.
 */
class MigrateCommandFormattingTest {

    private static final String HOSTILE = "bad \u00a7cvalue <click:open_url:'https://example.invalid'>click me</click> <bold>";

    @Test
    void migrationFailureRendersDynamicTextLiterally() {
        Component message = MigrateCommand.migrationFailed(HOSTILE);

        assertEquals("Migration failed: " + HOSTILE, plain(message));
        assertNoClick(message);
    }

    @Test
    void migrationErrorLineRendersDynamicTextLiterally() {
        Component message = MigrateCommand.migrationErrorLine(HOSTILE);

        assertEquals("- " + HOSTILE, plain(message));
        assertNoClick(message);
    }

    @Test
    void nullReasonFallsBackInsteadOfPrintingNull() {
        assertEquals("Migration failed: unknown error", plain(MigrateCommand.migrationFailed(null)));
        assertEquals("- unknown error", plain(MigrateCommand.migrationErrorLine(null)));
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static void assertNoClick(Component component) {
        assertFalse(hasClick(component), "dynamic text must not be parsed into a click event");
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
