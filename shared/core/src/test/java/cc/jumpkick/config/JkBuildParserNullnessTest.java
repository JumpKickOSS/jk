// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.JavacConfig;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.NullnessConfig;
import cc.jumpkick.model.Scope;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@code [nullness]}: the three jars it brings and the {@code [javac]} it folds into. */
class JkBuildParserNullnessTest {

    @Test
    void a_bare_table_checks_the_group_with_nullaway_alone_and_brings_its_jars() {
        JkBuild b = JkBuildParser.parse(JkBuildParserFixtures.PROJECT + "\n[nullness]\n");

        assertThat(b.build().nullness()).isEqualTo(NullnessConfig.DEFAULT);
        assertThat(b.build().javac()).as("[javac] as written stays empty").isEqualTo(JavacConfig.EMPTY);
        JavacConfig javac = b.javac();
        assertThat(javac.plugins())
                .containsExactly(Map.entry(
                        "ErrorProne",
                        List.of(
                                "-XepDisableAllChecks",
                                "-Xep:NullAway:ERROR",
                                "-XepOpt:NullAway:JSpecifyMode=true",
                                "-XepOpt:NullAway:AnnotatedPackages=com.example")));
        assertThat(javac.args()).containsExactly("-XDcompilePolicy=simple", "--should-stop=ifError=FLOW");
        assertThat(javac.forTests().plugins())
                .as("tests are not checked by default")
                .isEmpty();
        assertThat(javac.forTests().args()).isEmpty();

        assertThat(coordinates(b, Scope.MAIN)).containsExactly("org.jspecify:jspecify=1.0.1");
        assertThat(coordinates(b, Scope.PROCESSOR))
                .containsExactly("com.google.errorprone:error_prone_core=2.51.0", "com.uber.nullaway:nullaway=0.14.3");
        assertThat(b.dependencies().of(Scope.PROCESSOR))
                .allSatisfy(d -> assertThat(d.impliedBy()).isEqualTo("nullness"));
    }

    @Test
    void a_declared_jar_keeps_its_own_version() {
        JkBuild b = JkBuildParser.parse(JkBuildParserFixtures.PROJECT + """

                [provided-dependencies]
                jspecify = "org.jspecify:jspecify:1.0.0"

                [processor-dependencies]
                nullaway = "com.uber.nullaway:nullaway:0.14.0"

                [nullness]
                """);

        assertThat(b.dependencies().of(Scope.MAIN))
                .as("JSpecify is declared in another scope")
                .isEmpty();
        assertThat(coordinates(b, Scope.PROCESSOR))
                .containsExactly("com.uber.nullaway:nullaway=0.14.0", "com.google.errorprone:error_prone_core=2.51.0");
    }

    @Test
    void the_keys_name_the_packages_severity_extra_options_and_the_tests() {
        JkBuild b = JkBuildParser.parse(JkBuildParserFixtures.PROJECT + """

                [nullness]
                include  = []
                severity = "warn"
                options  = ["-XepOpt:NullAway:KnownInitializers=com.example.Cli.run"]
                tests    = true
                """);

        List<String> checks = List.of(
                "-XepDisableAllChecks",
                "-Xep:NullAway:WARN",
                "-XepOpt:NullAway:JSpecifyMode=true",
                "-XepOpt:NullAway:OnlyNullMarked=true",
                "-XepOpt:NullAway:KnownInitializers=com.example.Cli.run");
        assertThat(b.javac().plugins().get("ErrorProne")).isEqualTo(checks);
        assertThat(b.javac().forTests().plugins().get("ErrorProne"))
                .as("tests = true checks compile-test too")
                .isEqualTo(checks);

        JkBuild named = JkBuildParser.parse(JkBuildParserFixtures.PROJECT + """

                [nullness]
                include = ["com.example.core", "org.acme"]
                """);
        assertThat(named.javac().plugins().get("ErrorProne"))
                .contains("-XepOpt:NullAway:AnnotatedPackages=com.example.core,org.acme");
    }

