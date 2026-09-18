// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.plugin.testing.FakeBuildIo;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The step body over the shared engine fake and a real fork of Checkstyle (its closure is this
 * test's classpath): a violation reaches the diagnostics with its file, line and rule id, its
 * severity decides the step's fate under {@code fail-on}, the report lands in the declared
 * output, and a clean source is a step with no finding.
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

    /**
     * A module with the Checkstyle configuration at {@code severity}, one source, and the tool's
     * closure as this JVM's classpath — handed over as one jar whose manifest names every entry
     * by absolute URL, the way the engine hands a step a single tool jar.
     */
    private static FakeBuildIo lintModule(Path tmp, String severity, String source) throws Exception {
        FakeBuildIo io = new FakeBuildIo(tmp, "lint").config(Map.of("checkstyle", "checkstyle.xml"));
        FakeBuildIo.write(tmp.resolve("checkstyle.xml"), CONFIG.formatted(severity));
        FakeBuildIo.write(tmp.resolve("src/main/java/demo/Sample.java"), source);
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
        Path tool = Files.createDirectories(tmp.resolve("tool")).resolve("checkstyle-closure.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(tool), manifest)) {
            jar.flush();
        }
        return io.extra("checkstyle", tool);
    }
}
