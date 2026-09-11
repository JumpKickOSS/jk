// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.EnvConfig;
import cc.jumpkick.model.EnvDecl;
import cc.jumpkick.model.JkBuild;
import org.junit.jupiter.api.Test;

/** {@code [env]}: what a module's workers get from the environment beyond the allow-list. */
class JkBuildParserEnvTest {

    @Test
    void inherit_and_vars_are_read_and_vars_keep_manifest_order() {
        JkBuild b = JkBuildParser.parse(JkBuildParserFixtures.PROJECT + """

                [env]
                inherit = true
                vars = ["DOCKER_HOST", { TZ = "UTC", LANG = "C" }]
                """);
        EnvConfig env = b.build().env();
        assertThat(env.inherit()).isTrue();
        assertThat(env.vars())
                .containsExactly(
                        new EnvDecl.Forward("DOCKER_HOST"), new EnvDecl.Set("TZ", "UTC"), new EnvDecl.Set("LANG", "C"));
    }

    @Test
    void an_absent_or_empty_table_is_the_default() {
        assertThat(JkBuildParser.parse(JkBuildParserFixtures.PROJECT).build().env())
                .isEqualTo(EnvConfig.EMPTY);
        EnvConfig empty = JkBuildParser.parse(JkBuildParserFixtures.PROJECT + "\n[env]\n")
                .build()
                .env();
        assertThat(empty.inherit()).isFalse();
        assertThat(empty.vars()).isEmpty();
    }

    @Test
    void test_env_layers_after_env_vars_for_the_test_jvm() {
        JkBuild b = JkBuildParser.parse(JkBuildParserFixtures.PROJECT + """

                [env]
                vars = [{ TZ = "UTC" }]

                [test]
                env = ["CI"]
                """);
        assertThat(b.build().testEnvDecls()).containsExactly(new EnvDecl.Set("TZ", "UTC"), new EnvDecl.Forward("CI"));
    }

    @Test
    void a_variable_written_straight_into_the_table_is_refused_with_the_vars_spelling() {
        assertThatThrownBy(() -> JkBuildParser.parse(JkBuildParserFixtures.PROJECT + """

                        [env]
                        TZ = "UTC"
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[env] unknown key `TZ`")
                .hasMessageContaining("inherit, vars")
                .hasMessageContaining("vars = [{ TZ = \"…\" }]");
    }

    @Test
    void inherit_must_be_a_boolean_and_vars_an_array() {
        assertThatThrownBy(() -> JkBuildParser.parse(JkBuildParserFixtures.PROJECT + "\n[env]\ninherit = \"yes\"\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[env] inherit must be true or false");
        assertThatThrownBy(() -> JkBuildParser.parse(JkBuildParserFixtures.PROJECT + "\n[env]\nvars = \"CI\"\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[env] vars must be an array");
        assertThatThrownBy(() -> JkBuildParser.parse(JkBuildParserFixtures.PROJECT + "\n[env]\nvars = [\"TZ=UTC\"]\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[env].vars[0]")
                .hasMessageContaining("looks like NAME=value");
    }
}