    @Test
    void exclude_leaves_packages_unchecked_and_explicit_requires_every_class_to_choose() {
        JkBuild b = JkBuildParser.parse(JkBuildParserFixtures.PROJECT + """

                [nullness]
                exclude  = ["com.example.generated", "com.example.legacy"]
                explicit = true
                severity = "warn"
                """);

        assertThat(b.javac().plugins().get("ErrorProne"))
                .containsExactly(
                        "-XepDisableAllChecks",
                        "-Xep:NullAway:WARN",
                        "-XepOpt:NullAway:JSpecifyMode=true",
                        "-XepOpt:NullAway:AnnotatedPackages=com.example",
                        "-XepOpt:NullAway:UnannotatedSubPackages=com.example.generated,com.example.legacy",
                        "-Xep:RequireExplicitNullMarking:WARN");
    }

    @Test
    void a_declared_error_prone_keeps_its_checks_and_the_test_table_its_override() {
        JkBuild b = JkBuildParser.parse(JkBuildParserFixtures.PROJECT + """

                [javac]
                plugins = { ErrorProne = { options = ["-Xep:MissingOverride:ERROR"] } }
                args    = ["-Xlint:all", "-XDcompilePolicy=simple"]

                [javac.test]
                release = 25
                args    = ["-Xlint:none"]

                [nullness]
                tests = true
                """);

        JavacConfig javac = b.javac();
        assertThat(javac.plugins().get("ErrorProne"))
                .as("Error Prone's own checks stay on")
                .startsWith("-Xep:MissingOverride:ERROR", "-Xep:NullAway:ERROR")
                .doesNotContain("-XepDisableAllChecks");
        assertThat(javac.args()).containsExactly("-Xlint:all", "-XDcompilePolicy=simple", "--should-stop=ifError=FLOW");
        assertThat(javac.forTests().args())
                .containsExactly("-Xlint:none", "-XDcompilePolicy=simple", "--should-stop=ifError=FLOW");
        assertThat(javac.forTests().release()).isEqualTo(25);

        JkBuild unchecked = JkBuildParser.parse(JkBuildParserFixtures.PROJECT + """

                [javac.test]
                args = ["-Xlint:none"]

                [nullness]
                """);
        assertThat(unchecked.javac().forTests().args())
                .as("the test table as written")
                .containsExactly("-Xlint:none");
        assertThat(unchecked.javac().forTests().plugins()).isEmpty();
    }

    @Test
    void a_module_with_no_group_names_what_to_check() {
        assertThatThrownBy(() -> NullnessConfig.DEFAULT.fold(JavacConfig.EMPTY, ""))
                .hasMessageContaining("[nullness] checks the module's group by default")
                .hasMessageContaining("include = []");
    }

    @Test
    void unknown_keys_and_bad_values_fail_the_parse() {
        assertThatThrownBy(() -> JkBuildParser.parse(JkBuildParserFixtures.PROJECT + "\n[nullness]\npackages = []\n"))
                .hasMessageContaining("[nullness] unknown key `packages`")
                .hasMessageContaining("include, exclude, explicit, severity, options, tests");
        assertThatThrownBy(
                        () -> JkBuildParser.parse(JkBuildParserFixtures.PROJECT + "\n[nullness]\nseverity = \"off\"\n"))
                .hasMessageContaining("\"error\" or \"warn\"");
        assertThatThrownBy(() ->
                        JkBuildParser.parse(JkBuildParserFixtures.PROJECT + "\n[nullness]\nexclude = [\"a,b\"]\n"))
                .hasMessageContaining("[nullness].exclude lists one package per entry");
        assertThatThrownBy(() -> JkBuildParser.parse(JkBuildParserFixtures.PROJECT + "\n[nullness]\nexplicit = 1\n"))
                .hasMessageContaining("[nullness].explicit must be true or false");
        assertThatThrownBy(() -> JkBuildParser.parse(JkBuildParserFixtures.PROJECT + "\n[nullness]\ntests = \"yes\"\n"))
                .hasMessageContaining("[nullness].tests must be true or false");
    }

    private static List<String> coordinates(JkBuild b, Scope scope) {
        return b.dependencies().of(scope).stream()
                .map(d -> d.module() + "=" + d.version().raw())
                .toList();
    }
}
