// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.theme.Rgb;
import cc.jumpkick.cli.theme.Theme;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.Test;

/** Simple-task mode of the JkManager component. */
class JkManagerTest {

    @Test
    void tick_renders_first_glyph_and_verb() {
        var buf = new ByteArrayOutputStream();
        var cm = new JkManager(stream(buf), true);
        cm.label("Locking");
        cm.tick(); // frame 0 = pulse circle
        assertThat(TestAnsi.strip(buf.toString(StandardCharsets.UTF_8))).contains(Spinner.PULSE_GLYPH + " Locking…");
    }

    @Test
    void finish_success_freezes_spinner_then_prints_green_check_line() {
        var buf = new ByteArrayOutputStream();
        var cm = new JkManager(stream(buf), true);
        cm.label("Syncing");
        cm.finishSuccess("Finished syncing 13 artifacts");

        String raw = buf.toString(StandardCharsets.UTF_8);
        String visible = TestAnsi.strip(raw);
        // Frozen pulse circle + command on its own line, result line below.
        assertThat(visible).contains(Spinner.PULSE_GLYPH + " Syncing…");
        // "✓ <plan> Successful: <message>", head in green.
        assertThat(visible).contains("✓ Syncing Successful: Finished syncing 13 artifacts");
        assertThat(raw)
                .contains(Theme.colorize("✓ Syncing Successful", Theme.active().success()));
        assertThat(raw).contains("\033[?25h"); // cursor restored
    }

    @Test
    void finish_success_prints_deferred_output_above_the_summary_line() {
        var buf = new ByteArrayOutputStream();
        var cm = new JkManager(stream(buf), false); // pipe / --quiet
        cm.label("Build");
        cm.finishSuccess("built 17 modules", List.of("‼ Warning [compile-test]:", "  deprecation in Foo.java"));

        String visible = TestAnsi.strip(buf.toString(StandardCharsets.UTF_8));
        int warn = visible.indexOf("‼ Warning [compile-test]:");
        int summary = visible.indexOf("✓ Build Successful: built 17 modules");
        // Subprocess output prints first; the success summary is the last thing shown.
        assertThat(warn).isGreaterThanOrEqualTo(0);
        assertThat(summary).isGreaterThan(warn);
        assertThat(visible.indexOf("deprecation in Foo.java")).isBetween(warn, summary);
    }

    @Test
    void animated_goal_settle_wipes_region_then_prints_deferred_above_summary() {
        var buf = new ByteArrayOutputStream();
        var cm = new JkManager(stream(buf), true, true, 80); // animate + plan mode
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
    void finish_failure_prints_red_failure_marker_line() {
        var buf = new ByteArrayOutputStream();
        var cm = new JkManager(stream(buf), true);
        cm.label("Syncing");
        cm.finishFailure("Failed to sync remote artifacts");

        String raw = buf.toString(StandardCharsets.UTF_8);
        assertThat(TestAnsi.strip(raw)).contains("✘ Failed to sync remote artifacts");
        assertThat(raw).contains(Theme.colorize("✘", Theme.active().error()));
    }

    @Test
    void render_canceled_settles_spinner_without_printing_the_cancel_notice() {
        var buf = new ByteArrayOutputStream();
        var cm = new JkManager(stream(buf), true);
        cm.label("Locking");
        cm.renderCanceled();

        String visible = TestAnsi.strip(buf.toString(StandardCharsets.UTF_8));
        assertThat(visible).contains(Spinner.PULSE_GLYPH + " Locking…");
        // The notice itself is GlobalCancel's job; the component only settles.
        assertThat(visible).doesNotContain("cancelled");
        assertThat(buf.toString(StandardCharsets.UTF_8)).contains("\033[?25h");
        // Simple (non-plan) mode still supplies the generic cancel text for the notice.
        assertThat(cm.canceledMessage()).isEqualTo("Build job was cancelled");
    }

    @Test
    void non_animated_mode_prints_only_the_result_line() {
        var buf = new ByteArrayOutputStream();
        var cm = new JkManager(stream(buf), false); // pipe / --quiet
        cm.label("Locking");
        cm.tick(); // animator never runs; harmless if poked
        cm.finishSuccess("done");

        String raw = buf.toString(StandardCharsets.UTF_8);
        assertThat(TestAnsi.strip(raw)).contains("✓ Locking Successful: done");
        assertThat(TestAnsi.strip(raw)).doesNotContain("Locking…"); // no spinner line
        assertThat(raw).doesNotContain("\033[?25h"); // never hid the cursor
    }

    @Test
    void finish_is_idempotent() {
        var buf = new ByteArrayOutputStream();
        var cm = new JkManager(stream(buf), true);
        cm.label("Building");
        cm.finishSuccess("Built x");
        buf.reset();
        cm.finishFailure("ignored");
        cm.close();
        assertThat(buf.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    // --- plan-oriented mode ----------------------------------------------

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
        CommandWedge.resetEnvelope();
        var buf = new ByteArrayOutputStream();
        var cm = JkManager.plan(stream(buf), "Build", false);
        cm.finishSuccess("ok took 1s");
        String out = buf.toString(StandardCharsets.UTF_8);
        // Leading blank at construct; settle line is last (no trailing blank before prompt).
        assertThat(out).startsWith("\n");
        assertThat(out).doesNotEndWith("\n\n");
        assertThat(out).endsWith("\n");
        assertThat(out).contains("ok took 1s");
    }

    @Test
    void exec_handoff_settle_has_no_trailing_blank() {
        CommandWedge.resetEnvelope();
        var buf = new ByteArrayOutputStream();
        var cm = JkManager.plan(stream(buf), "Run", false);
        cm.finishBuildPlanExec("Executing `java -cp … Main`");
        String out = buf.toString(StandardCharsets.UTF_8);
        assertThat(out).startsWith("\n");
        assertThat(out).doesNotEndWith("\n\n");
        assertThat(out).endsWith("\n");
        assertThat(out).contains("Executing");
    }

    @Test
    void prep_envelope_then_command_manager_does_not_double_blank() {
        CommandWedge.resetEnvelope();
        var buf = new ByteArrayOutputStream();
        var ps = stream(buf);
        CommandWedge.envelopeStart(ps); // e.g. EnsureFreshLock / analyzing
        var cm = JkManager.plan(ps, "Build", false);
        cm.finishBuildPlanSuccess("built");
        String out = buf.toString(StandardCharsets.UTF_8);
        // Exactly one leading blank for the whole command, not two.
        assertThat(out).startsWith("\n");
        assertThat(out).doesNotStartWith("\n\n");
        assertThat(out).contains("built");
    }

    @Test
    void header_shows_a_wallclock_countdown_from_the_estimate() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdfont = false;
        cm.progress(50, 100);

        // No estimate set → single mid-gray count-up from construction.
        String up = cm.renderBuildPlanLines(120, 4_000).get(0);
        assertThat(TestAnsi.strip(up)).contains("+4s");

        // Seeded with a 60s estimate: dual clock — countdown ~56s + elapsed +4s.
        cm.setEtaEstimate(60_000);
        String header = cm.renderBuildPlanLines(120, 4_000).get(0);
        String plain = TestAnsi.strip(header);
        assertThat(plain).contains("ETA ~56s");
        assertThat(plain).contains("+4s");
        // Countdown is mid-gray with tilde; count-up is dim while remaining > 0.
        assertThat(header).contains(Theme.colorize("~56s", Theme.active().midGray()));
        assertThat(header).contains(Theme.colorize("+4s", Theme.active().darkGray()));
        assertThat(header).contains(Theme.colorize("·", Theme.active().darkGray()));
    }

    @Test
    void open_loop_bar_tracks_elapsed_over_R0_not_weight_slices() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.setEtaEstimate(100_000); // R0 = 100s; residual starts at R0
        assertThat(cm.activeProgressStrategy().id()).isEqualTo("clock");
        // Weight path would claim 50% immediately; adaptive: 30s/(30s+70s residual) after residual update.
        cm.progress(50, 100);
        cm.setBarResidualRemaining(70_000);
        long[] at30 = cm.displayBar(30_000);
        assertThat(at30[1]).isEqualTo(1000);
        assertThat(at30[0]).isEqualTo(300); // 30%
        // Residual shrinks → bar speeds up at same elapsed.
        cm.setBarResidualRemaining(10_000);
        long[] sped = cm.displayBar(30_000);
        assertThat(sped[0]).isEqualTo(750); // 30/(30+10)
        // Cap at 99% while still running (residual 0, long elapsed).
        cm.setBarResidualRemaining(0);
        long[] over = cm.displayBar(200_000);
        assertThat(over[0]).isEqualTo(990);
    }

