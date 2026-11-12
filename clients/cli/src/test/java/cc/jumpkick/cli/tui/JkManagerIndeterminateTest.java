// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static cc.jumpkick.cli.tui.JkManagerTestSupport.stream;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.config.NerdFontCaps;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;

/**
 * Before the engine's estimate exists, a build's header draws no bar: the orb breathes beside the
 * preflight phase and the clock, and the taskbar is told the work is indeterminate. The stage
 * itself is the tree row below, so the header names the phase instead of repeating the row.
 */
class JkManagerIndeterminateTest {

    private static boolean drawsBar(String line) {
        return line.indexOf(ProgressBar.FULL_BLOCK) >= 0
                || line.indexOf(ProgressBar.FILLED_CHAR) >= 0
                || line.indexOf(ProgressBar.EMPTY_CHAR) >= 0
                || line.matches(".*\\d+%.*");
    }

    @Test
    void the_preflight_draws_no_bar_until_the_estimate_arrives() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdFont = NerdFontCaps.NONE;
        cm.estimating(true);
        cm.progress(35, 1000);
        cm.preflight("checking", 0, 0, "Checking cache…");

        assertThat(cm.indeterminate()).isTrue();
        String waiting = TestAnsi.strip(cm.renderBuildPlanLines(120, 300).get(0));
        assertThat(waiting).contains(HeaderProgress.PREFLIGHT_LABEL);
        assertThat(drawsBar(waiting))
                .as("no bar before the estimate: " + waiting)
                .isFalse();

        cm.setEtaEstimate(2_000);
        assertThat(cm.indeterminate()).isFalse();
        String estimated = TestAnsi.strip(cm.renderBuildPlanLines(120, 600).get(0));
        assertThat(drawsBar(estimated))
                .as("the bar once the estimate is in: " + estimated)
                .isTrue();
    }

    @Test
    void the_header_names_the_phase_and_leaves_the_stage_to_its_row() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdFont = NerdFontCaps.NONE;
        cm.estimating(true);
        cm.preflight("checking", 45, 61, "Checking cache (45/61)");

        var lines =
                cm.renderBuildPlanLines(120, 300).stream().map(TestAnsi::strip).toList();
        assertThat(lines.get(0)).contains(HeaderProgress.PREFLIGHT_LABEL).doesNotContain("Checking cache");
        assertThat(String.join("\n", lines.subList(1, lines.size()))).contains("Checking cache (45/61)");
    }

    @Test
    void a_preflight_outside_the_estimate_window_still_names_the_phase() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Sync", false);
        cm.nerdFont = NerdFontCaps.NONE;
        cm.preflight("sync", 0, 0, "Syncing dependencies…");

        var lines =
                cm.renderBuildPlanLines(120, 300).stream().map(TestAnsi::strip).toList();
        assertThat(lines.get(0)).contains(HeaderProgress.PREFLIGHT_LABEL).doesNotContain("Syncing dependencies");
        assertThat(String.join("\n", lines)).contains("Syncing dependencies…");
    }

    @Test
    void an_explicit_solve_label_still_paints_in_the_header() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Lock", false);
        cm.nerdFont = NerdFontCaps.NONE;
        cm.solveLabel("Resolving dependencies…");

        assertThat(TestAnsi.strip(cm.renderBuildPlanLines(120, 300).get(0))).contains("Resolving dependencies…");
    }

    @Test
    void a_build_that_leaves_its_preflight_without_an_estimate_gets_its_bar_back() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdFont = NerdFontCaps.NONE;
        cm.estimating(true);
        cm.progress(95, 1000);
        assertThat(cm.indeterminate()).isTrue();

        // A no-op: nothing to estimate, the engine moves straight to execute and finishes.
        cm.estimating(false);
        cm.progress(100, 100);
        assertThat(cm.indeterminate()).isFalse();
        assertThat(drawsBar(TestAnsi.strip(cm.renderBuildPlanLines(120, 600).get(0))))
                .isTrue();
    }

    @Test
    void a_command_without_a_workspace_preflight_keeps_its_bar() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Sync", false);
        cm.nerdFont = NerdFontCaps.NONE;
        cm.progress(40, 100);
        assertThat(cm.indeterminate()).isFalse();
        assertThat(drawsBar(TestAnsi.strip(cm.renderBuildPlanLines(120, 600).get(0))))
                .isTrue();
    }
}
