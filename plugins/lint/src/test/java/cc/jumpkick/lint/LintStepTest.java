// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.plugin.testing.FakeBuildIo;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The step body over the shared engine fake and real forks of Checkstyle and SpotBugs (their
 * closures are this test's classpath): a violation reaches the diagnostics with its file, line and
 * rule id, its severity decides the step's fate under {@code fail-on}, the report lands in the
 * declared output, and a clean source is a step with no finding.
 */
class LintStepTest {

    private static final String CONFIG = """
            <?xml version="1.0"?>
            <!DOCTYPE module PUBLIC "-//Checkstyle//DTD Checkstyle Configuration 1.3//EN"
                "https://checkstyle.org/dtds/configuration_1_3.dtd">
            <module name="Checker">
              <property name="severity" value="%s"/>
              <module name="TreeWalker">
                <module name="MagicNumber"/>
              </module>
            </module>
            """;

    private static final String SOURCE = """
            package demo;

            public final class Sample {
                public static int twice(int n) {
                    return n * 42;
                }
            }
            """;

    private static final String REF_COMPARISON = """
            package demo;

            public final class Sample {
                public static boolean same(Integer a, Integer b) {
                    return a == b;
                }
            }
            """;

    private static final String CLEAN = """
            package demo;

            public final class Sample {
                public static boolean same(Integer a, Integer b) {
                    return a.equals(b);
                }
            }
            """;

    @Test
    void a_violation_is_a_warning_diagnostic_with_the_rule_id_and_the_step_passes(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = lintModule(tmp, "warning", SOURCE);

        LintStep.run(io, LintTool.CHECKSTYLE);

        Path source = tmp.resolve("src/main/java/demo/Sample.java");
        assertThat(io.diagnostics())
                .containsExactly("warning: " + source + ":5:20: '42' is a magic number. [MagicNumber]");
        assertThat(tmp.resolve("scratch/lint/checkstyle/checkstyle.xml")).isRegularFile();
        assertThat(io.labels()).containsExactly("checkstyle (1 root)");
    }

    @Test
    void an_error_severity_violation_fails_the_step_and_is_an_error_diagnostic(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = lintModule(tmp, "error", SOURCE);

        assertThatThrownBy(() -> LintStep.run(io, LintTool.CHECKSTYLE))
                .hasMessageContaining("checkstyle: 1 finding at or above `fail-on = \"error\"`");
        assertThat(io.diagnostics())
                .singleElement()
                .asString()
                .startsWith("error: ")
                .contains("[MagicNumber]");
    }

    @Test
    void fail_on_warning_fails_on_a_warning_and_never_fails_on_nothing(@TempDir Path tmp) throws Exception {
        FakeBuildIo strict =
                lintModule(tmp.resolve("strict"), "warning", SOURCE).config("fail-on", "warning");
        assertThatThrownBy(() -> LintStep.run(strict, LintTool.CHECKSTYLE))
                .hasMessageContaining("fail-on = \"warning\"");

        FakeBuildIo lenient =
                lintModule(tmp.resolve("lenient"), "error", SOURCE).config("fail-on", "never");
        LintStep.run(lenient, LintTool.CHECKSTYLE);
        assertThat(lenient.diagnostics()).singleElement().asString().startsWith("error: ");
    }

    @Test
    void a_clean_source_has_no_finding(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = lintModule(tmp, "error", SOURCE.replace("n * 42", "n * 2"));

        LintStep.run(io, LintTool.CHECKSTYLE);

        assertThat(io.diagnostics()).isEmpty();
    }

    @Test
    void a_module_without_the_source_roots_writes_an_empty_report(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = new FakeBuildIo(tmp, "lint").config("checkstyle", "checkstyle.xml");

        LintStep.run(io, LintTool.CHECKSTYLE);

        assertThat(io.diagnostics()).isEmpty();
        assertThat(io.labels()).containsExactly("checkstyle (no sources)");
        assertThat(tmp.resolve("scratch/lint/checkstyle/checkstyle.xml")).isEmptyFile();
    }

