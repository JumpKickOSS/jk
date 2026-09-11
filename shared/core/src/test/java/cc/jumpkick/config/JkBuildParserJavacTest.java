// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.JkBuild;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@code [javac]}: the javac plugins and verbatim args both compile steps lower into javac's argv. */
class JkBuildParserJavacTest {

    @Test
    void plugins_keep_manifest_order_and_args_ride_beside_them() {
        JkBuild b = JkBuildParser.parse(JkBuildParserFixtures.PROJECT + """

                [javac]
                plugins = { ErrorProne = { options = ["-Xep:NullAway:ERROR", "-XepOpt:NullAway:AnnotatedPackages=com.example"] }, Manifold = {} }
                args    = ["-XDcompilePolicy=simple", "--should-stop=ifError=FLOW"]
                """);
        JkBuild.JavacConfig javac = b.build().javac();
        assertThat(javac.plugins().keySet()).containsExactly("ErrorProne", "Manifold");
        assertThat(javac.plugins().get("ErrorProne"))
                .containsExactly("-Xep:NullAway:ERROR", "-XepOpt:NullAway:AnnotatedPackages=com.example");
        assertThat(javac.plugins().get("Manifold")).isEmpty();
        assertThat(javac.args()).containsExactly("-XDcompilePolicy=simple", "--should-stop=ifError=FLOW");
    }

    @Test
    void the_dotted_spelling_is_the_same_table() {
        JkBuild b = JkBuildParser.parse(JkBuildParserFixtures.PROJECT + """

                [javac.plugins.ErrorProne]
                options = ["-Xep:NullAway:ERROR"]
                """);
        assertThat(b.build().javac().plugins()).containsEntry("ErrorProne", List.of("-Xep:NullAway:ERROR"));
        assertThat(b.build().javac().args()).isEmpty();
    }

    @Test
    void an_absent_table_is_the_empty_config_and_the_release_key_is_untouched() {
        JkBuild b = JkBuildParser.parse(JkBuildParserFixtures.PROJECT);
        assertThat(b.build().javac()).isSameAs(JkBuild.JavacConfig.EMPTY);
        assertThat(b.build().javac().isEmpty()).isTrue();
        assertThat(b.project().javaRelease()).isEqualTo(25);
    }

    @Test
    void an_unknown_key_is_refused_naming_the_known_ones() {
        assertThatThrownBy(() -> JkBuildParser.parse(JkBuildParserFixtures.PROJECT + """

                        [javac]
                        plugin = { ErrorProne = {} }
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessage("[javac] unknown key `plugin` — expected one of: plugins, args, test");
        assertThatThrownBy(() -> JkBuildParser.parse(JkBuildParserFixtures.PROJECT + """

                        [javac.plugins.ErrorProne]
                        opts = ["-Xep:NullAway:ERROR"]
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessage("[javac.plugins.ErrorProne] unknown key `opts` — expected one of: options");
    }

    @Test
    void the_test_table_replaces_the_main_one_for_compile_test_only() {
        JkBuild b = JkBuildParser.parse(JkBuildParserFixtures.PROJECT + """
                [javac]
                plugins = { ErrorProne = { options = ["-Xep:NullAway:ERROR"] } }
                args    = ["-XDcompilePolicy=simple"]

                [javac.test]
                plugins = {}
                """);
        JkBuild.JavacConfig javac = b.build().javac();
        assertThat(javac.plugins()).containsOnlyKeys("ErrorProne");
        assertThat(javac.forTests().isEmpty())
                .as("the suite compiles without the plugins")
                .isTrue();
        assertThat(javac.forTests().args()).isEmpty();
    }

    @Test
    void without_a_test_table_compile_test_runs_the_main_one() {
        JkBuild b = JkBuildParser.parse(JkBuildParserFixtures.PROJECT + """
                [javac]
                plugins = { ErrorProne = {} }
                """);
        assertThat(b.build().javac().forTests()).isSameAs(b.build().javac());
    }

    @Test
    void the_test_table_cannot_nest_and_names_its_own_keys() {
        assertThatThrownBy(() -> JkBuildParser.parse(JkBuildParserFixtures.PROJECT + """
                        [javac.test]
                        test = {}
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessage("[javac.test] unknown key `test` — expected one of: plugins, args");
        assertThatThrownBy(() -> JkBuildParser.parse(JkBuildParserFixtures.PROJECT + """
                        [javac.test.plugins.ErrorProne]
                        opts = []
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessage("[javac.test.plugins.ErrorProne] unknown key `opts` — expected one of: options");
    }

    @Test
    void shapes_that_are_not_tables_of_string_arrays_are_refused() {
        assertThatThrownBy(() -> JkBuildParser.parse(JkBuildParserFixtures.PROJECT + """

                        [javac]
                        plugins = ["ErrorProne"]
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageStartingWith("[javac].plugins must be a table keyed by plugin name");
        assertThatThrownBy(() -> JkBuildParser.parse(JkBuildParserFixtures.PROJECT + """

                        [javac]
                        plugins = { ErrorProne = "on" }
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageStartingWith("[javac.plugins.ErrorProne] must be a table");
        assertThatThrownBy(() -> JkBuildParser.parse(JkBuildParserFixtures.PROJECT + """

                        [javac]
                        args = "-Xlint:all"
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessage("[javac].args must be an array of strings");
        assertThatThrownBy(() -> JkBuildParser.parse(JkBuildParserFixtures.PROJECT + """

                        [javac]
                        plugins = { ErrorProne = { options = [1, 2] } }
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessage("[javac.plugins.ErrorProne].options must be an array of strings");
    }
}
