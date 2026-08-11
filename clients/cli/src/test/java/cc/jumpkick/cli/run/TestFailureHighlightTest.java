// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.theme.Coords;
import cc.jumpkick.cli.theme.Theme;
import java.util.List;
import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;

/** {@link TestFailureHighlight} turns the engine's plain failure block into a styled report. */
class TestFailureHighlightTest {

    private static String plain(String s) {
        return AttributedString.stripAnsi(s == null ? "" : s);
    }

    @Test
    void header_is_test_pill_plus_failure() {
        String h = TestFailureHighlight.paintHeader();
        assertThat(plain(h)).contains("Test").contains("Failure");
        if (Theme.active().isAnsi()) {
            assertThat(h).contains("\u001b");
            assertThat(h)
                    .contains(Theme.colorize("Failure", Theme.active().error().bold()));
        }
    }

    @Test
    void paints_full_block_with_rail_coords_and_assertion_colors() {
        List<String> raw = List.of(
                "",
                "Test Failure",
                "1 test failed:",
                "",
                "  FAILED  cc.jumpkick:jk-engine :: size_model_for_cli_like_app_tracks_reference_at_scale_one()",
                "    class: cc.jumpkick.runtime.NativeEffortTest",
                "    java.lang.AssertionError",
                "",
                "Expecting actual:",
                "  21670L",
                "to be between:",
                "  [28000L, 45000L]",
                ""); // trailing blank must be stripped so the settle wedge sits tight
        List<String> painted = TestFailureHighlight.paintLines(raw);
        String all = String.join(
                "\n", painted.stream().map(TestFailureHighlightTest::plain).toList());

        assertThat(all).contains("Test");
        assertThat(all).contains("Failure");
        assertThat(all).contains("1 test failed:");
        assertThat(all).contains("FAILED");
        assertThat(all).contains("cc.jumpkick:jk-engine");
        assertThat(all).contains("size_model_for_cli_like_app_tracks_reference_at_scale_one()");
        assertThat(all).contains("class: cc.jumpkick.runtime.NativeEffortTest");
        assertThat(all).contains("21670L");
        assertThat(all).contains("[28000L, 45000L]");
        // Closes with heavy rail footer; no trailing blank after it.
        assertThat(plain(painted.getLast()).strip()).isEqualTo(DiagnosticReport.FOOTER);
        assertThat(all).contains("[28000L, 45000L]");
        // No redundant Error banner.
        assertThat(all).doesNotContain("Error [run-tests");

        if (Theme.active().isAnsi()) {
            Theme t = Theme.active();
            // Rail on body lines
            assertThat(painted.stream()
                            .filter(l -> l != null && l.contains(TestFailureHighlight.RAIL))
                            .count())
                    .isGreaterThan(3);
            // Coord uses theme segments
            assertThat(String.join("", painted)).contains(Coords.ga("cc.jumpkick", "jk-engine"));
            // Actual red, expected green
            assertThat(String.join("", painted)).contains(Theme.colorize("21670L", t.error()));
            assertThat(String.join("", painted)).contains(Theme.colorize("[28000L, 45000L]", t.success()));
        }
    }

    @Test
    void non_failure_output_passes_through_stack_highlight() {
        List<String> raw = List.of("Note: something", "\tat cc.jumpkick.Foo.bar(Foo.java:1)");
        List<String> painted = TestFailureHighlight.paintLines(raw);
        assertThat(plain(painted.get(0))).isEqualTo("Note: something");
        assertThat(plain(painted.get(1))).isEqualTo("\tat cc.jumpkick.Foo.bar(Foo.java:1)");
    }
}
