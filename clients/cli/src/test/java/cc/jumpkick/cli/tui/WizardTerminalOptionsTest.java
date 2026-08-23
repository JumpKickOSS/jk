// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Options of the single JLine system terminal shared by {@link Wizard} and the canPrompt probe. */
class WizardTerminalOptionsTest {

    @Test
    void system_terminal_builder_forces_utf8_only_on_windows() {
        String previous = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Windows 11");
            assertThat(Interactivity.terminalEncodingOrNull()).isEqualTo(StandardCharsets.UTF_8);

            // Off Windows, JLine's LANG/LC_ALL detection is authoritative: forcing UTF-8 over a
            // latin-1 terminal mojibakes output and mis-decodes typed keys.
            System.setProperty("os.name", "Linux");
            assertThat(Interactivity.terminalEncodingOrNull()).isNull();
        } finally {
            if (previous == null) System.clearProperty("os.name");
            else System.setProperty("os.name", previous);
        }
    }
}
