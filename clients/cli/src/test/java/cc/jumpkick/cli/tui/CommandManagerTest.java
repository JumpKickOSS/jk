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

/** Simple-task mode of the CommandManager component. */
class CommandManagerTest {

    @Test
    void tick_renders_first_glyph_and_verb() {
        var buf = new ByteArrayOutputStream();
        var cm = new CommandManager(stream(buf), true);
        cm.label("Locking");
        cm.tick(); // frame 0 = pulse circle
        assertThat(TestAnsi.strip(buf.toString(StandardCharsets.UTF_8))).contains(Spinner.PULSE_GLYPH + " Locking…");
    }

    @Test
    void finish_success_freezes_spinner_then_prints_green_check_line() {
        var buf = new ByteArrayOutputStream();
        var cm = new CommandManager(stream(buf), true);
        cm.label("Syncing");
        cm.finishSuccess("Finished syncing 13 artifacts");

        String raw = buf.toString(StandardCharsets.UTF_8);
        String visible = TestAnsi.strip(raw);
        // Frozen pulse circle + command on its own line, result line below.
        assertThat(visible).contains(Spinner.PULSE_GLYPH + " Syncing…");
        // "✓ <pipeline> Successful: <message>", head in green.
        assertThat(visible).contains("✓ Syncing Successful: Finished syncing 13 artifacts");
        assertThat(raw)
                .contains(Theme.colorize("✓ Syncing Successful", Theme.active().success()));
        assertThat(raw).contains("\033[?25h"); // cursor restored
    }

    @Test
    void finish_success_prints_deferred_output_above_the_summary_line() {
        var buf = new ByteArrayOutputStream();
        var cm = new CommandManager(stream(buf), false); // pipe / --quiet
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
        var cm = new CommandManager(stream(buf), true, true, 80); // animate + pipeline mode
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
        var cm = new CommandManager(stream(buf), true);
        cm.label("Syncing");
        cm.finishFailure("Failed to sync remote artifacts");

        String raw = buf.toString(StandardCharsets.UTF_8);
        assertThat(TestAnsi.strip(raw)).contains("✘ Failed to sync remote artifacts");
        assertThat(raw).contains(Theme.colorize("✘", Theme.active().error()));
    }

    @Test
    void render_canceled_settles_spinner_without_printing_the_cancel_notice() {
        var buf = new ByteArrayOutputStream();
        var cm = new CommandManager(stream(buf), true);
        cm.label("Locking");
        cm.renderCanceled();

        String visible = TestAnsi.strip(buf.toString(StandardCharsets.UTF_8));
        assertThat(visible).contains(Spinner.PULSE_GLYPH + " Locking…");
        // The notice itself is GlobalCancel's job; the component only settles.
        assertThat(visible).doesNotContain("cancelled");
        assertThat(buf.toString(StandardCharsets.UTF_8)).contains("\033[?25h");
        // Simple (non-pipeline) mode still supplies the generic cancel text for the notice.
        assertThat(cm.canceledMessage()).isEqualTo("Build job was cancelled");
    }

