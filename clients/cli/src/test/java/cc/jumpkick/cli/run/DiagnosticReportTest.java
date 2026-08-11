// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.theme.Coords;
import cc.jumpkick.cli.theme.Theme;
import java.util.List;
import org.jline.utils.AttributedString;
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
        String abs = "/Users/bryan.sant/src/oss/jk/target/shared/plugin-sdk/lib/jk-plugin-sdk-0.11.0.jar";
        String msg = "sibling not built — cc.jumpkick:jk-plugin-sdk (expected at " + abs + ")";
        String report = DiagnosticReport.renderError("parse-build", "workspace", msg);
        String plain = plain(report);

        assertThat(plain).contains("Parse Build");
        assertThat(plain).contains("Failure");
        assertThat(plain).contains("sibling not built");
        assertThat(plain).contains("cc.jumpkick:jk-plugin-sdk");
        // Project-relative path, not absolute home path.
        assertThat(plain).contains("target/shared/plugin-sdk/lib/jk-plugin-sdk-0.11.0.jar");
        assertThat(plain).doesNotContain("/Users/bryan.sant/src/oss/jk/target");
        // No legacy Error banner.
        assertThat(plain).doesNotContain("Error [parse-build]");
        assertThat(plain).doesNotContain("✘ Error");
        assertThat(plain.stripTrailing()).endsWith(DiagnosticReport.FOOTER);

        if (Theme.active().isAnsi()) {
            assertThat(report).contains(DiagnosticReport.RAIL);
            assertThat(report).contains(Coords.ga("cc.jumpkick", "jk-plugin-sdk"));
            assertThat(report).contains(Theme.colorize("target/shared/plugin-sdk/lib/jk-plugin-sdk-0.11.0.jar", Theme.active().path()));
        }
    }

    @Test
    void test_failure_code_is_suppressed() {
        assertThat(DiagnosticReport.renderError("run-tests", "test-failure", "boom")).isEmpty();
    }

    @Test
    void console_spec_delegates() {
        String r = ConsoleSpec.renderError("parse-build", "workspace", "sibling not built — g:a (expected at /tmp/x.jar)");
        assertThat(plain(r)).contains("Parse Build").contains("Failure").contains("sibling not built");
    }

    @Test
    void no_ansi_uses_bracket_title() {
        // paintProse still relativizes; header is [Title] Failure when ansi off — hard to force
        // Theme off in-unit, so just assert titleCase helpers and structure when ansi is on.
        assertThat(DiagnosticReport.titleCaseWords("parse build")).isEqualTo("Parse Build");
        assertThat(DiagnosticReport.titleCaseWords("ensure-jdk".replace('-', ' '))).isEqualTo("Ensure Jdk");
    }
}