    /** nacos's maintainer-sdk-test: every source under the roots matches an exclude glob, so there is nothing to lint. */
    @Test
    void a_module_whose_sources_are_all_excluded_is_a_no_op(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = lintModule(tmp, "error", SOURCE).config("exclude", List.of("**/demo/**"));

        LintStep.run(io, LintTool.CHECKSTYLE);

        assertThat(io.diagnostics()).isEmpty();
        assertThat(io.labels()).containsExactly("checkstyle (no sources)");
        assertThat(tmp.resolve("scratch/lint/checkstyle/checkstyle.xml")).isEmptyFile();
    }

    /** The import names a rule set the module does not hold; the step is a warning row, not a failed build. */
    @Test
    void a_configuration_the_module_does_not_hold_is_a_warning_and_the_step_passes(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = lintModule(tmp, "error", SOURCE).config("checkstyle", "config/sun_checks.xml");

        LintStep.run(io, LintTool.CHECKSTYLE);

        assertThat(io.diagnostics())
                .containsExactly("warning: checkstyle: `config/sun_checks.xml` is not a file in the module, so"
                        + " nothing was linted — copy the rule set to that path, or point `[lint] checkstyle` at"
                        + " the file that holds it");
        assertThat(io.labels()).containsExactly("checkstyle (no configuration)");
        assertThat(tmp.resolve("scratch/lint/checkstyle/checkstyle.xml")).isEmptyFile();
    }

