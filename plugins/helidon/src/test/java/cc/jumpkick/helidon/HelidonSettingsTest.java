// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.helidon;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.plugin.PluginConfig;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The {@code [helidon]} table as the binding step reads it. */
class HelidonSettingsTest {

    @Test
    void the_defaults_leave_binding_to_the_sources_and_ask_for_the_libs_layout() {
        // What the engine hands over for `[helidon] version = "27.0.1"`: the schema defaults applied,
        // binding left unset.
        PluginConfig config = new PluginConfig(
                "helidon",
                Map.of(
                        "version", "27.0.1",
                        "generate-main", false,
                        "validate", true,
                        "fail-on-warning", false,
                        "layout", "libs",
                        "declarative", false));

        assertThat(HelidonSettings.of(config))
                .isEqualTo(new HelidonSettings(
                        "27.0.1", Optional.empty(), false, true, false, false, HelidonSettings.Layout.LIBS, false));
    }

    @Test
    void every_key_reads_as_written() {
        PluginConfig config = new PluginConfig(
                "helidon",
                Map.of(
                        "version", "27.0.1",
                        "binding", false,
                        "generate-main", true,
                        "validate", false,
                        "fail-on-warning", true,
                        "test-binding", true,
                        "layout", "jar",
                        "declarative", true));

        assertThat(HelidonSettings.of(config))
                .isEqualTo(new HelidonSettings(
                        "27.0.1", Optional.of(false), true, false, true, true, HelidonSettings.Layout.JAR, true));
    }
}
