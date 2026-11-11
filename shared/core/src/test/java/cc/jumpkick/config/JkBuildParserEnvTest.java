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
    void declared_names_values_and_inherit_are_read_in_manifest_order() {
        JkBuild b = JkBuildParser.parse(JkBuildParserFixtures.PROJECT + """

                [env]
                require = ["MY_TOKEN_VALUE"]
                optional = ["FEATURE_X", "DOCKER_HOST"]
                vars = [{ TZ = "UTC", LANG = "C" }]
                inherit = true
                """);
        EnvConfig env = b.build().env();
        assertThat(env.inherit()).isTrue();
        assertThat(env.require()).containsExactly("MY_TOKEN_VALUE");
        assertThat(env.optional()).containsExactly("FEATURE_X", "DOCKER_HOST");
        assertThat(env.vars()).containsExactly(new EnvDecl.Set("TZ", "UTC"), new EnvDecl.Set("LANG", "C"));
        assertThat(env.declared()).containsExactly("MY_TOKEN_VALUE", "FEATURE_X", "DOCKER_HOST");
    }

    @Test
    void each_declared_name_carries_the_manifest_line_it_is_written_on() {
        String manifest = JkBuildParserFixtures.PROJECT + """

                [env]
                require = [
                  "FIRST",
                  "SECOND",
                ]
                """;
        EnvConfig env = JkBuildParser.parse(manifest).build().env();
        String[] lines = manifest.split("\n", -1);
        assertThat(lines[env.line("FIRST") - 1]).contains("\"FIRST\"");
        assertThat(lines[env.line("SECOND") - 1]).contains("\"SECOND\"");
    }

    @Test
    void an_absent_or_empty_table_is_the_default() {
        assertThat(JkBuildParser.parse(JkBuildParserFixtures.PROJECT).build().env())
                .isEqualTo(EnvConfig.EMPTY);
        EnvConfig empty = JkBuildParser.parse(JkBuildParserFixtures.PROJECT + "\n[env]\n")
                .build()
                .env();
        assertThat(empty.isEmpty()).isTrue();
    }

    @Test
    void the_test_jvm_gets_the_declared_names_then_the_values_then_test_env() {
        JkBuild b = JkBuildParser.parse(JkBuildParserFixtures.PROJECT + """

                [env]
                optional = ["FEATURE_X"]
                vars = [{ TZ = "UTC" }]

                [test]
                env = ["CI"]
                """);
        assertThat(b.build().testEnvDecls())
                .containsExactly(
                        new EnvDecl.Forward("FEATURE_X"), new EnvDecl.Set("TZ", "UTC"), new EnvDecl.Forward("CI"));
    }

    @Test
    void a_bare_name_in_vars_is_refused_with_the_declaration_spelling() {
        assertThatThrownBy(() -> JkBuildParser.parse(JkBuildParserFixtures.PROJECT + """

                        [env]
                        vars = ["DOCKER_HOST"]
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("optional = [\"DOCKER_HOST\"]");
    }

    @Test
    void a_name_is_declared_once() {
        assertThatThrownBy(() -> JkBuildParser.parse(
                        JkBuildParserFixtures.PROJECT + "\n[env]\nrequire = [\"A\"]\noptional = [\"A\"]\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("A is declared twice");
        assertThatThrownBy(() -> JkBuildParser.parse(
                        JkBuildParserFixtures.PROJECT + "\n[env]\noptional = [\"A\"]\nvars = [{ A = \"1\" }]\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("A is declared and also set in vars");
    }

    @Test
    void a_variable_written_straight_into_the_table_is_refused_with_the_vars_spelling() {
        assertThatThrownBy(() -> JkBuildParser.parse(JkBuildParserFixtures.PROJECT + """

                        [env]
                        TZ = "UTC"
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[env] unknown key `TZ`")
                .hasMessageContaining("vars = [{ TZ = \"…\" }]");
    }

    @Test
    void shapes_are_checked() {
        assertThatThrownBy(() -> JkBuildParser.parse(JkBuildParserFixtures.PROJECT + "\n[env]\ninherit = \"yes\"\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[env] inherit must be true or false");
        assertThatThrownBy(() -> JkBuildParser.parse(JkBuildParserFixtures.PROJECT + "\n[env]\nvars = \"CI\"\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[env] vars must be an array");
        assertThatThrownBy(() -> JkBuildParser.parse(JkBuildParserFixtures.PROJECT + "\n[env]\nrequire = \"CI\"\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[env] require must be an array of names");
        assertThatThrownBy(
                        () -> JkBuildParser.parse(JkBuildParserFixtures.PROJECT + "\n[env]\noptional = [\"TZ=UTC\"]\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[env].optional[0]")
                .hasMessageContaining("is not a name; to set a value write { TZ = \"UTC\" }");
    }
}
