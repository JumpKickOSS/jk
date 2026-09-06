// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.GuardsConfig;
import org.junit.jupiter.api.Test;
import org.tomlj.Toml;

class ManifestGuardsTest {

    @Test
    void absent_table_is_the_absent_config_and_costs_nothing() {
        GuardsConfig c = ManifestGuards.parse(Toml.parse("name = \"x\"\n"));
        assertThat(c).isSameAs(GuardsConfig.ABSENT);
        assertThat(c.declared()).isFalse();
        assertThat(c.onBuild()).isTrue();
    }

    @Test
    void the_two_keys_parse() {
        GuardsConfig c = ManifestGuards.parse(
                Toml.parse("[guards]\non-build = false\ncoverage-report = \"target/jacoco.xml\"\n"));
        assertThat(c.declared()).isTrue();
        assertThat(c.onBuild()).isFalse();
        assertThat(c.coverageReport()).isEqualTo("target/jacoco.xml");
    }

    @Test
    void an_unknown_key_is_refused_and_points_at_the_rule_file() {
        assertThatThrownBy(() -> ManifestGuards.parse(Toml.parse("[guards]\nonbuild = false\n")))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("unknown key `onbuild`")
                .hasMessageContaining("jk-guards.toml");
    }

    @Test
    void wrong_shapes_are_refused() {
        assertThatThrownBy(() -> ManifestGuards.parse(Toml.parse("guards = true\n")))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("must be a table");
        assertThatThrownBy(() -> ManifestGuards.parse(Toml.parse("[guards]\non-build = \"no\"\n")))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("on-build must be a boolean");
    }
}
