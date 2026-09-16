// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static cc.jumpkick.config.JkBuildParserFixtures.PROJECT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.Scope;
import org.junit.jupiter.api.Test;

/**
 * The {@code plugin} scope is the lock's own: {@code jk lock} writes a pinned plugin's SDK floor
 * under it, so a {@code [plugin-dependencies]} table in {@code jk.toml} is refused by the parser
 * and never written by the editor, both naming the mechanism that owns the scope.
 */
class PluginDependenciesTableTest {

    @Test
    void a_hand_written_plugin_dependencies_table_is_refused_naming_the_floor() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                        [plugin-dependencies]
                        extra = "com.acme:extra:1.0.0"
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[plugin-dependencies] is not a table you write")
                .hasMessageContaining("jk lock")
                .hasMessageContaining("SDK floor")
                .hasMessageContaining("[plugins]");
    }

    @Test
    void an_empty_table_is_refused_too() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + "[plugin-dependencies]\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[plugin-dependencies] is not a table you write");
    }

    @Test
    void the_editor_refuses_the_plugin_scope_before_writing() {
        assertThatThrownBy(
                        () -> JkBuildEditor.addDependency(PROJECT, Scope.PLUGIN, "extra", "com.acme", "extra", "1.0.0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[plugin-dependencies] is not a table you write");
        assertThatThrownBy(() -> JkBuildEditor.removeDependency(PROJECT, Scope.PLUGIN, "extra"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[plugin-dependencies] is not a table you write");
        assertThat(JkBuildParser.parse(PROJECT).dependencies().of(Scope.PLUGIN)).isEmpty();
    }
}