    /** SpotBugs runs over the compiled classes; a high-priority pattern is an error that fails the step. */
    @Test
    void spotbugs_reports_a_pattern_against_the_classes_and_a_high_priority_one_fails_the_step(@TempDir Path tmp)
            throws Exception {
        FakeBuildIo io = spotbugsModule(tmp, REF_COMPARISON);

        assertThatThrownBy(() -> LintStep.run(io, LintTool.SPOTBUGS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("spotbugs: 1 finding at or above `fail-on = \"error\"`");
        Path source = tmp.resolve("src/main/java/demo/Sample.java");
        assertThat(io.diagnostics())
                .singleElement()
                .asString()
                .startsWith("error: " + source + ":5: ")
                .endsWith("[RC_REF_COMPARISON]");
        assertThat(tmp.resolve("scratch/lint/spotbugs/spotbugs.xml")).isRegularFile();
        assertThat(io.labels()).containsExactly("spotbugs (1 root)");
    }

    @Test
    void spotbugs_over_clean_classes_has_no_finding(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = spotbugsModule(tmp, CLEAN);

        LintStep.run(io, LintTool.SPOTBUGS);

        assertThat(io.diagnostics()).isEmpty();
        assertThat(tmp.resolve("scratch/lint/spotbugs/spotbugs.xml")).isRegularFile();
    }

    /** Two source roots: PMD takes one {@code --dir} per root, detekt one comma-separated {@code --input}. */
    @Test
    void two_source_roots_are_spelled_the_way_each_tool_reads_them(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = new FakeBuildIo(tmp, "lint").config(Map.of("pmd", List.of("rulesets/java/quickstart.xml")));
        Path main = tmp.resolve("src/main/java");
        Path test = tmp.resolve("src/test/java");
        Path report = tmp.resolve("report.xml");

        List<String> pmd = LintStep.arguments(LintTool.PMD, io, List.of(main, test), report);
        List<String> detekt = LintStep.arguments(LintTool.DETEKT, io, List.of(main, test), report);

        assertThat(pmd).containsSequence("--dir", main.toString(), "--dir", test.toString());
        assertThat(pmd).doesNotContain(main + File.pathSeparator + test);
        assertThat(detekt).containsSequence("--input", main + "," + test);
    }

    /** Maven's default ruleset is not one of PMD's: jk carries it, and hands PMD a file under the step's output. */
    @Test
    void the_maven_pmd_plugins_default_ruleset_is_handed_to_pmd_as_a_file(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = new FakeBuildIo(tmp, "lint")
                .config(Map.of(
                        "pmd", List.of("rulesets/java/maven-pmd-plugin-default.xml", "category/java/security.xml")));
        Path report = Files.createDirectories(tmp.resolve("scratch/lint/pmd")).resolve("pmd.xml");

        List<String> pmd = LintStep.arguments(LintTool.PMD, io, List.of(tmp.resolve("src/main/java")), report);

        Path bundled = report.resolveSibling("maven-pmd-plugin-default.xml");
        assertThat(pmd).containsSequence("--rulesets", bundled + ",category/java/security.xml");
        assertThat(bundled)
                .content()
                .contains("Default Maven PMD Plugin Ruleset")
                .contains("UselessParentheses");
    }

    /** SpotBugs reports at medium confidence as the Maven plugin does by default; {@code spotbugs-threshold} lowers or raises it. */
    @Test
    void spotbugs_reports_at_the_tables_confidence_medium_by_default(@TempDir Path tmp) throws Exception {
        Path report = tmp.resolve("report.xml");
        FakeBuildIo byDefault = new FakeBuildIo(tmp.resolve("d"), "lint").config(Map.of("spotbugs", true));
        FakeBuildIo low =
                new FakeBuildIo(tmp.resolve("l"), "lint").config(Map.of("spotbugs", true, "spotbugs-threshold", "low"));

        assertThat(LintStep.arguments(LintTool.SPOTBUGS, byDefault, List.of(), report))
                .contains("-medium")
                .doesNotContain("-low");
        assertThat(LintStep.arguments(LintTool.SPOTBUGS, low, List.of(), report))
                .contains("-low");
    }

    /** {@code exclude} globs reach Checkstyle as {@code -x} path patterns and detekt as {@code --excludes}. */
    @Test
    void exclude_globs_are_passed_the_way_each_tool_leaves_paths_out(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = new FakeBuildIo(tmp, "lint")
                .config(Map.of(
                        "checkstyle",
                        "checkstyle.xml",
                        "detekt",
                        true,
                        "exclude",
                        List.of("**/grpc/auto/**", "**/istio/**")));
        Path main = tmp.resolve("src/main/java");
        Path report = tmp.resolve("report.xml");

        List<String> checkstyle = LintStep.arguments(LintTool.CHECKSTYLE, io, List.of(main), report);
        List<String> detekt = LintStep.arguments(LintTool.DETEKT, io, List.of(main), report);

        assertThat(checkstyle)
                .containsSequence("-x", LintStep.excludeRegex("**/grpc/auto/**"))
                .containsSequence("-x", LintStep.excludeRegex("**/istio/**"));
        assertThat(detekt).containsSequence("--excludes", "**/grpc/auto/**,**/istio/**");
    }

    @Test
    void an_exclude_glob_is_the_regex_checkstyle_finds_in_an_absolute_path() {
        String regex = LintStep.excludeRegex("**/api/grpc/auto/**");
        assertThat("/w/api/src/main/java/com/acme/api/grpc/auto/Stub.java").containsPattern(regex);
        assertThat("/w/api/src/main/java/com/acme/api/grpc/Client.java").doesNotContainPattern(regex);
        assertThat("/w/src/gen/Model.java").containsPattern(LintStep.excludeRegex("src/gen/*.java"));
        assertThat("/w/src/gen/deep/Model.java").doesNotContainPattern(LintStep.excludeRegex("src/gen/*.java"));
        assertThat("/w/src/Model.java.bak").doesNotContainPattern(LintStep.excludeRegex("src/Model.java"));
    }

    /**
     * SpotBugs reads the class files of the JDK it runs on, and a release older than that JDK
     * cannot: the step refuses such a pin before fetching anything, naming the floor for the build
     * JDK, so a project keeping an old spotbugs-maven-plugin learns the version to write.
     */
    @Test
    void a_spotbugs_release_below_the_build_jdks_floor_is_refused_with_the_floor_named(@TempDir Path tmp)
            throws Exception {
        FakeBuildIo io = new FakeBuildIo(tmp, "lint").config(Map.of("spotbugs", true, "spotbugs-version", "4.0.6"));
        int jdk = Runtime.version().feature();
        String floor = SpotBugsFloor.floorFor(jdk).orElseThrow();

        assertThatThrownBy(() -> LintStep.run(io, LintTool.SPOTBUGS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("SpotBugs 4.0.6 cannot read the class files of JDK " + jdk + ", which this step runs on;"
                        + " the oldest release that can is " + floor + ": set [lint] spotbugs-version = \"" + floor
                        + "\" or newer");
        assertThat(io.labels()).isEmpty();
    }

    @Test
    void exit_codes_after_a_completed_analysis_are_not_failures() {
        assertThat(LintStep.ran(LintTool.CHECKSTYLE, 3)).isTrue();
        assertThat(LintStep.ran(LintTool.PMD, 4)).isTrue();
        assertThat(LintStep.ran(LintTool.PMD, 1)).isFalse();
        assertThat(LintStep.ran(LintTool.SPOTBUGS, 3)).isTrue();
        assertThat(LintStep.ran(LintTool.SPOTBUGS, 4)).isFalse();
        assertThat(LintStep.ran(LintTool.DETEKT, 2)).isTrue();
        assertThat(LintStep.ran(LintTool.DETEKT, 3)).isFalse();
    }

    /** A module with the Checkstyle configuration at {@code severity}, one source, and Checkstyle's closure. */
    private static FakeBuildIo lintModule(Path tmp, String severity, String source) throws Exception {
        FakeBuildIo io = new FakeBuildIo(tmp, "lint").config(Map.of("checkstyle", "checkstyle.xml"));
        FakeBuildIo.write(tmp.resolve("checkstyle.xml"), CONFIG.formatted(severity));
        FakeBuildIo.write(tmp.resolve("src/main/java/demo/Sample.java"), source);
        return io.extra("checkstyle", closureJar(tmp, "checkstyle-closure.jar"));
    }

    /** A module with {@code spotbugs = true}, one source compiled into its classes dir, and SpotBugs's closure. */
    private static FakeBuildIo spotbugsModule(Path tmp, String source) throws Exception {
        FakeBuildIo io = new FakeBuildIo(tmp, "lint").config(Map.of("spotbugs", true));
        Path file = FakeBuildIo.write(tmp.resolve("src/main/java/demo/Sample.java"), source);
        Path classes = Files.createDirectories(tmp.resolve("classes"));
        JavaCompiler javac = Objects.requireNonNull(ToolProvider.getSystemJavaCompiler(), "a JDK runs this test");
        assertThat(javac.run(null, null, null, "-d", classes.toString(), file.toString()))
                .isZero();
        return io.classesDir(classes).extra("spotbugs", closureJar(tmp, "spotbugs-closure.jar"));
    }

    /**
     * This JVM's classpath as one jar whose manifest names every entry by absolute URL, the way
     * the engine hands a step a single tool jar.
     */
    private static Path closureJar(Path tmp, String name) throws Exception {
        StringBuilder classPath = new StringBuilder();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            Path path = Path.of(entry).toAbsolutePath();
            String url = path.toUri().toString();
            if (Files.isDirectory(path) && !url.endsWith("/")) url += "/";
            classPath.append(url).append(' ');
        }
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes()
                .put(Attributes.Name.CLASS_PATH, classPath.toString().strip());
        Path tool = Files.createDirectories(tmp.resolve("tool")).resolve(name);
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(tool), manifest)) {
            jar.flush();
        }
        return tool;
    }
}
