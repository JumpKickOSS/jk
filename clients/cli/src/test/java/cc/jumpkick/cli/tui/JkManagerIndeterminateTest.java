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
 * preflight stage and the clock, and the taskbar is told the work is indeterminate.
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
        assertThat(waiting).contains("Checking cache…");
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
