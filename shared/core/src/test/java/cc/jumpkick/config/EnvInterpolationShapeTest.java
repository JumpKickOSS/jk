// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.EnvDecl;
import org.junit.jupiter.api.Test;

/** Where a {@code ${VAR}} may stand in the environment tables, and what the error says where it may not. */
class EnvInterpolationShapeTest {

    private static final String HEAD = "group = \"t\"\nname = \"m\"\nversion = \"1\"\n";

    @Test
    void a_test_env_value_may_reference_the_environment() {
        var build = JkBuildParser.parse(HEAD + "[test]\nenv = [{ X = \"${HOME}\" }]\n");
        assertThat(build.build().testEnv()).containsExactly(new EnvDecl.Set("X", "${HOME}"));
    }

    /** [env] vars reach the compilers, whose keys do not carry the machine's environment. */
    @Test
    void an_env_vars_value_may_not_reference_the_environment() {
        assertThatThrownBy(() -> JkBuildParser.parse(HEAD + "[env]\nvars = [{ X = \"${HOME}\" }]\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("env.vars[0].X");
    }

    @Test
    void a_test_env_written_as_a_table_is_told_its_shape() {
        assertThatThrownBy(() -> JkBuildParser.parse(HEAD + "[test]\nenv = { X = \"${HOME}\" }\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("must be an array")
                .hasMessageNotContaining("not allowed here");
    }
}
