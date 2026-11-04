// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static cc.jumpkick.cli.tui.JkManagerTestSupport.stream;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.testing.Capture;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The settle envelope of the JkManager component. */
class JkManagerTest {

    @Test
    void animated_goal_settle_wipes_region_then_prints_deferred_above_summary() {
        var buf = new ByteArrayOutputStream();
        var cm = new JkManager(stream(buf), true, 80); // animate + plan mode
        cm.progress(2, 4);
        cm.stepRunning("m", "compile");
        cm.tick(); // paint the live region
        buf.reset();

        cm.finishSuccess("built 17 modules", List.of("‼ Warning [compile-test]:"));

        String visible = TestAnsi.strip(buf.toString(StandardCharsets.UTF_8));
        int warn = visible.indexOf("‼ Warning [compile-test]:");
        int summary = visible.indexOf("Successful: built 17 modules");
        // Region is wiped, then the deferred warning, then the summary line last.
        assertThat(warn).isGreaterThanOrEqualTo(0);
        assertThat(summary).isGreaterThan(warn);
    }

    @Test
    void non_animated_mode_prints_only_the_result_line() {
        var buf = new ByteArrayOutputStream();
        var cm = new JkManager(stream(buf), false, 80); // pipe / --quiet
        cm.name = "Locking";
        cm.tick(); // animator never runs; harmless if poked
        cm.finishSuccess("done");

        String raw = buf.toString(StandardCharsets.UTF_8);
        assertThat(TestAnsi.strip(raw)).contains("✓ Locking Successful: done");
        assertThat(raw).doesNotContain("\033[?25h"); // never hid the cursor
    }

    @Test
    void finish_is_idempotent() {
        var buf = new ByteArrayOutputStream();
        var cm = new JkManager(stream(buf), true, 80);
        cm.name = "Building";
        cm.finishSuccess("Built x");
        buf.reset();
        cm.finishFailure("ignored");
        cm.close();
        assertThat(buf.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void progress_is_monotonic_and_never_slides_backward() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.progress(50, 100);
        assertThat(cm.numerator()).isEqualTo(50);
        // A later denominator growth drops the raw fraction (30/100 < 50/100); the
        // bar must hold at the peak, so the numerator is clamped up to 50/100.
        cm.progress(30, 100);
        assertThat(cm.numerator()).isEqualTo(50);
        // Real forward progress past the peak is honoured.
        cm.progress(70, 100);
        assertThat(cm.numerator()).isEqualTo(70);
    }

    @Test
    void settle_prints_leading_blank_only() {
        CliOutput.beginCommand(false);
        var buf = new ByteArrayOutputStream();
        var cm = JkManager.plan(stream(buf), "Build", false);
        cm.finishSuccess("ok took 1s");
        String out = Capture.lf(buf.toString(StandardCharsets.UTF_8));
        // Leading blank at construct; settle does not close the envelope (dispatch does).
        assertThat(out).startsWith("\n");
        assertThat(out).doesNotEndWith("\n\n");
        assertThat(out).endsWith("\n");
        assertThat(out).contains("ok took 1s");
    }

    @Test
    void exec_handoff_settle_has_no_trailing_blank() {
        CliOutput.beginCommand(false);
        var buf = new ByteArrayOutputStream();
        var cm = JkManager.plan(stream(buf), "Run", false);
        cm.finishBuildPlanExec("Executing `java -cp … Main`");
        String out = Capture.lf(buf.toString(StandardCharsets.UTF_8));
        assertThat(out).startsWith("\n");
        assertThat(out).doesNotEndWith("\n\n");
        assertThat(out).endsWith("\n");
        assertThat(out).contains("Executing");
    }

    @Test
    void prep_envelope_then_command_manager_does_not_double_blank() {
        CliOutput.beginCommand(false);
        var buf = new ByteArrayOutputStream();
        var ps = stream(buf);
        CommandWedge.envelopeStart(ps); // e.g. EnsureFreshLock / analyzing
        var cm = JkManager.plan(ps, "Build", false);
        cm.finishBuildPlanSuccess("built");
        String out = Capture.lf(buf.toString(StandardCharsets.UTF_8));
        // Exactly one leading blank for the whole command, not two.
        assertThat(out).startsWith("\n");
        assertThat(out).doesNotStartWith("\n\n");
        assertThat(out).contains("built");
    }
}
