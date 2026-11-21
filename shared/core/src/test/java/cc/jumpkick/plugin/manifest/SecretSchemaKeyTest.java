// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JkBuildParseException;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/** A {@code secret = true} schema key is a string or a string-map, the values a secret is read as. */
class SecretSchemaKeyTest {

    @Test
    void a_string_key_may_be_secret() {
        PluginDescriptor manifest = Objects.requireNonNull(
                PluginDescriptors.parse(manifest("password = { type = \"string\", secret = true }"), "p.toml"));
        assertThat(Objects.requireNonNull(manifest.schema().get("password")).secret())
                .isTrue();
    }

    @Test
    void a_string_map_key_may_be_secret() {
        PluginDescriptor manifest = Objects.requireNonNull(
                PluginDescriptors.parse(manifest("secret-env = { type = \"string-map\", secret = true }"), "p.toml"));
        assertThat(Objects.requireNonNull(manifest.schema().get("secret-env")).secret())
                .isTrue();
    }

    @Test
    void a_secret_of_another_type_is_refused() {
        assertThatThrownBy(() ->
                        PluginDescriptors.parse(manifest("retries = { type = \"int\", secret = true }"), "p.toml"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("retries")
                .hasMessageContaining("secret = true needs type = \"string\" or \"string-map\"");
    }

    private static String manifest(String line) {
        return """
                [plugin]
                id = "p"
                table = "p"
                version = "1.0.0"

                [schema]
                %s
                """.formatted(line);
    }
}
