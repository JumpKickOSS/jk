// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static cc.jumpkick.cli.tui.JkManagerTestSupport.stream;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.NerdFontCaps;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;

/** Header ETA countdown, progress-bar strategies, and residual re-anchoring of the JkManager plan header. */
class JkManagerCountdownTest {

    @Test
    void header_shows_a_wallclock_countdown_from_the_estimate() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdFont = NerdFontCaps.NONE;
        cm.progress(50, 100);

        // No estimate set → single mid-gray count-up from construction.
        String up = cm.renderBuildPlanLines(120, 4_000).get(0);
        assertThat(TestAnsi.strip(up)).contains("4s");
        assertThat(TestAnsi.strip(up)).doesNotContain("+4s");

        // Seeded with a 60s estimate: dual clock — countdown ~56s · elapsed 4s (no +).
        cm.setEtaEstimate(60_000);
        String header = cm.renderBuildPlanLines(120, 4_000).get(0);
        String plain = TestAnsi.strip(header);
        assertThat(plain).contains("ETA ~56s");
        assertThat(plain).contains("· 4s");
        // Countdown is mid-gray with tilde; elapsed is dim with no plus.
        assertThat(header).contains(Theme.colorize("~56s", Theme.active().midGray()));
        assertThat(header).contains(Theme.colorize("4s", Theme.active().darkGray()));
        assertThat(header).doesNotContain(Theme.colorize("+4s", Theme.active().darkGray()));
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
    void eta_countdown_counts_up_past_zero_without_a_color_swap() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdFont = NerdFontCaps.NONE;
        cm.setEtaEstimate(10_000); // 10s estimate
        Theme t = Theme.active();

        // Hits zero: countdown stays mid-gray 0s; elapsed stays dim with no plus.
        String atZero = cm.renderBuildPlanLines(120, 10_000).get(0);
        assertThat(TestAnsi.strip(atZero)).contains("ETA 0s").contains("· 10s");
        assertThat(atZero).contains(Theme.colorize("0s", t.midGray()));
        assertThat(atZero).contains(Theme.colorize("10s", t.darkGray()));
        assertThat(atZero).doesNotContain(Theme.colorize("0s", t.darkGray()));
        assertThat(atZero).doesNotContain(Theme.colorize("+10s", t.darkGray()));

