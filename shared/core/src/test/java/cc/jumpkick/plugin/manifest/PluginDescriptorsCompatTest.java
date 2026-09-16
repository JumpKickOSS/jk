// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.model.JkVersion;
import org.junit.jupiter.api.Test;

class PluginDescriptorsCompatTest {

    @Test
    void parse_without_enforce_records_a_future_floor() {
        String toml = """
                [plugin]
                id = "future"
                table = "future"
                version = "1"
                jk-compat = ">=99.0"
                """;
        assertThatThrownBy(() -> PluginDescriptors.parse(toml, "future.toml"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("self update");
        PluginDescriptor d = PluginDescriptors.parse(toml, "future.toml", false);
        assertThat(PluginDescriptors.jkCompatFloor(d.jkCompat())).isEqualTo("99.0");
        assertThat(PluginDescriptors.maxFloor(JkVersion.VERSION, "99.0")).isEqualTo("99.0");
        assertThat(PluginDescriptors.maxFloor("0.11.0", "0.12.0")).isEqualTo("0.12.0");
    }

    @Test
    void the_sdk_field_is_the_release_the_plugin_compiled_against_and_is_optional() {
        PluginDescriptor named = PluginDescriptors.parse("""
                [plugin]
                id = "hello"
                table = "hello"
                version = "0.1.0"
                sdk = "0.13.7"
                jk-compat = ">=0.13"
                """, "hello.toml");
        assertThat(named.sdk()).isEqualTo("0.13.7");

        PluginDescriptor unnamed = PluginDescriptors.parse("""
                [plugin]
                id = "hello"
                table = "hello"
                version = "0.1.0"
                """, "hello.toml");
        assertThat(unnamed.sdk()).isNull();

        assertThatThrownBy(() -> PluginDescriptors.parse("""
                        [plugin]
                        id = "hello"
                        table = "hello"
                        version = "0.1.0"
                        sdk = " "
                        """, "hello.toml"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("hello.toml.plugin.sdk must name the jk-plugin-sdk release");
    }
}
