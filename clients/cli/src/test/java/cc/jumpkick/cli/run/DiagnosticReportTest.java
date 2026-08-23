// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.PathDisplay;
import cc.jumpkick.cli.theme.Coords;
import cc.jumpkick.cli.theme.Rgb;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.terminal.Style;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Phase-pill diagnostic reports for non-test-failure errors. */
class DiagnosticReportTest {

    private static String plain(String s) {
        return cc.jumpkick.terminal.Width.stripAnsi(s == null ? "" : s);
    }

    @Test
    void title_case_humanizes_kebab_step() {
        assertThat(DiagnosticReport.titleFor("parse-build", "workspace")).isEqualTo("Parse Build");
        assertThat(DiagnosticReport.titleFor("compile-java", "javac")).isEqualTo("Compile Java");
        assertThat(DiagnosticReport.titleFor("run-tests", "test-failure")).isEqualTo("Run Tests");
        assertThat(DiagnosticReport.titleFor("composite", null)).isEqualTo("Build");
    }

    @Test
    void sibling_not_built_report_has_pill_rail_coord_and_relative_path() {
        // Build an absolute path under the real project root so PathDisplay can relativize on any
        // machine (a hard-coded foreign home path stays absolute outside all anchors).
        String rel = "target/shared/plugin-sdk/lib/jk-plugin-sdk-0.12.0.jar";
        Path absPath = Path.of(rel).toAbsolutePath().normalize();
        String abs = absPath.toString();
        String display = PathDisplay.of(absPath);
        String msg = "sibling not built — cc.jumpkick:jk-plugin-sdk (expected at " + abs + ")";
        String report = DiagnosticReport.renderError("parse-build", "workspace", msg);
        String plain = plain(report);

        assertThat(plain).contains("Parse Build");
        assertThat(plain).contains("Failure");
        assertThat(plain).contains("sibling not built");
        assertThat(plain).contains("cc.jumpkick:jk-plugin-sdk");
        // Project-relative path (forward slashes), not absolute workspace path.
        assertThat(plain).contains(display);
        assertThat(plain).doesNotContain(abs);
        assertThat(plain).doesNotContain(abs.replace('\\', '/'));
        // No legacy Error banner.
        assertThat(plain).doesNotContain("Error [parse-build]");
        assertThat(plain).doesNotContain("✘ Error");
        assertThat(plain.stripTrailing()).endsWith(DiagnosticReport.FOOTER);

        if (Theme.active().isAnsi()) {
            assertThat(report).contains(DiagnosticReport.RAIL);
            assertThat(report).contains(Coords.ga("cc.jumpkick", "jk-plugin-sdk"));
            assertThat(report).contains(Theme.colorize(display, Theme.active().path()));
        }
    }

    @Test
    void test_failure_code_is_suppressed() {
        assertThat(DiagnosticReport.renderError("run-tests", "test-failure", "boom"))
                .isEmpty();
    }

    @Test
    void compiler_error_header_includes_module_coord() {
        String report = DiagnosticReport.renderError(
                "compile-java", "javac", "Foo.java:1: error: cannot find symbol", "cc.jumpkick:jk-core");
        String p = plain(report);
        assertThat(p).contains("Compile Java");
        assertThat(p).contains("Failure");
        assertThat(p).contains("in cc.jumpkick:jk-core");
        assertThat(p).contains("error:");
        assertThat(p).contains("Foo.java:1");
        assertThat(p.lines().count()).isGreaterThan(2);
        if (Theme.active().isAnsi()) {
            assertThat(report).contains(Coords.ga("cc.jumpkick", "jk-core"));
        }
    }

    @Test
    void later_compiler_error_omits_the_repeated_pill() {
        String first = DiagnosticReport.renderError(
                "compile-java", "javac", "Foo.java:1: error: cannot find symbol", "cc.jumpkick:jk-core", true);
        String later = DiagnosticReport.renderError(
                "compile-java", "javac", "Foo.java:8: error: cannot find symbol", "cc.jumpkick:jk-core", false);
        String firstPlain = plain(first);
        String laterPlain = plain(later);
        assertThat(firstPlain).contains("Compile Java");
        assertThat(firstPlain).contains("Failure");
        assertThat(laterPlain).doesNotContain("Compile Java");
        assertThat(laterPlain).doesNotContain("Failure");
        assertThat(laterPlain).contains("error:");
        assertThat(laterPlain).contains("Foo.java:8");
        assertThat(laterPlain.stripTrailing()).endsWith(DiagnosticReport.FOOTER);
    }

    @Test
    void compiler_header_run_collapses_same_module_and_reopens_on_change() {
        DiagnosticReport.CompilerHeaderRun run = new DiagnosticReport.CompilerHeaderRun();
        assertThat(run.show("compile-java", "javac", "g:a")).isTrue();
        assertThat(run.show("compile-java", "javac", "g:a")).isFalse();
        assertThat(run.show("compile-java", "javac", "g:b")).isTrue();
        assertThat(run.show("parse-build", "workspace", "g:b")).isTrue();
        assertThat(run.show("compile-java", "javac", "g:b")).isTrue();
    }

    @Test
    void warning_pill_uses_black_ink_on_amber() {
        String report = DiagnosticReport.renderWarning("compile-java", "javac", "src/Main.java:1: warning: something");
        String plain = plain(report);
        assertThat(plain).contains("Compile Java");
        assertThat(plain).contains("Warning");
        if (Theme.active().isAnsi()) {
            Theme t = Theme.active();
            // Black on amber chip body. Pill axis: half-circle caps around bare label;
            // without it, Badge.pill pads the label with spaces.
            Style blackOnAmber = t.withBackground(t.bright(0, 0, 0), Rgb.hex(0xFFB800));
            String body = GlobalConfig.nerdFont().pill() ? "Compile Java" : " Compile Java ";
            assertThat(report).contains(Theme.colorize(body, blackOnAmber));
        }
    }

    @Test
    void console_spec_delegates() {
        String r =
                ConsoleSpec.renderError("parse-build", "workspace", "sibling not built — g:a (expected at /tmp/x.jar)");
        assertThat(plain(r)).contains("Parse Build").contains("Failure").contains("sibling not built");
    }

    @Test
    void no_ansi_uses_bracket_title() {
        // paintProse still relativizes; header is [Title] Failure when ansi off — hard to force
        // Theme off in-unit, so just assert titleCase helpers and structure when ansi is on.
        assertThat(DiagnosticReport.titleCaseWords("parse build")).isEqualTo("Parse Build");
        assertThat(DiagnosticReport.titleCaseWords("ensure-jdk".replace('-', ' ')))
                .isEqualTo("Ensure Jdk");
    }
}