        // Next second: countdown adds + and counts the miss; elapsed keeps ticking, still dim.
        String over = cm.renderBuildPlanLines(120, 11_000).get(0);
        assertThat(TestAnsi.strip(over)).contains("ETA +1s").contains("· 11s");
        assertThat(over).contains(Theme.colorize("+1s", t.midGray()));
        assertThat(over).contains(Theme.colorize("11s", t.darkGray()));
        assertThat(over).doesNotContain(Theme.colorize("11s", t.midGray()));
        assertThat(over).doesNotContain(Theme.colorize("+1s", t.warning()));
        assertThat(over).doesNotContain(Theme.colorize("0s", t.darkGray()));
    }

    @Test
    void dual_clock_ticks_together_even_when_seed_is_not_second_aligned() {
        // Independent floor(remainingMs) vs floor(elapsedMs) desynced the two faces by the seed's
        // sub-second remainder (e.g. 100ms after setRemainingWorkEstimate). Both must advance on
        // the same whole-second elapsed boundary.
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdFont = NerdFontCaps.NONE;
        cm.setEtaEstimate(60_100); // 60s + 100ms
        String mid = TestAnsi.strip(cm.renderBuildPlanLines(120, 4_050).get(0));
        assertThat(mid).contains("ETA ~56s");
        assertThat(mid).contains("· 4s");
        // Still the same pair just under the next second (old code would drop countdown here).
        String justBefore = TestAnsi.strip(cm.renderBuildPlanLines(120, 4_999).get(0));
        assertThat(justBefore).contains("ETA ~56s");
        assertThat(justBefore).contains("· 4s");
        // One paint advances both faces.
        String next = TestAnsi.strip(cm.renderBuildPlanLines(120, 5_000).get(0));
        assertThat(next).contains("ETA ~55s");
        assertThat(next).contains("· 5s");
    }

    @Test
    void eta_seed_may_refine_before_any_module_finishes() {
        // Early shape seed then post-prepare reseed — both before execute — may update the total.
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdFont = NerdFontCaps.NONE;
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
        cm.nerdFont = NerdFontCaps.NONE;
        // Simulate 30s already elapsed by using setEtaEstimate with elapsed+remaining directly
        // via setRemainingWorkEstimate after construction; render at that elapsed.
        // We can't freeze elapsedMillis, so set total = 30s + 90s and render at 30s.
        cm.setEtaEstimate(30_000 + 90_000);
        String at30 = TestAnsi.strip(cm.renderBuildPlanLines(120, 30_000).get(0));
        assertThat(at30).contains("ETA ~1m 30s");
        assertThat(at30).contains("· 30s");
        // At end of remaining work (elapsed 120s) → mid-gray 0s · dim elapsed (no plus).
        String done = TestAnsi.strip(cm.renderBuildPlanLines(120, 120_000).get(0));
        assertThat(done).contains("ETA 0s");
        assertThat(done).contains("· 2m 00s");
    }

    @Test
    void seed_path_locks_after_execute_but_residual_reanchors_countdown() {
        // R0 seed path freezes once a module completes (provisional eta thrash guard). Live
        // residual still re-anchors the countdown so ETA eases into R(t) and ends on time.
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdFont = NerdFontCaps.NONE;
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
        assertThat(mid).contains("· 11s");
        // Residual 0 snaps immediately (end on time — no 1s hold). Painted well past that
        // deadline the countdown counts the miss; elapsed stays a bare dim clock.
        cm.setBarResidualRemaining(0);
        String done = TestAnsi.strip(cm.renderBuildPlanLines(120, 40_000).get(0));
        assertThat(done).contains("ETA +40s");
        assertThat(done).contains("· 40s");
    }

    @Test
    void same_second_reanchor_overwrites_a_committed_zero() {
        // Snap-to-zero commits instantly; a residual raise in the SAME second must repaint
        // instead of holding 0s and bouncing 0s → Ns at the next second.
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdFont = NerdFontCaps.NONE;
        cm.setEtaEstimate(30_000);
        cm.setModuleProgress(1, 2);
        String zero = TestAnsi.strip(cm.renderBuildPlanLines(120, 40_000).get(0));
        // 10s past the 30s seed — countdown is already counting the miss.
        assertThat(zero).contains("ETA +10s");
        cm.startNanos = System.nanoTime() - 40_000_000_000L; // re-anchor lands at ~40s elapsed
        cm.setBarResidualRemaining(15_000);
        String raised = TestAnsi.strip(cm.renderBuildPlanLines(120, 40_000).get(0));
        assertThat(raised).contains("ETA ~15s");
    }

    @Test
    void identical_residual_reemits_do_not_reanchor_the_countdown() {
        // Preflight ticks force-emit the unchanged seed residual every ~500 ms; identical
        // re-emits must not reset the countdown anchor.
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdFont = NerdFontCaps.NONE;
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
        cm.nerdFont = NerdFontCaps.NONE;
        cm.setEtaEstimate(100_000); // R0 = 100s
        cm.setModuleProgress(1, 3);
        // Residual re-anchor near t=0 with 20s left (work finishing early). First paint samples it.
        // Open-loop R0 at 10s would still show ~90s; residual-anchored shows ~10s.
        cm.setBarResidualRemaining(20_000);
        String header = TestAnsi.strip(cm.renderBuildPlanLines(120, 10_000).get(0));
        assertThat(header).contains("ETA ~10s");
        assertThat(header).contains("· 10s");
        // Bar also speeds up from residual (raw residual, not wall-decayed).
        long[] bar = cm.displayBar(10_000);
        assertThat(bar[0]).isEqualTo(333); // 10/(10+20)
    }

    @Test
    void countdown_jitter_buffer_samples_latest_target_once_per_second() {
        // Residual may thrash several times inside one whole second; the painted face holds the
        // first sample for that second, then commits the latest target on the next elapsedSec.
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdFont = NerdFontCaps.NONE;
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
        // post-forecast seed replaces it, and only execute activity locks.
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdFont = NerdFontCaps.NONE;
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
        cm.nerdFont = NerdFontCaps.NONE;
        // No seed → elapsed for the whole command (no plus).
        assertThat(TestAnsi.strip(cm.renderBuildPlanLines(120, 12_000).get(0))).contains("12s");
        assertThat(TestAnsi.strip(cm.renderBuildPlanLines(120, 12_000).get(0))).doesNotContain("+12s");
        // Positive remaining seeds the dual clock (R0=30s at apply time ≈ elapsed 0).
        cm.setEtaEstimate(30_000);
        String seeded = TestAnsi.strip(cm.renderBuildPlanLines(120, 12_000).get(0));
        assertThat(seeded).contains("ETA ~18s");
        assertThat(seeded).contains("· 12s");
        // Zero before lock is ignored (unknown clear) — seed remains open-loop.
        cm.setEtaEstimate(0);
        String still = TestAnsi.strip(cm.renderBuildPlanLines(120, 12_000).get(0));
        assertThat(still).contains("ETA ~18s");
        assertThat(still).contains("· 12s");
    }

    @Test
    void header_countdown_has_dim_eta_prefix_and_no_module_counter() {
        // Dual clock: dim italic "ETA " + mid-gray ~remaining · dim elapsed; module n/m lives on
        // tree rows only.
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdFont = NerdFontCaps.NONE;
        cm.progress(50, 100);
        cm.setEtaEstimate(60_000);
        cm.setModuleProgress(2, 8);
        String header = TestAnsi.strip(cm.renderBuildPlanLines(120, 4_000).get(0));
        assertThat(header).contains("ETA ~56s");
        assertThat(header).contains("· 4s");
        assertThat(header).doesNotContain("2/8");
        // Cold count-up has no ETA prefix.
        String cold = TestAnsi.strip(JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false)
                .renderBuildPlanLines(120, 12_000)
                .get(0));
        assertThat(cold).contains("12s");
        assertThat(cold).doesNotContain("+12s");
        assertThat(cold).doesNotContain("ETA ");
    }
}
