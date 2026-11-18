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

    @Test
    void inherit_written_false_is_strict_and_unwritten_is_not() {
        EnvConfig strict = JkBuildParser.parse(JkBuildParserFixtures.PROJECT + "\n[env]\ninherit = false\n")
                .build()
                .env();
        assertThat(strict.strict()).isTrue();
        assertThat(strict.inherit()).isFalse();
        assertThat(JkBuildParser.parse(JkBuildParserFixtures.PROJECT)
                        .build()
                        .env()
                        .strict())
                .as("the default: a test JVM reads the caller's shell")
                .isFalse();
        assertThat(JkBuildParser.parse(JkBuildParserFixtures.PROJECT + "\n[env]\ninherit = true\n")
                        .build()
                        .env()
                        .strict())
                .isFalse();
    }

    @Test
    void scope_takes_names_and_prefixes_ending_in_a_star() {
        EnvConfig env = JkBuildParser.parse(
                        JkBuildParserFixtures.PROJECT + "\n[env]\nscope = [\"SPRING_*\", \"MYAPP_URL\"]\n")
                .build()
                .env();
        assertThat(env.scope()).containsExactly("SPRING_*", "MYAPP_URL");
        assertThatThrownBy(() -> JkBuildParser.parse(JkBuildParserFixtures.PROJECT + "\n[env]\nscope = [\"S*X\"]\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[env].scope[0]")
                .hasMessageContaining("a prefix ending in *");
        assertThatThrownBy(() -> JkBuildParser.parse(JkBuildParserFixtures.PROJECT + "\n[env]\nscope = \"S*\"\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[env] scope must be an array");
    }

    @Test
    void a_workspace_roots_strict_switch_and_scope_hold_for_its_members() {
        JkBuild root =
                JkBuildParser.parse(JkBuildParserFixtures.PROJECT + "\n[env]\ninherit = false\nscope = [\"APP_*\"]\n");
        JkBuild member = JkBuildParser.parse(JkBuildParserFixtures.PROJECT + "\n[env]\noptional = [\"X\"]\n");
        EnvConfig inherited =
                WorkspaceLoader.inheritTestEnvPolicy(member, root).build().env();
        assertThat(inherited.strict()).isTrue();
        assertThat(inherited.scope()).containsExactly("APP_*");
        assertThat(inherited.optional()).containsExactly("X");
        JkBuild own = JkBuildParser.parse(JkBuildParserFixtures.PROJECT + "\n[env]\nscope = [\"MINE_*\"]\n");
        assertThat(WorkspaceLoader.inheritTestEnvPolicy(own, root).build().env().scope())
                .as("a member's own scope wins")
                .containsExactly("MINE_*");
    }
}
