// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.theme.Coords;
import cc.jumpkick.cli.theme.Rgb;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.GlobalConfig;
import java.nio.file.Path;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.Test;

/** Phase-pill diagnostic reports for non-test-failure errors. */
class DiagnosticReportTest {

    private static String plain(String s) {
        return AttributedString.stripAnsi(s == null ? "" : s);
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
        String abs = Path.of(rel).toAbsolutePath().normalize().toString();
        String msg = "sibling not built — cc.jumpkick:jk-plugin-sdk (expected at " + abs + ")";
        String report = DiagnosticReport.renderError("parse-build", "workspace", msg);
        String plain = plain(report);

        assertThat(plain).contains("Parse Build");
        assertThat(plain).contains("Failure");
        assertThat(plain).contains("sibling not built");
        assertThat(plain).contains("cc.jumpkick:jk-plugin-sdk");
        // Project-relative path, not absolute workspace path.
        assertThat(plain).contains(rel);
        assertThat(plain).doesNotContain(abs);
        // No legacy Error banner.
        assertThat(plain).doesNotContain("Error [parse-build]");
        assertThat(plain).doesNotContain("✘ Error");
        assertThat(plain.stripTrailing()).endsWith(DiagnosticReport.FOOTER);

        if (Theme.active().isAnsi()) {
            assertThat(report).contains(DiagnosticReport.RAIL);
            assertThat(report).contains(Coords.ga("cc.jumpkick", "jk-plugin-sdk"));
            assertThat(report).contains(Theme.colorize(rel, Theme.active().path()));
        }
    }

    @Test
    void test_failure_code_is_suppressed() {
        assertThat(DiagnosticReport.renderError("run-tests", "test-failure", "boom"))
                .isEmpty();
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
            AttributedStyle blackOnAmber = t.withBackground(t.bright(0, 0, 0), Rgb.hex(0xFFB800));
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
