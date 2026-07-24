// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.theme.Rgb;
import cc.jumpkick.cli.theme.Theme;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
        assertThat(stripAnsi(buf.toString(StandardCharsets.UTF_8))).contains(Spinner.PULSE_GLYPH + " Locking…");
    }

    @Test
    void finish_success_freezes_spinner_then_prints_green_check_line() {
        var buf = new ByteArrayOutputStream();
        var cm = new CommandManager(stream(buf), true);
        cm.label("Syncing");
        cm.finishSuccess("Finished syncing 13 artifacts");

        String raw = buf.toString(StandardCharsets.UTF_8);
        String visible = stripAnsi(raw);
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

        String visible = stripAnsi(buf.toString(StandardCharsets.UTF_8));
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

        String visible = stripAnsi(buf.toString(StandardCharsets.UTF_8));
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
        assertThat(stripAnsi(raw)).contains("✘ Failed to sync remote artifacts");
        assertThat(raw).contains(Theme.colorize("✘", Theme.active().error()));
    }

    @Test
    void render_canceled_settles_spinner_without_printing_the_cancel_notice() {
        var buf = new ByteArrayOutputStream();
        var cm = new CommandManager(stream(buf), true);
        cm.label("Locking");
        cm.renderCanceled();

        String visible = stripAnsi(buf.toString(StandardCharsets.UTF_8));
        assertThat(visible).contains(Spinner.PULSE_GLYPH + " Locking…");
        // The notice itself is GlobalCancel's job; the component only settles.
        assertThat(visible).doesNotContain("Canceled");
        assertThat(buf.toString(StandardCharsets.UTF_8)).contains("\033[?25h");
        // …but the component supplies the pipeline-named cancel text.
        assertThat(cm.canceledMessage()).isEqualTo("Locking canceled by user");
    }

    @Test
    void non_animated_mode_prints_only_the_result_line() {
        var buf = new ByteArrayOutputStream();
        var cm = new CommandManager(stream(buf), false); // pipe / --quiet
        cm.label("Locking");
        cm.tick(); // animator never runs; harmless if poked
        cm.finishSuccess("done");

        String raw = buf.toString(StandardCharsets.UTF_8);
        assertThat(stripAnsi(raw)).contains("✓ Locking Successful: done");
        assertThat(stripAnsi(raw)).doesNotContain("Locking…"); // no spinner line
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
    void header_shows_a_wallclock_countdown_from_the_estimate() {
        var cm = CommandManager.pipeline(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdfont = false;
        cm.progress(50, 100);

        // No estimate set → the clock counts elapsed up from 0s (4s elapsed → "4s").
        String up = cm.renderPipelineLines(120, 4_000).get(0);
        assertThat(stripAnsi(up)).contains("4s");

        // Seeded with a 60s estimate, the clock counts down by pure wall-clock: at 4s
        // elapsed, 56s remain — independent of the bar's numerator/denominator.
        cm.setEtaEstimate(60_000);
        String header = cm.renderPipelineLines(120, 4_000).get(0);
        assertThat(stripAnsi(header)).contains("56s");
        // The clock is yellow; the · separator is bright-black.
        assertThat(header).contains(Theme.colorize("56s", Theme.active().warning()));
        assertThat(header).contains(Theme.colorize("·", Theme.active().darkGray()));
    }

    @Test
    void eta_countdown_flips_to_count_up_when_the_build_overruns_the_estimate() {
        var cm = CommandManager.pipeline(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdfont = false;
        cm.setEtaEstimate(10_000); // 10s estimate
        // 15s elapsed → 5s overrun → clock flips to "+5s".
        assertThat(stripAnsi(cm.renderPipelineLines(120, 15_000).get(0))).contains("+5s");
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
        assertThat(stripAnsi(raw.get(0)))
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
        assertThat(stripAnsi(header)).contains(Spinner.PULSE_GLYPH + " Build " + Glyphs.SEGMENT_END_NERD);
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
    void tree_rows_use_blue_spinner_and_green_phase_not_background_pills() {
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
        assertThat(joined).contains(Theme.colorize("Compile", t.success()));
        assertThat(joined).contains(Theme.colorize("Test", t.error()));
        assertThat(joined).contains(Theme.colorize("·", t.darkGray()));
        assertThat(joined).doesNotContain(Theme.colorize(" Compile ", t.phaseRunningPill()));
        assertThat(joined).doesNotContain(Theme.colorize(" Test ", t.phaseFailedPill()));
        assertThat(joined).doesNotContain(Glyphs.PILL_LEFT_NERD);
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
        assertThat(stripAnsi(lines.get(1))).startsWith(" ╰─").contains("Compile");
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
        assertThat(stripAnsi(lines.get(1))).startsWith(" ╰─").contains("Compile");
        assertThat(stripAnsi(lines.get(2))).isEqualTo("    ✓ [14 of 17] g:a14 took 1s");
        assertThat(stripAnsi(lines.get(3))).isEqualTo("    ✓ [13 of 17] g:a13 took 1s");
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

        String visible = stripAnsi(buf.toString(StandardCharsets.UTF_8));
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
        assertThat(stripAnsi(buf.toString(StandardCharsets.UTF_8)))
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
        String cut = CommandManager.truncateVisible(colored, 3);
        assertThat(stripAnsi(cut)).isEqualTo("abc");
        assertThat(cut).endsWith("\033[0m"); // reset appended on truncation
    }

    @Test
    void truncate_visible_returns_verbatim_when_it_fits() {
        String colored = Theme.colorize("abcdef", Theme.active().success());
        // Fits in 6 columns → original bytes preserved exactly (jk's SGR byte order).
        assertThat(CommandManager.truncateVisible(colored, 6)).isEqualTo(colored);
        assertThat(CommandManager.truncateVisible("plain", 10)).isEqualTo("plain");
    }

    private static List<String> stripAll(List<String> lines) {
        return lines.stream().map(CommandManagerTest::stripAnsi).toList();
    }

    private static PrintStream stream(ByteArrayOutputStream buf) {
        return new PrintStream(buf, true, StandardCharsets.UTF_8);
    }

    private static String stripAnsi(String s) {
        return s.replaceAll("\033\\[[0-9;?]*[a-zA-Z]", "");
    }
}