    @Test
    void open_loop_bar_never_goes_backwards() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.setEtaEstimate(100_000);
        cm.setBarResidualRemaining(0); // residual 0 at 40s → ~99% (capped)
        long[] a = cm.displayBar(40_000);
        assertThat(a[0]).isEqualTo(990);
        // Peak hold if residual suddenly grows (would otherwise drop fill).
        cm.setBarResidualRemaining(200_000);
        long[] b = cm.displayBar(40_000);
        assertThat(b[0]).isGreaterThanOrEqualTo(990);
    }

    @Test
    void without_r0_auto_uses_weighted_strategy() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        assertThat(cm.activeProgressStrategy().id()).isEqualTo("weighted");
        cm.progress(50, 100);
        long[] d = cm.displayBar(0);
        assertThat(d[0]).isEqualTo(50);
        assertThat(d[1]).isEqualTo(100);
    }

    @Test
    void eta_countdown_freezes_at_zero_and_count_up_promotes_after_grace() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdfont = false;
        cm.setEtaEstimate(10_000); // 10s estimate
        Theme t = Theme.active();

        // Just past deadline: countdown at dim 0s; count-up still dim (grace window).
        String early = cm.renderBuildPlanLines(120, 11_000).get(0);
        assertThat(TestAnsi.strip(early)).contains("ETA 0s").contains("+11s");
        assertThat(early).contains(Theme.colorize("0s", t.darkGray()));
        assertThat(early).contains(Theme.colorize("+11s", t.darkGray()));
        assertThat(early).doesNotContain(Theme.colorize("+11s", t.midGray()));

        // Past the 2s grace → count-up mid-gray (countdown's former color). Rendered at +3s
        // past deadline, not the exact 2 000 ms boundary: the seed's set-at base is real wall
        // clock (nanoTime since plan()), so an exact-boundary assertion flaked whenever ≥1 ms
        // elapsed between plan() and setEtaEstimate() (JK-1824). The strictly->= boundary
        // itself is covered by the clock-injected sibling test.
        String promoted = cm.renderBuildPlanLines(120, 13_000).get(0);
        assertThat(TestAnsi.strip(promoted)).contains("ETA 0s").contains("+13s");
        assertThat(promoted).contains(Theme.colorize("+13s", t.midGray()));
        assertThat(promoted).doesNotContain(Theme.colorize("+13s", t.warning()));
    }

    @Test
    void dual_clock_ticks_together_even_when_seed_is_not_second_aligned() {
        // Independent floor(remainingMs) vs floor(elapsedMs) desynced the two faces by the seed's
        // sub-second remainder (e.g. 100ms after setRemainingWorkEstimate). Both must advance on
        // the same whole-second elapsed boundary.
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdfont = false;
        cm.setEtaEstimate(60_100); // 60s + 100ms
        String mid = TestAnsi.strip(cm.renderBuildPlanLines(120, 4_050).get(0));
        assertThat(mid).contains("ETA ~56s");
        assertThat(mid).contains("+4s");
        // Still the same pair just under the next second (old code would drop countdown here).
        String justBefore = TestAnsi.strip(cm.renderBuildPlanLines(120, 4_999).get(0));
        assertThat(justBefore).contains("ETA ~56s");
        assertThat(justBefore).contains("+4s");
        // One paint advances both faces.
        String next = TestAnsi.strip(cm.renderBuildPlanLines(120, 5_000).get(0));
        assertThat(next).contains("ETA ~55s");
        assertThat(next).contains("+5s");
    }

    @Test
    void eta_seed_may_refine_before_any_module_finishes() {
        // Early shape seed then post-prepare reseed — both before execute — may update the total.
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdfont = false;
        cm.setEtaEstimate(60_000);
        cm.setEtaEstimate(38_000); // post-prepare refine while modulesComplete == 0
        // 4s elapsed → 34s remaining from the refined seed.
        assertThat(TestAnsi.strip(cm.renderBuildPlanLines(120, 4_000).get(0))).contains("ETA ~34s");
    }

    @Test
    void remaining_work_seed_adds_elapsed_so_countdown_matches_explain() {
        // Engine reports remaining work (same figure as jk explain). After 30s of lock, a 90s
        // remaining estimate must show ~90s left — not 60s (which would finish 30s early).
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdfont = false;
        // Simulate 30s already elapsed by using setEtaEstimate with elapsed+remaining directly
        // via setRemainingWorkEstimate after construction; render at that elapsed.
        // We can't freeze elapsedMillis, so set total = 30s + 90s and render at 30s.
        cm.setEtaEstimate(30_000 + 90_000);
        String at30 = TestAnsi.strip(cm.renderBuildPlanLines(120, 30_000).get(0));
        assertThat(at30).contains("ETA ~1m 30s");
        assertThat(at30).contains("+30s");
        // At end of remaining work (elapsed 120s) → frozen 0s + mid-gray full elapsed.
        String done = TestAnsi.strip(cm.renderBuildPlanLines(120, 120_000).get(0));
        assertThat(done).contains("ETA 0s");
        assertThat(done).contains("+2m 00s");
    }

    @Test
    void seed_path_locks_after_execute_but_residual_reanchors_countdown() {
        // R0 seed path freezes once a module completes (provisional eta thrash guard). Live
        // residual still re-anchors the countdown so ETA eases into R(t) and ends on time.
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdfont = false;
        cm.setEtaEstimate(38_000); // R0
        cm.setModuleProgress(1, 2); // locks seed path
        cm.setEtaEstimate(5_000); // seed-path rewrite — ignored
        // Without residual: open-loop 38s seed at 10s elapsed → ~28s.
        String openLoop = TestAnsi.strip(cm.renderBuildPlanLines(120, 10_000).get(0));
        assertThat(openLoop).contains("ETA ~28s");
        // Same-second residual re-anchor is held by the 1s jitter buffer (still ~28s).
        cm.setBarResidualRemaining(20_000);
        String held = TestAnsi.strip(cm.renderBuildPlanLines(120, 10_000).get(0));
        assertThat(held).contains("ETA ~28s");
        // Next whole second samples the latest residual: 20s re-anchor at ~0 → ~9s at 11s elapsed.
        String mid = TestAnsi.strip(cm.renderBuildPlanLines(120, 11_000).get(0));
        assertThat(mid).contains("ETA ~9s");
        assertThat(mid).contains("+11s");
        // Residual 0 snaps to 0s immediately (end on time — no 1s hold on zero).
        cm.setBarResidualRemaining(0);
        String done = TestAnsi.strip(cm.renderBuildPlanLines(120, 40_000).get(0));
        assertThat(done).contains("ETA 0s");
        assertThat(done).contains("+40s");
    }

    @Test
    void same_second_reanchor_overwrites_a_committed_zero() {
        // Snap-to-zero commits instantly; a residual raise in the SAME second must repaint
        // instead of holding 0s and bouncing 0s → Ns at the next second (JK-1850).
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdfont = false;
        cm.setEtaEstimate(30_000);
        cm.setModuleProgress(1, 2);
        String zero = TestAnsi.strip(cm.renderBuildPlanLines(120, 40_000).get(0));
        assertThat(zero).contains("ETA 0s");
        cm.startNanos = System.nanoTime() - 40_000_000_000L; // re-anchor lands at ~40s elapsed
        cm.setBarResidualRemaining(15_000);
        String raised = TestAnsi.strip(cm.renderBuildPlanLines(120, 40_000).get(0));
        assertThat(raised).contains("ETA ~15s");
    }

    @Test
    void identical_residual_reemits_do_not_reanchor_the_countdown() {
        // Preflight ticks force-emit the unchanged seed residual every ~500 ms; each emit used to
        // reset the anchor, so the countdown displayed a constant R0 for the whole prepare window
        // instead of the promised open-loop decay (JK-1843).
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdfont = false;
        cm.setEtaEstimate(30_000); // R0 seed anchors residual at ~0 elapsed
        cm.setModuleProgress(1, 2); // execute locks the seed path
        cm.startNanos = System.nanoTime() - 10_000_000_000L; // wall clock: ~10s into the run
        cm.setBarResidualRemaining(30_000); // identical re-emit — must NOT re-anchor
        String held = TestAnsi.strip(cm.renderBuildPlanLines(120, 10_000).get(0));
        assertThat(held).contains("ETA ~20s"); // decayed from the ORIGINAL anchor, not frozen at 30s
        // A genuinely new residual still re-anchors: 25s at ~10s elapsed → ~24s a second later.
        cm.setBarResidualRemaining(25_000);
        String reanchored = TestAnsi.strip(cm.renderBuildPlanLines(120, 11_000).get(0));
        assertThat(reanchored).contains("ETA ~24s");
    }

    @Test
    void residual_speeds_up_countdown_when_work_finishes_early() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdfont = false;
        cm.setEtaEstimate(100_000); // R0 = 100s
        cm.setModuleProgress(1, 3);
        // Residual re-anchor near t=0 with 20s left (work finishing early). First paint samples it.
        // Open-loop R0 at 10s would still show ~90s; residual-anchored shows ~10s.
        cm.setBarResidualRemaining(20_000);
        String header = TestAnsi.strip(cm.renderBuildPlanLines(120, 10_000).get(0));
        assertThat(header).contains("ETA ~10s");
        assertThat(header).contains("+10s");
        // Bar also speeds up from residual (raw residual, not wall-decayed).
        long[] bar = cm.displayBar(10_000);
        assertThat(bar[0]).isEqualTo(333); // 10/(10+20)
    }

    @Test
    void countdown_jitter_buffer_samples_latest_target_once_per_second() {
        // Residual may thrash several times inside one whole second; the painted face holds the
        // first sample for that second, then commits the latest target on the next elapsedSec.
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdfont = false;
        cm.setEtaEstimate(60_000); // R0 = 60s
        // First paint at +4s samples open-loop ~56s.
        assertThat(TestAnsi.strip(cm.renderBuildPlanLines(120, 4_000).get(0))).contains("ETA ~56s");
        // Three residual re-anchors inside the same second — face must not thrash.
        cm.setBarResidualRemaining(40_000);
        cm.setBarResidualRemaining(25_000);
        cm.setBarResidualRemaining(12_000);
        assertThat(TestAnsi.strip(cm.renderBuildPlanLines(120, 4_100).get(0))).contains("ETA ~56s");
        assertThat(TestAnsi.strip(cm.renderBuildPlanLines(120, 4_900).get(0))).contains("ETA ~56s");
        // Next second samples the latest residual (12s at ~0 wall → ~7s at +5s).
        assertThat(TestAnsi.strip(cm.renderBuildPlanLines(120, 5_000).get(0))).contains("ETA ~7s");
        // Open-loop decay of that residual on the following second (no new residual).
        assertThat(TestAnsi.strip(cm.renderBuildPlanLines(120, 6_000).get(0))).contains("ETA ~6s");
    }

    @Test
    void provisional_lock_window_seed_is_replaced_by_the_real_forecast_seed() {
        // Stale-lock builds get a coarse provisional ETA before preflight. Preflight progress
        // events (clock strategy active, work model published) must NOT freeze it: the real
        // post-forecast seed replaces it, and only execute activity locks (JK-1806).
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdfont = false;
        cm.setEtaEstimate(138_000); // provisional: lockEta + history prior
        cm.progress(100, 1000); // preflight band workspace-progress with R0 seeded
        cm.setModuleProgress(0, 4); // work model publishes modulesTotal before the real seed
        cm.setEtaEstimate(26_000); // real post-forecast seed must win
        assertThat(TestAnsi.strip(cm.renderBuildPlanLines(120, 6_000).get(0))).contains("ETA ~20s");
        // First module task starting freezes the seed; later rewrites are ignored.
        cm.stepRunning("app", "compile", "compile");
        cm.setEtaEstimate(90_000);
        assertThat(TestAnsi.strip(cm.renderBuildPlanLines(120, 6_000).get(0))).contains("ETA ~20s");
    }

    @Test
    void cold_count_up_is_run_wide_until_a_remaining_seed_arrives() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdfont = false;
        // No seed → +elapsed for the whole command.
        assertThat(TestAnsi.strip(cm.renderBuildPlanLines(120, 12_000).get(0))).contains("+12s");
        // Positive remaining seeds the dual clock (R0=30s at apply time ≈ elapsed 0).
        cm.setEtaEstimate(30_000);
        String seeded = TestAnsi.strip(cm.renderBuildPlanLines(120, 12_000).get(0));
        assertThat(seeded).contains("ETA ~18s");
        assertThat(seeded).contains("+12s");
        // Zero before lock is ignored (unknown clear) — seed remains open-loop.
        cm.setEtaEstimate(0);
        String still = TestAnsi.strip(cm.renderBuildPlanLines(120, 12_000).get(0));
        assertThat(still).contains("ETA ~18s");
        assertThat(still).contains("+12s");
    }

    @Test
    void header_countdown_has_dim_eta_prefix_and_no_module_counter() {
        // Dual clock: dim italic "ETA " + ~remaining · +elapsed; module n/m lives on tree rows only.
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdfont = false;
        cm.progress(50, 100);
        cm.setEtaEstimate(60_000);
        cm.setModuleProgress(2, 8);
        String header = TestAnsi.strip(cm.renderBuildPlanLines(120, 4_000).get(0));
        assertThat(header).contains("ETA ~56s");
        assertThat(header).contains("+4s");
        assertThat(header).doesNotContain("2/8");
        // Cold count-up has no ETA prefix.
        String cold = TestAnsi.strip(JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false)
                .renderBuildPlanLines(120, 12_000)
                .get(0));
        assertThat(cold).contains("+12s");
        assertThat(cold).doesNotContain("ETA ");
    }

    @Test
    void setWindowTitle_emits_osc0_and_clears_on_settle() {
        var buf = new ByteArrayOutputStream();
        var cm = JkManager.plan(stream(buf), "Build", true);
        cm.setWindowTitle("JumpKick - Building cc.jumpkick:jk:0.12.0...");
        String set = buf.toString(StandardCharsets.UTF_8);
        // OSC 0: fill-circle glyph + base, terminated with ST (ESC \), not BEL.
        String expected = "\033]0;" + Spinner.fillGlyph(0) + " JumpKick - Building cc.jumpkick:jk:0.12.0...\033\\";
        assertThat(set).contains(expected);
        buf.reset();
        cm.finishBuildPlanSuccess("ok", List.of());
        String cleared = buf.toString(StandardCharsets.UTF_8);
        assertThat(cleared).contains("\033]0;\033\\");
    }

    @Test
    void window_title_never_reaches_non_animated_output() {
        // Piped / CI / --quiet builds (animate=false) must stay byte-clean of OSC — the
        // escapes would land verbatim in the redirected stream.
        var buf = new ByteArrayOutputStream();
        var cm = JkManager.plan(stream(buf), "Build", false);
        cm.setWindowTitle("JumpKick - Building g:a:v...");
        cm.addTask("g:a", "compile-main");
        cm.stepDone("g:a", "compile-main", true);
        cm.tick();
        cm.finishBuildPlanSuccess("ok", List.of());
        assertThat(buf.toString(StandardCharsets.UTF_8)).doesNotContain("\033]0;");
    }

    @Test
    void window_title_suppressed_in_no_ansi_mode() {
        // --no-ansi on a real TTY: still animated, but ANSI sequences are promised away.
        var noAnsi = new cc.jumpkick.config.JkConfig(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(true), // noAnsi
                Optional.empty(),
                Optional.empty()); // noOsc
        cc.jumpkick.config.SessionContext.runWhere(
                cc.jumpkick.config.Session.defaults().withConfig(noAnsi), () -> {
                    var buf = new ByteArrayOutputStream();
                    var cm = JkManager.plan(stream(buf), "Build", true);
                    cm.setWindowTitle("JumpKick - Building g:a:v...");
                    cm.finishBuildPlanSuccess("ok", List.of());
                    assertThat(buf.toString(StandardCharsets.UTF_8)).doesNotContain("\033]0;");
                });
    }

    @Test
    void plain_progress_emits_decades_then_100_done() {
        var noAnsi = new cc.jumpkick.config.JkConfig(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(true),
                Optional.empty(),
                Optional.empty());
        cc.jumpkick.config.SessionContext.runWhere(
                cc.jumpkick.config.Session.defaults().withConfig(noAnsi), () -> {
                    var buf = new ByteArrayOutputStream();
                    var cm = JkManager.plan(stream(buf), "Format", true);
                    cm.addTaskLabeled("", "fmt", "Examining source files");
                    cm.stepRunning("", "fmt");
                    cm.progress(0, 100);
                    cm.progress(15, 100); // still in the 0% step
                    cm.progress(25, 100); // crosses 20%
                    cm.progress(100, 100); // still working chrome max 80% mid-run
                    cm.finishBuildPlanSuccess("Already formatted - took 547ms", List.of());
                    String out = buf.toString(StandardCharsets.UTF_8);
                    assertThat(out).doesNotContain("\u001B[");
                    assertThat(out).doesNotContain(Spinner.PULSE_GLYPH);
                    assertThat(out).contains(" * Format > Examining source files - 0% - working...");
                    assertThat(out).doesNotContain(" * Format > Examining source files - 10% - working...");
                    assertThat(out).contains(" * Format > Examining source files - 20% - working...");
                    assertThat(out).contains(" * Format > Examining source files - 100% - done.");
                    assertThat(out).contains(" + Format > Already formatted - took 547ms");
                    // No mid-run 100% working line — 100% is only the done line.
                    assertThat(out).doesNotContain("100% - working...");
                });
    }

    @Test
    void plain_progress_line_helper_shape() {
        assertThat(JkManager.plainProgressLine("Format", "Examining source files", 0, false))
                .isEqualTo(" * Format > Examining source files - 0% - working...");
        assertThat(JkManager.plainProgressLine("Format", "Examining source files", 100, true))
                .isEqualTo(" * Format > Examining source files - 100% - done.");
        assertThat(JkManager.plainIndeterminateLine("Format", "Examining source files", false))
                .isEqualTo(" * Format > Examining source files - working...");
    }

    @Test
    void window_title_updates_only_when_fill_glyph_changes() {
        var buf = new ByteArrayOutputStream();
        var cm = new JkManager(stream(buf), true, true, 80);
        cm.setWindowTitle("JumpKick - Building g:a:v...");
        buf.reset();
        // setWindowTitle left frame=0 with ○ emitted. FILL_HOLD ticks use frames 0..HOLD-1
        // (same glyph) then leave frame=HOLD; none of those rewrite the title.
        for (int i = 0; i < Spinner.FILL_HOLD; i++) {
            cm.tick();
        }
        assertThat(buf.toString(StandardCharsets.UTF_8)).doesNotContain("\033]0;");
        // This tick paints with frame=HOLD (next phase) → one OSC update.
        cm.tick();
        String out = buf.toString(StandardCharsets.UTF_8);
        String nextGlyph = Spinner.fillGlyph(Spinner.FILL_HOLD);
        assertThat(nextGlyph).isNotEqualTo(Spinner.fillGlyph(0));
        assertThat(out).contains("\033]0;" + nextGlyph + " JumpKick - Building g:a:v...\033\\");
        // Only one OSC 0 in this window (the phase advance).
        assertThat(out.split("\033]0;", -1).length - 1).isEqualTo(1);
        cm.finishBuildPlanSuccess("ok", List.of());
    }

    @Test
    void header_countdown_is_mid_gray_count_up_is_dim_then_mid_gray_on_overrun() {
        Theme t = Theme.active();
        // Seeded ETA with remaining > 0 → dim italic "ETA " + mid-gray "~remaining" · dim "+elapsed".
        var down = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        down.nerdfont = false;
        down.progress(10, 100);
        down.setEtaEstimate(60_000);
        String downHeader = down.renderBuildPlanLines(120, 4_000).get(0);
        assertThat(TestAnsi.strip(downHeader)).contains("ETA ~56s");
        assertThat(TestAnsi.strip(downHeader)).contains("+4s");
        assertThat(downHeader).contains(Theme.colorize("ETA ", t.darkGray().italic()));
        assertThat(downHeader).contains(Theme.colorize("~56s", t.midGray()));
        assertThat(downHeader).contains(Theme.colorize("+4s", t.darkGray()));
        assertThat(downHeader).doesNotContain(Theme.colorize("+4s", t.warning()));

        // No seed → +elapsed count-up (mid-gray, same as countdown), no ETA prefix.
        var up = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        up.nerdfont = false;
        up.progress(10, 100);
        String upHeader = up.renderBuildPlanLines(120, 12_000).get(0);
        assertThat(TestAnsi.strip(upHeader)).contains("+12s");
        assertThat(TestAnsi.strip(upHeader)).doesNotContain("ETA ");
        assertThat(upHeader).contains(Theme.colorize("+12s", t.midGray()));
        assertThat(upHeader).doesNotContain(Theme.colorize("+12s", t.warning()));

        // Seed overrun within grace → frozen dim 0s + still-dim count-up.
        var earlyOver = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        earlyOver.nerdfont = false;
        earlyOver.progress(90, 100);
        earlyOver.setEtaEstimate(10_000);
        String earlyHeader = earlyOver.renderBuildPlanLines(120, 11_000).get(0);
        assertThat(TestAnsi.strip(earlyHeader)).contains("ETA 0s").contains("+11s");
        assertThat(earlyHeader).contains(Theme.colorize("0s", t.darkGray()));
        assertThat(earlyHeader).contains(Theme.colorize("+11s", t.darkGray()));

        // Past grace → mid-gray full elapsed (countdown's former color).
        var over = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        over.nerdfont = false;
        over.progress(90, 100);
        over.setEtaEstimate(10_000);
        String overHeader = over.renderBuildPlanLines(120, 15_000).get(0);
        assertThat(TestAnsi.strip(overHeader)).contains("ETA 0s");
        assertThat(TestAnsi.strip(overHeader)).contains("+15s");
        assertThat(overHeader).contains(Theme.colorize("ETA ", t.darkGray().italic()));
        assertThat(overHeader).contains(Theme.colorize("0s", t.darkGray()));
        assertThat(overHeader).contains(Theme.colorize("+15s", t.midGray()));
        assertThat(overHeader).doesNotContain(Theme.colorize("+15s", t.warning()));
    }

    @Test
    void goal_header_bar_and_phase_chain() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Building", false);
        cm.nerdfont = false;
        cm.progress(45, 100);
        cm.stepDone("acme:api", "parse-build", true, "resolve"); // success → removed from chain
        cm.stepRunning("acme:api", "compile-java", "compile");

        var raw = cm.renderBuildPlanLines(120, 112_000);
        String all = String.join("\n", stripAll(raw));
        assertThat(TestAnsi.strip(raw.get(0)))
                .contains("Building")
                .contains(Spinner.PULSE_GLYPH)
                .contains("1m 52s")
                .doesNotContain("acme:api"); // module lives on the tree row, not the header
        assertThat(all).contains("45%");
        // Only running Compile stays; Resolve succeeded and is gone. Module + phase on the row.
        assertThat(all).contains("acme:api").contains("Compile").contains("·");
        assertThat(all).doesNotContain("Resolve");
        assertThat(all).containsAnyOf("├─", "╰─", "+-", "`-");
        assertThat(all).doesNotContain("›");
    }

    @Test
    void nerdfont_header_wraps_the_name_in_a_pill_with_a_powerline_cap() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdfont = true;
        cm.progress(45, 100);

        String header = cm.renderBuildPlanLines(120, 0).get(0);
        // Pill: pulse circle + name + powerline cap.
        assertThat(TestAnsi.strip(header)).contains(Spinner.PULSE_GLYPH + " Build " + Glyphs.SEGMENT_END_NERD);
        AttributedStyle chip = Theme.active().planChip();
        assertThat(header).startsWith(Theme.colorize(" ", chip));
        assertThat(header).contains(Theme.colorize("Build", chip));
        // Cap: FG = chip blue; BG = bar lead color.
        Rgb lead = new ProgressBar().leadColor(45, 100);
        assertThat(header)
                .contains(Theme.colorize(
                        Glyphs.SEGMENT_END_NERD,
                        Theme.active()
                                .withBackground(
                                        Theme.active().bright(Theme.active().planBadgeColor()), lead)));
    }

    @Test
    void canceling_a_goal_region_returns_to_column_zero_before_erasing() {
        var buf = new ByteArrayOutputStream();
        var cm = new JkManager(stream(buf), true, true, 80); // animate + plan mode
        cm.progress(2, 4);
        cm.stepRunning("m", "compile");
        cm.tick(); // paint the live region
        buf.reset();

        cm.renderCanceled();

        // On Ctrl-C the tty echoes "^C" at the cursor (two columns in); cursorUp keeps
        // the column, so the wipe must emit a carriage return before
        // ERASE_DISPLAY_TO_END — otherwise the first two columns (the spinner glyph)
        // of the top line survive.
        String raw = buf.toString(StandardCharsets.UTF_8);
        assertThat(raw).contains("\r" + cc.jumpkick.cli.Ansi.ERASE_DISPLAY_TO_END);
    }

    @Test
    void tree_rows_use_blue_spinner_and_blue_phase_not_background_pills() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdfont = false;
        cm.stepRunning("com.foo:bar", "compile", "compile");
        cm.stepDone("com.foo:baz", "test", false, "test");
        var raw = cm.renderBuildPlanLines(120, 0);
        String joined = String.join("\n", raw);
        String visible = String.join("\n", stripAll(raw));
        Theme t = Theme.active();
        // Compact module · phase: no bg pills / powerline caps on the tree.
        assertThat(visible).contains("com.foo:bar").contains("·").contains("Compile");
        assertThat(visible).contains("com.foo:baz").contains("Test");
        // Running phase is bold blue (web parity); failed phase stays red.
        assertThat(joined).contains(Theme.colorize("Compile", t.blue().bold()));
        assertThat(joined).contains(Theme.colorize("Test", t.error()));
        assertThat(joined).contains(Theme.colorize("·", t.darkGray()));
        assertThat(joined).doesNotContain(Glyphs.PILL_LEFT_NERD);
        // Running row uses fill-circle (○) in constant blue — not the solid ● pulse glyph.
        assertThat(visible).contains("\u25CB"); // ○ frame 0
        assertThat(joined).contains(Theme.colorize("\u25CB", t.blue()));
        assertThat(visible).doesNotContain(Spinner.PULSE_GLYPH + " com.foo:bar");
    }

    @Test
    void tree_fill_spinner_cycles_circle_bullseye_fisheye() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdfont = false;
        cm.stepRunning("m", "compile", "compile");
        assertThat(Spinner.FILL_PHASES).containsExactly("\u25CB", "\u25CE", "\u25C9", "\u25CE");
        assertThat(Spinner.FILL_HOLD).isEqualTo(4);
        assertThat(Spinner.fillGlyph(0)).isEqualTo("\u25CB");
        assertThat(Spinner.fillGlyph(4)).isEqualTo("\u25CE");
        String line0 = stripAll(cm.renderBuildPlanLines(120, 0)).get(1);
        assertThat(line0).contains("\u25CB");
    }

    @Test
    void tree_row_appends_step_message_as_detail_after_phase() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdfont = false;
        cm.stepRunning("cc.jumpkick:jk-java-compiler", "package-jar", "package");
        cm.stepMessage("cc.jumpkick:jk-java-compiler", "package-jar", "shrinking jar");

        String all = String.join("\n", stripAll(cm.renderBuildPlanLines(120, 0)));
        // ● module · Package · shrinking jar
        assertThat(all)
                .contains("cc.jumpkick:jk-java-compiler")
                .contains("Package")
                .contains("shrinking jar");
        assertThat(all.indexOf("Package")).isLessThan(all.indexOf("shrinking jar"));
    }

    @Test
    void tree_row_strips_redundant_module_prefix_from_test_labels() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdfont = false;
        cm.stepRunning("cc.jumpkick:jk-core", "run-tests", "test");
        cm.stepMessage("cc.jumpkick:jk-core", "run-tests", "cc.jumpkick:jk-core :: FooTest.bar()  [w2]");

        String all = String.join("\n", stripAll(cm.renderBuildPlanLines(120, 0)));
        assertThat(all).contains("FooTest.bar()").contains("[w2]");
        // Module appears once as the row coordinate, not again in the detail segment.
        int first = all.indexOf("cc.jumpkick:jk-core");
        int second = all.indexOf("cc.jumpkick:jk-core", first + 1);
        assertThat(second).isLessThan(0);
    }

    @Test
    void test_detail_uses_java_syntax_highlighting() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdfont = false;
        cm.stepRunning("cc.jumpkick:jk-engine", "run-tests", "test");
        cm.stepMessage("cc.jumpkick:jk-engine", "run-tests", "VariantSwitchTest.switching_variants(Path)");

        var raw = cm.renderBuildPlanLines(120, 0);
        String joined = String.join("\n", raw);
        String visible = String.join("\n", stripAll(raw));
        assertThat(visible).contains("VariantSwitchTest.switching_variants(Path)");
        // Class name → TYPE, method → FUNCTION (same roles as source snippets).
        Theme t = Theme.active();
        assertThat(joined).contains(Theme.colorize("VariantSwitchTest", t.synType()));
        assertThat(joined).contains(Theme.colorize("switching_variants", t.synFunction()));
        assertThat(joined).contains(Theme.colorize("Path", t.synType()));
    }

    @Test
    void tree_rows_never_wrap_long_test_details() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdfont = false;
        cm.stepRunning("cc.jumpkick:jk-engine", "run-tests", "test");
        String longName = "VariantSwitchTest.switching_variants_drops_the_previous_values_extra_src_classes(Path)";
        cm.stepMessage("cc.jumpkick:jk-engine", "run-tests", longName);

        int cols = 60;
        for (String line : cm.renderBuildPlanLines(cols, 0)) {
            // Paint path hard-truncates; each rendered line must fit the terminal width.
            assertThat(JkManager.truncateVisible(line, cols)
                            .replaceAll("\033\\[[0-9;]*[A-Za-z]", "")
                            .length())
                    .isLessThanOrEqualTo(cols);
        }
        // Truncation adds an ellipsis rather than wrapping.
        String painted =
                JkManager.truncateVisible(cm.renderBuildPlanLines(cols, 0).get(1), cols);
        assertThat(TestAnsi.strip(painted)).contains("…");
    }

    @Test
    void detailForDisplay_strips_module_prefix() {
        assertThat(JkManager.detailForDisplay("g:a", "g:a :: FooTest.t()")).isEqualTo("FooTest.t()");
        assertThat(JkManager.detailForDisplay("g:a", "shrinking jar")).isEqualTo("shrinking jar");
        assertThat(JkManager.detailForDisplay("g:a", "")).isEmpty();
    }

    @Test
    void looksLikeJavaMember_detects_class_method_form() {
        assertThat(JkManager.looksLikeJavaMember("FooTest.bar(Path)")).isTrue();
        assertThat(JkManager.looksLikeJavaMember("FooTest")).isTrue();
        assertThat(JkManager.looksLikeJavaMember("shrinking jar")).isFalse();
    }

    @Test
    void colorDetail_strips_fqcns_from_java_member_labels() {
        Theme t = Theme.active();
        String painted = JkManager.colorDetail(
                "Test", "cc.jumpkick.runtime.FooTest.bar(java.nio.file.Path)", t);
        assertThat(TestAnsi.strip(painted)).isEqualTo("FooTest.bar(Path)");
        assertThat(TestAnsi.strip(painted)).doesNotContain("java.nio");
        assertThat(painted).contains(Theme.colorize("Path", t.synType()));
    }

    @Test
    void prose_detail_defaults_to_mid_gray_not_dim_or_cyan() {
        Theme t = Theme.active();
        String painted = JkManager.colorDetail("Package", "shrinking jar", t);
        assertThat(TestAnsi.strip(painted)).isEqualTo("shrinking jar");
        // Body tokens are mid-gray (#A0A0A0) — not dim bright-black, not cyan.
        assertThat(painted).contains(Theme.colorize("shrinking", t.midGray()));
        assertThat(painted).doesNotContain(Theme.colorize("shrinking", t.darkGray()));
        assertThat(painted).doesNotContain(Theme.colorize("shrinking", t.activeStep()));
        assertThat(painted).doesNotContain(Theme.colorize("shrinking", t.brightCyan()));
    }

    @Test
    void compile_test_under_test_phase_is_prose_mid_gray_not_syntax_white() {
        // compile-test is "test" wire-wise, but labels are "compiling N sources" — not FooTest.bar.
        Theme t = Theme.active();
        String painted = JkManager.colorDetail("Test", "compiling 12 Groovy test sources", t);
        assertThat(TestAnsi.strip(painted)).isEqualTo("compiling 12 Groovy test sources");
        assertThat(painted).contains(Theme.colorize("compiling", t.midGray()));
        assertThat(painted).contains(Theme.colorize("12", t.warning()));
        assertThat(painted).contains(Theme.colorize("Groovy", t.midGray()));
        // Must not route through SyntaxHighlight (PLAIN = terminal default/white).
        assertThat(painted).doesNotContain("compiling 12 Groovy test sources"); // unstyled whole string
    }

    @Test
    void package_detail_uses_path_color_for_jar_name() {
        Theme t = Theme.active();
        String painted = JkManager.colorDetail("Package", "package jk-engine-0.12.0.jar", t);
        assertThat(TestAnsi.strip(painted)).isEqualTo("package jk-engine-0.12.0.jar");
        assertThat(painted).contains(Theme.colorize("package", t.midGray()));
        assertThat(painted).contains(Theme.colorize("jk-engine-0.12.0.jar", t.path()));
    }

    @Test
    void compile_detail_uses_yellow_for_source_count() {
        Theme t = Theme.active();
        String painted = JkManager.colorDetail("Compile", "compiling 42 sources", t);
        assertThat(TestAnsi.strip(painted)).isEqualTo("compiling 42 sources");
        assertThat(painted).contains(Theme.colorize("compiling", t.midGray()));
        assertThat(painted).contains(Theme.colorize("42", t.warning()));
        assertThat(painted).contains(Theme.colorize("sources", t.midGray()));
    }

    @Test
    void size_uses_yellow_number_and_gray_unit() {
        Theme t = Theme.active();
        String painted = JkManager.colorDetail("Package", "shrunk 4.2 MiB → 1.1 MiB", t);
        assertThat(TestAnsi.strip(painted)).isEqualTo("shrunk 4.2 MiB → 1.1 MiB");
        assertThat(painted).contains(Theme.colorize("4.2", t.warning()));
        assertThat(painted).contains(Theme.colorize("1.1", t.warning()));
        assertThat(painted).contains(Theme.colorize("MiB", t.midGray()));
    }

    @Test
    void resolve_detail_colors_maven_coords() {
        String painted = JkManager.colorDetail(
                "Resolve", "fetched com.fasterxml.jackson.core:jackson-core:2.18.0", Theme.active());
        assertThat(TestAnsi.strip(painted)).isEqualTo("fetched com.fasterxml.jackson.core:jackson-core:2.18.0");
        // Coords.gav splits group / artifact / version with their theme roles.
        assertThat(painted)
                .contains(cc.jumpkick.cli.theme.Coords.gav("com.fasterxml.jackson.core", "jackson-core", "2.18.0"));
    }

    @Test
    void fetch_detail_colors_library_short_name() {
        String painted = JkManager.colorDetail("Resolve", "fetched jackson-core", Theme.active());
        assertThat(TestAnsi.strip(painted)).isEqualTo("fetched jackson-core");
        assertThat(painted).contains(cc.jumpkick.cli.theme.Coords.shortName("jackson-core"));
    }

    @Test
    void cache_hit_hex_is_dim_not_number_yellow() {
        Theme t = Theme.active();
        String painted = JkManager.colorDetail("Compile", "cache hit 9aa55003", t);
        assertThat(TestAnsi.strip(painted)).isEqualTo("cache hit 9aa55003");
        // Slightly dimmer than mid-gray body prose, still not number-yellow.
        assertThat(painted).contains(Theme.colorize("9aa55003", t.darkGray()));
        assertThat(painted).doesNotContain(Theme.colorize("9aa55003", t.warning()));
    }

    @Test
    void paren_count_still_yellows_the_number() {
        Theme t = Theme.active();
        String painted = JkManager.colorDetail("Compile", "d8 (12 classes + 3 jars)", t);
        assertThat(TestAnsi.strip(painted)).isEqualTo("d8 (12 classes + 3 jars)");
        assertThat(painted).contains(Theme.colorize("12", t.warning()));
        assertThat(painted).contains(Theme.colorize("3", t.warning()));
    }

    @Test
    void looksLikePathOrArtifact_detects_jars_and_paths() {
        assertThat(JkManager.looksLikePathOrArtifact("lib.jar")).isTrue();
        assertThat(JkManager.looksLikePathOrArtifact("app.aar")).isTrue();
        assertThat(JkManager.looksLikePathOrArtifact("target/classes")).isTrue();
        assertThat(JkManager.looksLikePathOrArtifact("sources")).isFalse();
        assertThat(JkManager.looksLikePathOrArtifact("up-to-date")).isFalse();
    }

    @Test
    void native_classpath_size_detail_uses_path_and_bold_white() {
        Theme t = Theme.active();
        String detail = "jk-cli · classpath input size: ~3.8 MiB";
        String painted = JkManager.colorDetail("Native", detail, t);
        assertThat(TestAnsi.strip(painted)).isEqualTo(detail);
        // Filename: Theme.path (periwinkle #969DD4) — same as other file/path designations.
        assertThat(painted).contains(Theme.colorize("jk-cli", t.path()));
        // Size number: focused = bold + bright white (not count-yellow).
        assertThat(painted).contains(Theme.colorize("~3.8", t.focused()));
        assertThat(painted).doesNotContain(Theme.colorize("~3.8", t.warning()));
        assertThat(painted).contains(Theme.colorize(" MiB", t.midGray()));
        assertThat(painted).contains(Theme.colorize(" · classpath input size: ", t.midGray()));
    }

    @Test
    void native_classpath_size_detail_win_exe_basename() {
        Theme t = Theme.active();
        String detail = "cli.exe · classpath input size: ~1.2 MiB";
        String painted = JkManager.colorDetail("Native", detail, t);
        assertThat(TestAnsi.strip(painted)).isEqualTo(detail);
        assertThat(painted).contains(Theme.colorize("cli.exe", t.path()));
        assertThat(painted).contains(Theme.colorize("~1.2", t.focused()));
    }

    @Test
    void looksLikeCoord_detects_gav() {
        assertThat(JkManager.looksLikeCoord("com.foo:bar:1.0")).isTrue();
        assertThat(JkManager.looksLikeCoord("com.foo:bar")).isTrue();
        assertThat(JkManager.looksLikeCoord("lib.jar")).isFalse();
        assertThat(JkManager.looksLikeCoord("compiling")).isFalse();
    }

    @Test
    void preflight_detail_shows_on_phase_only_tree_row() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdfont = false;
        cm.preflight("lock", 0, 1, "resolving dependencies");

        String all = String.join("\n", stripAll(cm.renderBuildPlanLines(120, 0)));
        assertThat(all).contains("Lock").contains("resolving dependencies");
    }

    @Test
    void phase_chain_shows_running_and_failed_only_newest_first() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Building", false);
        cm.nerdfont = false;
        cm.stepDone("m", "s1", true, "resolve"); // success → dropped
        cm.stepDone("m", "s2", false, "compile"); // failed → stays
        cm.stepRunning("m", "s3", "test");

        String all = String.join("\n", stripAll(cm.renderBuildPlanLines(120, 0)));
        assertThat(all).contains("Test").contains("Compile");
        assertThat(all).doesNotContain("Resolve");
        // Running rows first (newest), then failed by finish seq.
        assertThat(all.indexOf("Test")).isLessThan(all.indexOf("Compile"));
        assertThat(all).contains(Glyphs.CROSS); // failed row icon
    }

    @Test
    void failed_phase_shows_brief_error_below() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Building", false);
        cm.nerdfont = false;
        cm.stepRunning("m", "compile", "compile");
        cm.attachPhaseError("m", "compile", "compile", "javac failed: cannot find symbol");
        cm.stepDone("m", "compile", false, "compile");

        var lines = stripAll(cm.renderBuildPlanLines(120, 0));
        String all = String.join("\n", lines);
        assertThat(all).contains("Compile").contains("cannot find symbol");
    }

    @Test
    void attachPhaseError_uses_row_wire_phase_when_callers_pass_empty_phase() {
        // listeners pass phase=""; step key is compile-java, phase node is compile.
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdfont = false;
        cm.stepRunning("m", "compile-java", "compile");
        cm.attachPhaseError("m", "compile-java", "", "cannot find symbol Foo");
        cm.stepDone("m", "compile-java", false, "compile");

        var lines = stripAll(cm.renderBuildPlanLines(120, 0));
        String all = String.join("\n", lines);
        assertThat(all).contains("Compile").contains("cannot find symbol Foo");
        assertThat(all).doesNotContain("Failed\n"); // not the generic-only brief when we have a real one
    }

    @Test
    void brief_error_line_colors_message_not_rail() {
        // Rail/indent is dim; only the message is error-red (not the whole " │ Failed" string).
        Theme t = Theme.active();
        String mid = JkManager.renderBriefErrorLine(false, "Failed");
        String last = JkManager.renderBriefErrorLine(true, "boom");
        assertThat(mid).isEqualTo(Theme.colorize(" │  ", t.darkGray()) + Theme.colorize("Failed", t.error()));
        assertThat(last).isEqualTo(Theme.colorize("    ", t.darkGray()) + Theme.colorize("boom", t.error()));
        // Whole-line coloring would put the rail inside one error-styled span — must not.
        assertThat(mid).isNotEqualTo(Theme.colorize(" │  Failed", t.error()));
    }

    @Test
    void brief_error_under_last_tree_entry_uses_space_indent_not_rail() {
        // ╰─ then spaces, not │ under a closing branch.
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdfont = false;
        cm.stepRunning("m", "compile-java", "compile");
        cm.attachPhaseError("m", "compile-java", "compile", "boom");
        cm.stepDone("m", "compile-java", false, "compile");

        var lines = stripAll(cm.renderBuildPlanLines(120, 0));
        // Find the error line after the last ╰─ row
        boolean sawClose = false;
        for (String line : lines) {
            if (line.startsWith(" ╰─") || line.startsWith(" `-")) sawClose = true;
            if (sawClose && line.contains("boom")) {
                assertThat(line).startsWith("    boom");
                assertThat(line).doesNotContain("│");
                return;
            }
        }
        throw new AssertionError("expected brief error under closing branch, got:\n" + String.join("\n", lines));
    }

    @Test
    void tree_is_vertically_compact_without_blank_rail_spacers() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Building", false);
        cm.nerdfont = false;
        cm.stepRunning("com.foo:a", "a", "compile");
        cm.stepRunning("com.foo:b", "b", "test");
        var lines = stripAll(cm.renderBuildPlanLines(120, 0));
        // header + two tree rows only (no leading │, no blank │ between).
        assertThat(lines).hasSize(3);
        assertThat(lines.get(1)).matches(" [├+].*").contains("com.foo").contains("·");
        assertThat(lines.get(2)).matches(" [╰`].*").contains("com.foo").contains("·");
        for (String line : lines) {
            assertThat(line.strip()).isNotEqualTo("│");
        }
    }

    @Test
    void region_is_capped_to_terminal_height() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Building", false);
        cm.height = 6;
        for (int i = 0; i < 20; i++) cm.stepRunning("m", "p" + i, "phase" + i);

        var lines = cm.renderBuildPlanLines(120, 0);
        assertThat(lines.size()).isLessThanOrEqualTo(6 - 1);
    }

    @Test
    void phase_chain_uses_tree_connectors() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Building", false);
        cm.nerdfont = false;
        cm.stepRunning("m", "compile", "compile");
        var lines = cm.renderBuildPlanLines(120, 0);
        // lines[0]=header, lines[1]=single work row (closing branch) — no leading blank rail.
        assertThat(TestAnsi.strip(lines.get(1))).matches(" [╰`].*Compile.*");
    }

    @Test
    void completed_lines_render_below_the_phase_chain_newest_first() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdfont = false;
        cm.stepRunning("m", "compile", "compile");
        cm.addCompletion("✓ [13 of 17] g:a13 took 1s");
        cm.addCompletion("✓ [14 of 17] g:a14 took 1s");

        var lines = cm.renderBuildPlanLines(120, 0);
        // header, work row (╰─), then completions newest first.
        assertThat(TestAnsi.strip(lines.get(1))).matches(" [╰`].*Compile.*");
        assertThat(TestAnsi.strip(lines.get(2))).isEqualTo("    ✓ [14 of 17] g:a14 took 1s");
        assertThat(TestAnsi.strip(lines.get(3))).isEqualTo("    ✓ [13 of 17] g:a13 took 1s");
    }

    @Test
    void completed_tail_caps_and_collapses_overflow_into_a_footer() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.stepRunning("m", "compile");
        for (int i = 1; i <= 8; i++) cm.addCompletion("✓ [0" + i + " of 17] g:a" + i + " took 1s");

        var all = String.join("\n", stripAll(cm.renderBuildPlanLines(120, 0)));
        // Only MAX_COMPLETIONS (5) show; the newest is first; 3 collapse into the footer.
        assertThat(all).contains("    ✓ [08 of 17]").contains("    ✓ [04 of 17]");
        assertThat(all).doesNotContain("[03 of 17]");
        assertThat(all).contains("      … plus 3 more …");
    }

    @Test
    void write_above_prints_the_line_then_repaints_the_region_below() {
        var buf = new ByteArrayOutputStream();
        var cm = new JkManager(stream(buf), true, true, 80);
        cm.progress(1, 4);
        cm.stepRunning("m", "compile");
        cm.tick(); // initial region paint
        buf.reset();

        cm.writeAbove("javac: warning in Foo.java");

        String visible = TestAnsi.strip(buf.toString(StandardCharsets.UTF_8));
        // The log line appears, and the bar (region) is repainted after it.
        int log = visible.indexOf("javac: warning in Foo.java");
        int bar = visible.indexOf("█");
        assertThat(log).isGreaterThanOrEqualTo(0);
        assertThat(bar).isGreaterThan(log); // region re-drawn below the log line
    }

    @Test
    void capture_output_routes_system_out_above_the_region_then_restores() {
        var buf = new ByteArrayOutputStream();
        var cm = new JkManager(stream(buf), true, true, 80);
        cm.stepRunning("m", "compile");
        cm.tick();
        buf.reset();

        PrintStream original = System.out;
        try (var scope = cm.captureOutput()) {
            System.out.println("from a step");
        }
        assertThat(System.out).isSameAs(original); // streams restored
        assertThat(TestAnsi.strip(buf.toString(StandardCharsets.UTF_8)))
                .contains("from a step"); // routed to the region's real stdout
    }

    @Test
    void fmt_elapsed_formats_minutes_and_seconds() {
        assertThat(JkManager.fmtElapsed(112_000)).isEqualTo("1m 52s");
        assertThat(JkManager.fmtElapsed(52_000)).isEqualTo("52s");
        assertThat(JkManager.fmtElapsed(0)).isEqualTo("0s");
    }

    @Test
    void truncate_visible_cuts_at_column_keeping_escapes() {
        String colored = Theme.colorize("abcdef", Theme.active().success());
        // Hard-truncate: reserve one column for … so the line never wraps.
        String cut = JkManager.truncateVisible(colored, 3);
        assertThat(TestAnsi.strip(cut)).isEqualTo("ab…");
        assertThat(cut).endsWith("\033[0m"); // reset appended on truncation
    }

    @Test
    void truncate_visible_returns_verbatim_when_it_fits() {
        String colored = Theme.colorize("abcdef", Theme.active().success());
        // Fits in 6 columns → original bytes preserved exactly (jk's SGR byte order).
        assertThat(JkManager.truncateVisible(colored, 6)).isEqualTo(colored);
        assertThat(JkManager.truncateVisible("plain", 10)).isEqualTo("plain");
    }

    @Test
    void truncate_visible_one_column_keeps_a_fitting_char() {
        // Degenerate 1-column width: fitting content survives; only longer input degrades
        // to the bare ellipsis. Empty stays empty.
        assertThat(JkManager.truncateVisible("", 1)).isEmpty();
        assertThat(JkManager.truncateVisible("a", 1)).isEqualTo("a");
        assertThat(TestAnsi.strip(JkManager.truncateVisible("ab", 1))).isEqualTo("…");
    }

    private static List<String> stripAll(List<String> lines) {
        return lines.stream().map(TestAnsi::strip).toList();
    }

    private static PrintStream stream(ByteArrayOutputStream buf) {
        return new PrintStream(buf, true, StandardCharsets.UTF_8);
    }
}