    @Test
    void non_animated_mode_prints_only_the_result_line() {
        var buf = new ByteArrayOutputStream();
        var cm = new CommandManager(stream(buf), false); // pipe / --quiet
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
        var cm = new CommandManager(stream(buf), true);
        cm.label("Building");
        cm.finishSuccess("Built x");
        buf.reset();
        cm.finishFailure("ignored");
        cm.close();
        assertThat(buf.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    // --- pipeline-oriented mode ----------------------------------------------

    @Test
    void progress_is_monotonic_and_never_slides_backward() {
        var cm = CommandManager.pipeline(stream(new ByteArrayOutputStream()), "Build", false);
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
        var cm = CommandManager.pipeline(stream(buf), "Build", false);
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
        var cm = CommandManager.pipeline(stream(buf), "Run", false);
        cm.finishPipelineExec("Executing `java -cp … Main`");
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
        var cm = CommandManager.pipeline(ps, "Build", false);
        cm.finishPipelineSuccess("built");
        String out = buf.toString(StandardCharsets.UTF_8);
        // Exactly one leading blank for the whole command, not two.
        assertThat(out).startsWith("\n");
        assertThat(out).doesNotStartWith("\n\n");
        assertThat(out).contains("built");
    }

    @Test
    void header_shows_a_wallclock_countdown_from_the_estimate() {
        var cm = CommandManager.pipeline(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdfont = false;
        cm.progress(50, 100);

        // No estimate set → single yellow count-up from construction.
        String up = cm.renderPipelineLines(120, 4_000).get(0);
        assertThat(TestAnsi.strip(up)).contains("+4s");

        // Seeded with a 60s estimate: dual clock — countdown ~56s + elapsed +4s.
        cm.setEtaEstimate(60_000);
        String header = cm.renderPipelineLines(120, 4_000).get(0);
        String plain = TestAnsi.strip(header);
        assertThat(plain).contains("ETA ~56s");
        assertThat(plain).contains("+4s");
        // Countdown is blue with tilde; count-up is dim while remaining > 0.
        assertThat(header).contains(Theme.colorize("~56s", Theme.active().blue()));
        assertThat(header).contains(Theme.colorize("+4s", Theme.active().darkGray()));
        assertThat(header).contains(Theme.colorize("·", Theme.active().darkGray()));
    }

    @Test
    void eta_countdown_freezes_at_zero_and_count_up_turns_yellow_on_overrun() {
        var cm = CommandManager.pipeline(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdfont = false;
        cm.setEtaEstimate(10_000); // 10s estimate
        // 15s elapsed → countdown freezes at dim 0s; count-up is full elapsed (yellow).
        String header = cm.renderPipelineLines(120, 15_000).get(0);
        String plain = TestAnsi.strip(header);
        assertThat(plain).contains("ETA 0s");
        assertThat(plain).contains("+15s");
        assertThat(header).contains(Theme.colorize("0s", Theme.active().darkGray()));
        assertThat(header).contains(Theme.colorize("+15s", Theme.active().warning()));
    }

    @Test
    void dual_clock_ticks_together_even_when_seed_is_not_second_aligned() {
        // Independent floor(remainingMs) vs floor(elapsedMs) desynced the two faces by the seed's
        // sub-second remainder (e.g. 100ms after setRemainingWorkEstimate). Both must advance on
        // the same whole-second elapsed boundary.
        var cm = CommandManager.pipeline(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdfont = false;
        cm.setEtaEstimate(60_100); // 60s + 100ms
        String mid = TestAnsi.strip(cm.renderPipelineLines(120, 4_050).get(0));
        assertThat(mid).contains("ETA ~56s");
        assertThat(mid).contains("+4s");
        // Still the same pair just under the next second (old code would drop countdown here).
        String justBefore = TestAnsi.strip(cm.renderPipelineLines(120, 4_999).get(0));
        assertThat(justBefore).contains("ETA ~56s");
        assertThat(justBefore).contains("+4s");
        // One paint advances both faces.
        String next = TestAnsi.strip(cm.renderPipelineLines(120, 5_000).get(0));
        assertThat(next).contains("ETA ~55s");
        assertThat(next).contains("+5s");
    }

    @Test
    void eta_seed_may_refine_before_any_module_finishes() {
        // Early shape seed then post-prepare reseed — both before execute — may update the total.
        var cm = CommandManager.pipeline(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdfont = false;
        cm.setEtaEstimate(60_000);
        cm.setEtaEstimate(38_000); // post-prepare refine while modulesComplete == 0
        // 4s elapsed → 34s remaining from the refined seed.
        assertThat(TestAnsi.strip(cm.renderPipelineLines(120, 4_000).get(0))).contains("ETA ~34s");
    }

    @Test
    void remaining_work_seed_adds_elapsed_so_countdown_matches_explain() {
        // Engine reports remaining work (same figure as jk explain). After 30s of lock, a 90s
        // remaining estimate must show ~90s left — not 60s (which would finish 30s early).
        var cm = CommandManager.pipeline(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdfont = false;
        // Simulate 30s already elapsed by using setEtaEstimate with elapsed+remaining directly
        // via setRemainingWorkEstimate after construction; render at that elapsed.
        // We can't freeze elapsedMillis, so set total = 30s + 90s and render at 30s.
        cm.setEtaEstimate(30_000 + 90_000);
        String at30 = TestAnsi.strip(cm.renderPipelineLines(120, 30_000).get(0));
        assertThat(at30).contains("ETA ~1m 30s");
        assertThat(at30).contains("+30s");
        // At end of remaining work (elapsed 120s) → frozen 0s + yellow full elapsed.
        String done = TestAnsi.strip(cm.renderPipelineLines(120, 120_000).get(0));
        assertThat(done).contains("ETA 0s");
        assertThat(done).contains("+2m 00s");
    }

    @Test
    void eta_seed_locks_after_a_module_completes_so_reprojections_cannot_jump_the_clock() {
        // Live re-projections used to overwrite the total mid-build (elapsed + remaining schedule),
        // so the countdown jumped at module boundaries and count-up reset near zero.
        var cm = CommandManager.pipeline(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdfont = false;
        cm.setEtaEstimate(38_000);
        cm.setModuleProgress(1, 2); // first module finished → lock
        cm.setEtaEstimate(20_000); // would-be re-projection: ignore
        // 10s elapsed of a locked 38s seed → 28s remain (not 10s from the rejected re-projection).
        String mid = TestAnsi.strip(cm.renderPipelineLines(120, 10_000).get(0));
        assertThat(mid).contains("ETA ~28s");
        assertThat(mid).contains("+10s");
        // Overrun still pure wall-clock from the locked seed: freeze 0s + full elapsed (not re-projected).
        String over = TestAnsi.strip(cm.renderPipelineLines(120, 40_000).get(0));
        assertThat(over).contains("ETA 0s");
        assertThat(over).contains("+40s");
    }

    @Test
    void cold_count_up_is_run_wide_and_never_cleared_by_a_zero_eta() {
        var cm = CommandManager.pipeline(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdfont = false;
        // No seed → +elapsed for the whole command.
        assertThat(TestAnsi.strip(cm.renderPipelineLines(120, 12_000).get(0))).contains("+12s");
        // A zero ETA must not reset or clear a later positive seed's continuity either.
        cm.setEtaEstimate(30_000);
        cm.setEtaEstimate(0); // ignore clear
        String seeded = TestAnsi.strip(cm.renderPipelineLines(120, 12_000).get(0));
        assertThat(seeded).contains("ETA ~18s");
        assertThat(seeded).contains("+12s");
    }

    @Test
    void header_countdown_has_dim_eta_prefix_and_no_module_counter() {
        // Dual clock: dim italic "ETA " + ~remaining · +elapsed; module n/m lives on tree rows only.
        var cm = CommandManager.pipeline(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdfont = false;
        cm.progress(50, 100);
        cm.setEtaEstimate(60_000);
        cm.setModuleProgress(2, 8);
        String header = TestAnsi.strip(cm.renderPipelineLines(120, 4_000).get(0));
        assertThat(header).contains("ETA ~56s");
        assertThat(header).contains("+4s");
        assertThat(header).doesNotContain("2/8");
        // Cold count-up has no ETA prefix.
        String cold = TestAnsi.strip(CommandManager.pipeline(stream(new ByteArrayOutputStream()), "Build", false)
                .renderPipelineLines(120, 12_000)
                .get(0));
        assertThat(cold).contains("+12s");
        assertThat(cold).doesNotContain("ETA ");
    }

    @Test
    void setWindowTitle_emits_osc0_and_clears_on_settle() {
        var buf = new ByteArrayOutputStream();
        var cm = CommandManager.pipeline(stream(buf), "Build", true);
        cm.setWindowTitle("JumpKick - Building cc.jumpkick:jk:0.11.0...");
        String set = buf.toString(StandardCharsets.UTF_8);
        // OSC 0: fill-circle glyph + base, terminated with ST (ESC \), not BEL.
        String expected = "\033]0;" + Spinner.fillGlyph(0) + " JumpKick - Building cc.jumpkick:jk:0.11.0...\033\\";
        assertThat(set).contains(expected);
        buf.reset();
        cm.finishPipelineSuccess("ok", List.of());
        String cleared = buf.toString(StandardCharsets.UTF_8);
        assertThat(cleared).contains("\033]0;\033\\");
    }

    @Test
    void window_title_never_reaches_non_animated_output() {
        // Piped / CI / --quiet builds (animate=false) must stay byte-clean of OSC — the
        // escapes would land verbatim in the redirected stream.
        var buf = new ByteArrayOutputStream();
        var cm = CommandManager.pipeline(stream(buf), "Build", false);
        cm.setWindowTitle("JumpKick - Building g:a:v...");
        cm.addStep("g:a", "compile-main");
        cm.stepDone("g:a", "compile-main", true);
        cm.tick();
        cm.finishPipelineSuccess("ok", List.of());
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
                    var cm = CommandManager.pipeline(stream(buf), "Build", true);
                    cm.setWindowTitle("JumpKick - Building g:a:v...");
                    cm.finishPipelineSuccess("ok", List.of());
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
                    var cm = CommandManager.pipeline(stream(buf), "Format", true);
                    cm.addStepLabeled("", "fmt", "Examining source files");
                    cm.stepRunning("", "fmt");
                    cm.progress(0, 100);
                    cm.progress(15, 100); // crosses 10%
                    cm.progress(25, 100); // crosses 20%
                    cm.progress(100, 100); // still working chrome max 90% mid-run
                    cm.finishPipelineSuccess("Already formatted - took 547ms", List.of());
                    String out = buf.toString(StandardCharsets.UTF_8);
                    assertThat(out).doesNotContain("\u001B[");
                    assertThat(out).doesNotContain(Spinner.PULSE_GLYPH);
                    assertThat(out).contains(" * Format > Examining source files - 0% - working...");
                    assertThat(out).contains(" * Format > Examining source files - 10% - working...");
                    assertThat(out).contains(" * Format > Examining source files - 20% - working...");
                    assertThat(out).contains(" * Format > Examining source files - 100% - done.");
                    assertThat(out).contains(" + Format > Already formatted - took 547ms");
                    // No mid-run 100% working line — 100% is only the done line.
                    assertThat(out).doesNotContain("100% - working...");
                });
    }

    @Test
    void plain_progress_line_helper_shape() {
        assertThat(CommandManager.plainProgressLine("Format", "Examining source files", 0, false))
                .isEqualTo(" * Format > Examining source files - 0% - working...");
        assertThat(CommandManager.plainProgressLine("Format", "Examining source files", 100, true))
                .isEqualTo(" * Format > Examining source files - 100% - done.");
        assertThat(CommandManager.plainIndeterminateLine("Format", "Examining source files", false))
                .isEqualTo(" * Format > Examining source files - working...");
    }

    @Test
    void window_title_updates_only_when_fill_glyph_changes() {
        var buf = new ByteArrayOutputStream();
        var cm = new CommandManager(stream(buf), true, true, 80);
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
        cm.finishPipelineSuccess("ok", List.of());
    }

    @Test
    void header_countdown_is_blue_count_up_is_dim_then_yellow() {
        Theme t = Theme.active();
        // Seeded ETA with remaining > 0 → dim italic "ETA " + blue "~remaining" · dim "+elapsed".
        var down = CommandManager.pipeline(stream(new ByteArrayOutputStream()), "Build", false);
        down.nerdfont = false;
        down.progress(10, 100);
        down.setEtaEstimate(60_000);
        String downHeader = down.renderPipelineLines(120, 4_000).get(0);
        assertThat(TestAnsi.strip(downHeader)).contains("ETA ~56s");
        assertThat(TestAnsi.strip(downHeader)).contains("+4s");
        assertThat(downHeader).contains(Theme.colorize("ETA ", t.darkGray().italic()));
        assertThat(downHeader).contains(Theme.colorize("~56s", t.blue()));
        assertThat(downHeader).contains(Theme.colorize("+4s", t.darkGray()));
        assertThat(downHeader).doesNotContain(Theme.colorize("+4s", t.warning()));

        // No seed → +elapsed count-up (yellow), no ETA prefix.
        var up = CommandManager.pipeline(stream(new ByteArrayOutputStream()), "Build", false);
        up.nerdfont = false;
        up.progress(10, 100);
        String upHeader = up.renderPipelineLines(120, 12_000).get(0);
        assertThat(TestAnsi.strip(upHeader)).contains("+12s");
        assertThat(TestAnsi.strip(upHeader)).doesNotContain("ETA ");
        assertThat(upHeader).contains(Theme.colorize("+12s", t.warning()));

        // Seed overrun → frozen dim 0s + yellow full elapsed (still keeps ETA prefix).
        var over = CommandManager.pipeline(stream(new ByteArrayOutputStream()), "Build", false);
        over.nerdfont = false;
        over.progress(90, 100);
        over.setEtaEstimate(10_000);
        String overHeader = over.renderPipelineLines(120, 15_000).get(0);
        assertThat(TestAnsi.strip(overHeader)).contains("ETA 0s");
        assertThat(TestAnsi.strip(overHeader)).contains("+15s");
        assertThat(overHeader).contains(Theme.colorize("ETA ", t.darkGray().italic()));
        assertThat(overHeader).contains(Theme.colorize("0s", t.darkGray()));
        assertThat(overHeader).contains(Theme.colorize("+15s", t.warning()));
    }

    @Test
    void goal_header_bar_and_phase_chain() {
        var cm = CommandManager.pipeline(stream(new ByteArrayOutputStream()), "Building", false);
        cm.nerdfont = false;
        cm.progress(45, 100);
        cm.stepDone("acme:api", "parse-build", true, "resolve"); // success → removed from chain
        cm.stepRunning("acme:api", "compile-java", "compile");

        var raw = cm.renderPipelineLines(120, 112_000);
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
        assertThat(all).containsAnyOf("├─", "╰─");
        assertThat(all).doesNotContain("›");
    }

    @Test
    void nerdfont_header_wraps_the_name_in_a_pill_with_a_powerline_cap() {
        var cm = CommandManager.pipeline(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdfont = true;
        cm.progress(45, 100);

        String header = cm.renderPipelineLines(120, 0).get(0);
        // Pill: pulse circle + name + powerline cap.
        assertThat(TestAnsi.strip(header)).contains(Spinner.PULSE_GLYPH + " Build " + Glyphs.SEGMENT_END_NERD);
        AttributedStyle chip = Theme.active().pipelineChip();
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
        var cm = new CommandManager(stream(buf), true, true, 80); // animate + pipeline mode
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
        var cm = CommandManager.pipeline(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdfont = false;
        cm.stepRunning("com.foo:bar", "compile", "compile");
        cm.stepDone("com.foo:baz", "test", false, "test");
        var raw = cm.renderPipelineLines(120, 0);
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
        var cm = CommandManager.pipeline(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdfont = false;
        cm.stepRunning("m", "compile", "compile");
        assertThat(Spinner.FILL_PHASES).containsExactly("\u25CB", "\u25CE", "\u25C9", "\u25CE");
        assertThat(Spinner.FILL_HOLD).isEqualTo(4);
        assertThat(Spinner.fillGlyph(0)).isEqualTo("\u25CB");
        assertThat(Spinner.fillGlyph(4)).isEqualTo("\u25CE");
        String line0 = stripAll(cm.renderPipelineLines(120, 0)).get(1);
        assertThat(line0).contains("\u25CB");
    }

    @Test
    void tree_row_appends_step_message_as_detail_after_phase() {
        var cm = CommandManager.pipeline(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdfont = false;
        cm.stepRunning("cc.jumpkick:jk-java-compiler", "package-jar", "package");
        cm.stepMessage("cc.jumpkick:jk-java-compiler", "package-jar", "shrinking jar");

        String all = String.join("\n", stripAll(cm.renderPipelineLines(120, 0)));
        // ● module · Package · shrinking jar
        assertThat(all)
                .contains("cc.jumpkick:jk-java-compiler")
                .contains("Package")
                .contains("shrinking jar");
        assertThat(all.indexOf("Package")).isLessThan(all.indexOf("shrinking jar"));
    }

    @Test
    void tree_row_strips_redundant_module_prefix_from_test_labels() {
        var cm = CommandManager.pipeline(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdfont = false;
        cm.stepRunning("cc.jumpkick:jk-core", "run-tests", "test");
        cm.stepMessage("cc.jumpkick:jk-core", "run-tests", "cc.jumpkick:jk-core :: FooTest.bar()  [w2]");

        String all = String.join("\n", stripAll(cm.renderPipelineLines(120, 0)));
        assertThat(all).contains("FooTest.bar()").contains("[w2]");
        // Module appears once as the row coordinate, not again in the detail segment.
        int first = all.indexOf("cc.jumpkick:jk-core");
        int second = all.indexOf("cc.jumpkick:jk-core", first + 1);
        assertThat(second).isLessThan(0);
    }

    @Test
    void test_detail_uses_java_syntax_highlighting() {
        var cm = CommandManager.pipeline(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdfont = false;
        cm.stepRunning("cc.jumpkick:jk-engine", "run-tests", "test");
        cm.stepMessage("cc.jumpkick:jk-engine", "run-tests", "VariantSwitchTest.switching_variants(Path)");

        var raw = cm.renderPipelineLines(120, 0);
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
        var cm = CommandManager.pipeline(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdfont = false;
        cm.stepRunning("cc.jumpkick:jk-engine", "run-tests", "test");
        String longName = "VariantSwitchTest.switching_variants_drops_the_previous_values_extra_src_classes(Path)";
        cm.stepMessage("cc.jumpkick:jk-engine", "run-tests", longName);

        int cols = 60;
        for (String line : cm.renderPipelineLines(cols, 0)) {
            // Paint path hard-truncates; each rendered line must fit the terminal width.
            assertThat(CommandManager.truncateVisible(line, cols)
                            .replaceAll("\033\\[[0-9;]*[A-Za-z]", "")
                            .length())
                    .isLessThanOrEqualTo(cols);
        }
        // Truncation adds an ellipsis rather than wrapping.
        String painted =
                CommandManager.truncateVisible(cm.renderPipelineLines(cols, 0).get(1), cols);
        assertThat(TestAnsi.strip(painted)).contains("…");
    }

    @Test
    void detailForDisplay_strips_module_prefix() {
        assertThat(CommandManager.detailForDisplay("g:a", "g:a :: FooTest.t()")).isEqualTo("FooTest.t()");
        assertThat(CommandManager.detailForDisplay("g:a", "shrinking jar")).isEqualTo("shrinking jar");
        assertThat(CommandManager.detailForDisplay("g:a", "")).isEmpty();
    }

    @Test
    void looksLikeJavaMember_detects_class_method_form() {
        assertThat(CommandManager.looksLikeJavaMember("FooTest.bar(Path)")).isTrue();
        assertThat(CommandManager.looksLikeJavaMember("FooTest")).isTrue();
        assertThat(CommandManager.looksLikeJavaMember("shrinking jar")).isFalse();
    }

    @Test
    void prose_detail_defaults_to_mid_gray_not_dim_or_cyan() {
        Theme t = Theme.active();
        String painted = CommandManager.colorDetail("Package", "shrinking jar", t);
        assertThat(TestAnsi.strip(painted)).isEqualTo("shrinking jar");
        // Body tokens are mid-gray (#A0A0A0) — not dim bright-black, not cyan.
        assertThat(painted).contains(Theme.colorize("shrinking", t.midGray()));
        assertThat(painted).doesNotContain(Theme.colorize("shrinking", t.darkGray()));
        assertThat(painted).doesNotContain(Theme.colorize("shrinking", t.activeStep()));
        assertThat(painted).doesNotContain(Theme.colorize("shrinking", t.brightCyan()));
    }

    @Test
    void compile_test_under_test_phase_is_prose_mid_gray_not_syntax_white() {
        // compile-test is Phase.TEST wire-wise, but labels are "compiling N sources" — not FooTest.bar.
        Theme t = Theme.active();
        String painted = CommandManager.colorDetail("Test", "compiling 12 Groovy test sources", t);
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
        String painted = CommandManager.colorDetail("Package", "package jk-engine-0.11.0.jar", t);
        assertThat(TestAnsi.strip(painted)).isEqualTo("package jk-engine-0.11.0.jar");
        assertThat(painted).contains(Theme.colorize("package", t.midGray()));
        assertThat(painted).contains(Theme.colorize("jk-engine-0.11.0.jar", t.path()));
    }

    @Test
    void compile_detail_uses_yellow_for_source_count() {
        Theme t = Theme.active();
        String painted = CommandManager.colorDetail("Compile", "compiling 42 sources", t);
        assertThat(TestAnsi.strip(painted)).isEqualTo("compiling 42 sources");
        assertThat(painted).contains(Theme.colorize("compiling", t.midGray()));
        assertThat(painted).contains(Theme.colorize("42", t.warning()));
        assertThat(painted).contains(Theme.colorize("sources", t.midGray()));
    }

    @Test
    void size_uses_yellow_number_and_gray_unit() {
        Theme t = Theme.active();
        String painted = CommandManager.colorDetail("Package", "shrunk 4.2 MiB → 1.1 MiB", t);
        assertThat(TestAnsi.strip(painted)).isEqualTo("shrunk 4.2 MiB → 1.1 MiB");
        assertThat(painted).contains(Theme.colorize("4.2", t.warning()));
        assertThat(painted).contains(Theme.colorize("1.1", t.warning()));
        assertThat(painted).contains(Theme.colorize("MiB", t.midGray()));
    }

    @Test
    void resolve_detail_colors_maven_coords() {
        String painted = CommandManager.colorDetail(
                "Resolve", "fetched com.fasterxml.jackson.core:jackson-core:2.18.0", Theme.active());
        assertThat(TestAnsi.strip(painted)).isEqualTo("fetched com.fasterxml.jackson.core:jackson-core:2.18.0");
        // Coords.gav splits group / artifact / version with their theme roles.
        assertThat(painted)
                .contains(cc.jumpkick.cli.theme.Coords.gav("com.fasterxml.jackson.core", "jackson-core", "2.18.0"));
    }

    @Test
    void fetch_detail_colors_library_short_name() {
        String painted = CommandManager.colorDetail("Resolve", "fetched jackson-core", Theme.active());
        assertThat(TestAnsi.strip(painted)).isEqualTo("fetched jackson-core");
        assertThat(painted).contains(cc.jumpkick.cli.theme.Coords.shortName("jackson-core"));
    }

    @Test
    void cache_hit_hex_is_dim_not_number_yellow() {
        Theme t = Theme.active();
        String painted = CommandManager.colorDetail("Compile", "cache hit 9aa55003", t);
        assertThat(TestAnsi.strip(painted)).isEqualTo("cache hit 9aa55003");
        // Slightly dimmer than mid-gray body prose, still not number-yellow.
        assertThat(painted).contains(Theme.colorize("9aa55003", t.darkGray()));
        assertThat(painted).doesNotContain(Theme.colorize("9aa55003", t.warning()));
    }

    @Test
    void paren_count_still_yellows_the_number() {
        Theme t = Theme.active();
        String painted = CommandManager.colorDetail("Compile", "d8 (12 classes + 3 jars)", t);
        assertThat(TestAnsi.strip(painted)).isEqualTo("d8 (12 classes + 3 jars)");
        assertThat(painted).contains(Theme.colorize("12", t.warning()));
        assertThat(painted).contains(Theme.colorize("3", t.warning()));
    }

    @Test
    void looksLikePathOrArtifact_detects_jars_and_paths() {
        assertThat(CommandManager.looksLikePathOrArtifact("lib.jar")).isTrue();
        assertThat(CommandManager.looksLikePathOrArtifact("app.aar")).isTrue();
        assertThat(CommandManager.looksLikePathOrArtifact("target/classes")).isTrue();
        assertThat(CommandManager.looksLikePathOrArtifact("sources")).isFalse();
        assertThat(CommandManager.looksLikePathOrArtifact("up-to-date")).isFalse();
    }

    @Test
    void looksLikeCoord_detects_gav() {
        assertThat(CommandManager.looksLikeCoord("com.foo:bar:1.0")).isTrue();
        assertThat(CommandManager.looksLikeCoord("com.foo:bar")).isTrue();
        assertThat(CommandManager.looksLikeCoord("lib.jar")).isFalse();
        assertThat(CommandManager.looksLikeCoord("compiling")).isFalse();
    }

    @Test
    void preflight_detail_shows_on_phase_only_tree_row() {
        var cm = CommandManager.pipeline(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdfont = false;
        cm.preflight("lock", 0, 1, "resolving dependencies");

        String all = String.join("\n", stripAll(cm.renderPipelineLines(120, 0)));
        assertThat(all).contains("Lock").contains("resolving dependencies");
    }

    @Test
    void phase_chain_shows_running_and_failed_only_newest_first() {
        var cm = CommandManager.pipeline(stream(new ByteArrayOutputStream()), "Building", false);
        cm.nerdfont = false;
        cm.stepDone("m", "s1", true, "resolve"); // success → dropped
        cm.stepDone("m", "s2", false, "compile"); // failed → stays
        cm.stepRunning("m", "s3", "test");

        String all = String.join("\n", stripAll(cm.renderPipelineLines(120, 0)));
        assertThat(all).contains("Test").contains("Compile");
        assertThat(all).doesNotContain("Resolve");
        // Running rows first (newest), then failed by finish seq.
        assertThat(all.indexOf("Test")).isLessThan(all.indexOf("Compile"));
        assertThat(all).contains(Glyphs.CROSS); // failed row icon
    }

    @Test
    void failed_phase_shows_brief_error_below() {
        var cm = CommandManager.pipeline(stream(new ByteArrayOutputStream()), "Building", false);
        cm.nerdfont = false;
        cm.stepRunning("m", "compile", "compile");
        cm.attachPhaseError("m", "compile", "compile", "javac failed: cannot find symbol");
        cm.stepDone("m", "compile", false, "compile");

        var lines = stripAll(cm.renderPipelineLines(120, 0));
        String all = String.join("\n", lines);
        assertThat(all).contains("Compile").contains("cannot find symbol");
    }

    @Test
    void attachPhaseError_uses_row_wire_phase_when_callers_pass_empty_phase() {
        // listeners pass phase=""; step key is compile-java, phase node is compile.
        var cm = CommandManager.pipeline(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdfont = false;
        cm.stepRunning("m", "compile-java", "compile");
        cm.attachPhaseError("m", "compile-java", "", "cannot find symbol Foo");
        cm.stepDone("m", "compile-java", false, "compile");

        var lines = stripAll(cm.renderPipelineLines(120, 0));
        String all = String.join("\n", lines);
        assertThat(all).contains("Compile").contains("cannot find symbol Foo");
        assertThat(all).doesNotContain("Failed\n"); // not the generic-only brief when we have a real one
    }

    @Test
    void brief_error_line_colors_message_not_rail() {
        // Rail/indent is dim; only the message is error-red (not the whole " │ Failed" string).
        Theme t = Theme.active();
        String mid = CommandManager.renderBriefErrorLine(false, "Failed");
        String last = CommandManager.renderBriefErrorLine(true, "boom");
        assertThat(mid).isEqualTo(Theme.colorize(" │  ", t.darkGray()) + Theme.colorize("Failed", t.error()));
        assertThat(last).isEqualTo(Theme.colorize("    ", t.darkGray()) + Theme.colorize("boom", t.error()));
        // Whole-line coloring would put the rail inside one error-styled span — must not.
        assertThat(mid).isNotEqualTo(Theme.colorize(" │  Failed", t.error()));
    }

    @Test
    void brief_error_under_last_tree_entry_uses_space_indent_not_rail() {
        // ╰─ then spaces, not │ under a closing branch.
        var cm = CommandManager.pipeline(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdfont = false;
        cm.stepRunning("m", "compile-java", "compile");
        cm.attachPhaseError("m", "compile-java", "compile", "boom");
        cm.stepDone("m", "compile-java", false, "compile");

        var lines = stripAll(cm.renderPipelineLines(120, 0));
        // Find the error line after the last ╰─ row
        boolean sawClose = false;
        for (String line : lines) {
            if (line.startsWith(" ╰─")) sawClose = true;
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
        var cm = CommandManager.pipeline(stream(new ByteArrayOutputStream()), "Building", false);
        cm.nerdfont = false;
        cm.stepRunning("com.foo:a", "a", "compile");
        cm.stepRunning("com.foo:b", "b", "test");
        var lines = stripAll(cm.renderPipelineLines(120, 0));
        // header + two tree rows only (no leading │, no blank │ between).
        assertThat(lines).hasSize(3);
        assertThat(lines.get(1)).startsWith(" ├─").contains("com.foo").contains("·");
        assertThat(lines.get(2)).startsWith(" ╰─").contains("com.foo").contains("·");
        for (String line : lines) {
            assertThat(line.strip()).isNotEqualTo("│");
        }
    }

    @Test
    void region_is_capped_to_terminal_height() {
        var cm = CommandManager.pipeline(stream(new ByteArrayOutputStream()), "Building", false);
        cm.height = 6;
        for (int i = 0; i < 20; i++) cm.stepRunning("m", "p" + i, "phase" + i);

        var lines = cm.renderPipelineLines(120, 0);
        assertThat(lines.size()).isLessThanOrEqualTo(6 - 1);
    }

    @Test
    void phase_chain_uses_tree_connectors() {
        var cm = CommandManager.pipeline(stream(new ByteArrayOutputStream()), "Building", false);
        cm.nerdfont = false;
        cm.stepRunning("m", "compile", "compile");
        var lines = cm.renderPipelineLines(120, 0);
        // lines[0]=header, lines[1]=single work row (closing branch) — no leading blank rail.
        assertThat(TestAnsi.strip(lines.get(1))).startsWith(" ╰─").contains("Compile");
    }

    @Test
    void completed_lines_render_below_the_phase_chain_newest_first() {
        var cm = CommandManager.pipeline(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdfont = false;
        cm.stepRunning("m", "compile", "compile");
        cm.addCompletion("✓ [13 of 17] g:a13 took 1s");
        cm.addCompletion("✓ [14 of 17] g:a14 took 1s");

        var lines = cm.renderPipelineLines(120, 0);
        // header, work row (╰─), then completions newest first.
        assertThat(TestAnsi.strip(lines.get(1))).startsWith(" ╰─").contains("Compile");
        assertThat(TestAnsi.strip(lines.get(2))).isEqualTo("    ✓ [14 of 17] g:a14 took 1s");
        assertThat(TestAnsi.strip(lines.get(3))).isEqualTo("    ✓ [13 of 17] g:a13 took 1s");
    }

    @Test
    void completed_tail_caps_and_collapses_overflow_into_a_footer() {
        var cm = CommandManager.pipeline(stream(new ByteArrayOutputStream()), "Build", false);
        cm.stepRunning("m", "compile");
        for (int i = 1; i <= 8; i++) cm.addCompletion("✓ [0" + i + " of 17] g:a" + i + " took 1s");

        var all = String.join("\n", stripAll(cm.renderPipelineLines(120, 0)));
        // Only MAX_COMPLETIONS (5) show; the newest is first; 3 collapse into the footer.
        assertThat(all).contains("    ✓ [08 of 17]").contains("    ✓ [04 of 17]");
        assertThat(all).doesNotContain("[03 of 17]");
        assertThat(all).contains("      … plus 3 more …");
    }

    @Test
    void write_above_prints_the_line_then_repaints_the_region_below() {
        var buf = new ByteArrayOutputStream();
        var cm = new CommandManager(stream(buf), true, true, 80);
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
        var cm = new CommandManager(stream(buf), true, true, 80);
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
        assertThat(CommandManager.fmtElapsed(112_000)).isEqualTo("1m 52s");
        assertThat(CommandManager.fmtElapsed(52_000)).isEqualTo("52s");
        assertThat(CommandManager.fmtElapsed(0)).isEqualTo("0s");
    }

    @Test
    void truncate_visible_cuts_at_column_keeping_escapes() {
        String colored = Theme.colorize("abcdef", Theme.active().success());
        // Hard-truncate: reserve one column for … so the line never wraps.
        String cut = CommandManager.truncateVisible(colored, 3);
        assertThat(TestAnsi.strip(cut)).isEqualTo("ab…");
        assertThat(cut).endsWith("\033[0m"); // reset appended on truncation
    }

    @Test
    void truncate_visible_returns_verbatim_when_it_fits() {
        String colored = Theme.colorize("abcdef", Theme.active().success());
        // Fits in 6 columns → original bytes preserved exactly (jk's SGR byte order).
        assertThat(CommandManager.truncateVisible(colored, 6)).isEqualTo(colored);
        assertThat(CommandManager.truncateVisible("plain", 10)).isEqualTo("plain");
    }

    @Test
    void truncate_visible_one_column_keeps_a_fitting_char() {
        // Degenerate 1-column width: fitting content survives; only longer input degrades
        // to the bare ellipsis. Empty stays empty.
        assertThat(CommandManager.truncateVisible("", 1)).isEmpty();
        assertThat(CommandManager.truncateVisible("a", 1)).isEqualTo("a");
        assertThat(TestAnsi.strip(CommandManager.truncateVisible("ab", 1))).isEqualTo("…");
    }

    private static List<String> stripAll(List<String> lines) {
        return lines.stream().map(TestAnsi::strip).toList();
    }

    private static PrintStream stream(ByteArrayOutputStream buf) {
        return new PrintStream(buf, true, StandardCharsets.UTF_8);
    }
}
