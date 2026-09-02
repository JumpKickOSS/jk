// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.testing.NoAnsi;
import cc.jumpkick.cli.theme.Rgb;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.NerdFontCaps;
import cc.jumpkick.config.SessionContext;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SpinnerTest {

    @Test
    void step_uses_pulse_circle_glyph_in_ansi() throws Exception {
        NoAnsi.forcedAnsi(() -> {
            var buf = new ByteArrayOutputStream();
            var s = new Spinner(stream(buf), "Working");
            s.step();
            assertThat(TestAnsi.strip(buf.toString(StandardCharsets.UTF_8))).contains(Spinner.PULSE_GLYPH + " Working");
            return null;
        });
    }

    @Test
    void step_uses_the_ascii_pulse_glyph_in_plain() throws Exception {
        NoAnsi.forced(() -> {
            var buf = new ByteArrayOutputStream();
            var s = new Spinner(stream(buf), "Working");
            s.step();
            assertThat(TestAnsi.strip(buf.toString(StandardCharsets.UTF_8))).contains(Glyphs.PULSE_PLAIN + " Working");
            return null;
        });
    }

    @Test
    void step_cycles_pulse_frames_without_changing_glyph_in_ansi() throws Exception {
        NoAnsi.forcedAnsi(() -> {
            String raw = pulseFrames();
            // Same solid circle every frame; only ANSI FG changes.
            assertThat(countOccurrences(raw, Spinner.PULSE_GLYPH)).isEqualTo(Spinner.PULSE_FRAMES);
            assertThat(TestAnsi.strip(raw)).doesNotContain("·");
            return null;
        });
    }

    @Test
    void step_paints_one_static_frame_in_plain() throws Exception {
        NoAnsi.forced(() -> {
            // Plain: single static frame, no multi-frame thrash.
            assertThat(countOccurrences(TestAnsi.strip(pulseFrames()), Glyphs.PULSE_PLAIN))
                    .isEqualTo(1);
            return null;
        });
    }

    /** One spinner driven through a full pulse cycle; the caller pins the mode. */
    private static String pulseFrames() {
        var buf = new ByteArrayOutputStream();
        var s = new Spinner(stream(buf), "Working");
        for (int i = 0; i < Spinner.PULSE_FRAMES; i++) {
            s.step();
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    @Test
    void update_changes_message_on_next_step() {
        var buf = new ByteArrayOutputStream();
        var s = new Spinner(stream(buf), "first");
        s.step();
        s.update("second");
        buf.reset();
        s.step();
        String visible = TestAnsi.strip(buf.toString(StandardCharsets.UTF_8));
        assertThat(visible).contains("second");
    }

    @Test
    void shrinking_message_pads_only_the_removed_tail_in_ansi() throws Exception {
        NoAnsi.forcedAnsi(() -> {
            String longMsg = "downloading temurin-25.tar.gz";
            String shortMsg = "done";
            int expectedShrink = longMsg.length() - shortMsg.length();

            var buf = new ByteArrayOutputStream();
            var s = new Spinner(stream(buf), longMsg);
            s.step();
            s.update(shortMsg);
            buf.reset();
            s.step();
            String visible = TestAnsi.strip(buf.toString(StandardCharsets.UTF_8));
            int idx = visible.indexOf(shortMsg);
            assertThat(idx).isGreaterThanOrEqualTo(0);
            long spaces = visible.substring(idx + shortMsg.length())
                    .chars()
                    .takeWhile(c -> c == ' ')
                    .count();
            assertThat(spaces).isEqualTo(expectedShrink);
            return null;
        });
    }

    @Test
    void shrinking_message_pads_nothing_in_plain() throws Exception {
        NoAnsi.forced(() -> {
            // Plain mode repaints a static line rather than overwriting in place, so there is no
            // tail to pad — the assertion the ANSI case makes must not silently hold here too.
            var buf = new ByteArrayOutputStream();
            var s = new Spinner(stream(buf), "downloading temurin-25.tar.gz");
            s.step();
            s.update("done");
            buf.reset();
            s.step();
            assertThat(buf.toString(StandardCharsets.UTF_8)).doesNotContain("  ");
            return null;
        });
    }

    @Test
    void open_pulse_styles_are_brand_blue_at_ends_and_dark_blue_at_midpoint() {
        var bright = Spinner.PULSE_OPEN_BRIGHT;
        var dim = Spinner.PULSE_OPEN_DIM;
        var colors = Spinner.buildOpenPulseStyles(Spinner.PULSE_FRAMES);
        // Ends: brand blue #3D9BFF
        assertThat(colors[0].sgrBody()).isEqualTo("38;2;" + bright.r() + ";" + bright.g() + ";" + bright.b());
        assertThat(colors[colors.length - 1].sgrBody())
                .isEqualTo("38;2;" + bright.r() + ";" + bright.g() + ";" + bright.b());
        // Midpoint: almost-black blue in the same family
        int mid = Spinner.PULSE_FRAMES / 2;
        assertThat(colors[mid].sgrBody()).isEqualTo("38;2;" + dim.r() + ";" + dim.g() + ";" + dim.b());
        // Dim is darker but still blue-dominant (not pure black).
        assertThat(dim.b()).isGreaterThan(dim.r());
        assertThat(dim.r() + dim.g() + dim.b()).isGreaterThan(0);
    }

    @Test
    void chip_pulse_styles_are_white_at_ends_and_dim_at_midpoint() {
        var dim = Rgb.hex(0x0F4786); // plan/chip blue
        var colors = Spinner.buildChipPulseStyles(Spinner.PULSE_FRAMES, dim);
        assertThat(colors[0].sgrBody()).isEqualTo("38;2;255;255;255");
        assertThat(colors[colors.length - 1].sgrBody()).isEqualTo("38;2;255;255;255");
        int mid = Spinner.PULSE_FRAMES / 2;
        assertThat(colors[mid].sgrBody()).isEqualTo("38;2;" + dim.r() + ";" + dim.g() + ";" + dim.b());
    }

    @Test
    void fill_glyph_holds_each_phase_then_advances() {
        // Phases ○ ◎ ◉ ◎, each held FILL_HOLD frames — not a repeated array.
        assertThat(Spinner.FILL_PHASES).containsExactly("\u25CB", "\u25CE", "\u25C9", "\u25CE");
        assertThat(Spinner.FILL_HOLD).isEqualTo(4);
        assertThat(Spinner.FILL_FRAMES).isEqualTo(16);
        assertThat(Spinner.fillGlyph(0)).isEqualTo("\u25CB");
        assertThat(Spinner.fillGlyph(3)).isEqualTo("\u25CB"); // still holding
        assertThat(Spinner.fillGlyph(4)).isEqualTo("\u25CE"); // phase advance
        assertThat(Spinner.fillGlyph(8)).isEqualTo("\u25C9");
        assertThat(Spinner.fillGlyph(12)).isEqualTo("\u25CE");
        assertThat(Spinner.fillGlyph(16)).isEqualTo("\u25CB");
        assertThat(Spinner.fillGlyph(-1)).isEqualTo("\u25CE"); // last frame of cycle
    }

    @Test
    void close_clears_line_and_restores_cursor_in_ansi() throws Exception {
        NoAnsi.forcedAnsi(() -> {
            String out = closeFrame();
            assertThat(out).contains("\r\033[K"); // clear current line
            assertThat(out).contains("\033[?25h"); // show cursor
            return null;
        });
    }

    @Test
    void close_emits_a_newline_and_no_cursor_control_in_plain() throws Exception {
        NoAnsi.forced(() -> {
            String out = closeFrame();
            assertThat(out).contains("\n");
            assertThat(out).doesNotContain("\033[K");
            assertThat(out).doesNotContain("\033[?25h");
            return null;
        });
    }

    /** Output of one close after a step; the caller pins the mode. */
    private static String closeFrame() {
        var buf = new ByteArrayOutputStream();
        var s = new Spinner(stream(buf), "Working");
        s.step();
        buf.reset();
        s.close();
        return buf.toString(StandardCharsets.UTF_8);
    }

    @Test
    void show_emits_osc94_indeterminate_indicator_in_ansi() throws Exception {
        NoAnsi.forcedAnsi(() -> {
            String out = showFrame();
            assertThat(out).contains("\033]9;4;3\007"); // indeterminate
            assertThat(out).contains("\033]9;4;0\007"); // cleared on close
            assertThat(out.indexOf("\033]9;4;3\007")).isLessThan(out.indexOf("\033]9;4;0\007"));
            return null;
        });
    }

    @Test
    void show_emits_no_osc94_indicator_in_plain() throws Exception {
        NoAnsi.forced(() -> {
            assertThat(showFrame()).doesNotContain("\033]9;4;3\007");
            return null;
        });
    }

    /** Output of one show/close cycle; the caller pins the mode. */
    private static String showFrame() {
        var buf = new ByteArrayOutputStream();
        try (var s = Spinner.show(stream(buf), "Working")) {
            Thread.yield();
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    @Test
    void each_step_reasserts_osc94_indeterminate_in_ansi() throws Exception {
        NoAnsi.forcedAnsi(() -> {
            assertThat(countOccurrences(threeSteps(), "\033]9;4;3\007")).isEqualTo(3);
            return null;
        });
    }

    @Test
    void no_step_asserts_osc94_in_plain() throws Exception {
        NoAnsi.forced(() -> {
            assertThat(countOccurrences(threeSteps(), "\033]9;4;3\007")).isZero();
            return null;
        });
    }

    /** Output of three steps; the caller pins the mode. */
    private static String threeSteps() {
        var buf = new ByteArrayOutputStream();
        var s = new Spinner(stream(buf), "Working");
        s.step();
        s.step();
        s.step();
        return buf.toString(StandardCharsets.UTF_8);
    }

    @Test
    void close_clear_precedes_show_cursor_in_ansi() throws Exception {
        NoAnsi.forcedAnsi(() -> {
            String out = closeFrame();
            assertThat(out.indexOf("\033]9;4;0\007")).isLessThan(out.indexOf("\033[?25h"));
            return null;
        });
    }

    @Test
    void wedge_frame_uses_pulse_glyph_and_command_on_chip() throws Exception {
        // Pinned ANSI: the glyph assertion is mode-dependent even though nothing here branches —
        // in plain mode Theme.colorize routes through PlainAscii, which rewrites the circle to '*'.
        NoAnsi.forcedAnsi(() -> {
            var colors = Spinner.buildChipPulseStyles(
                    Spinner.PULSE_FRAMES, Theme.active().planBadgeColor());
            String visible = TestAnsi.strip(
                    Spinner.renderWedgeFrame(0, "Status", "Analyzing status...", NerdFontCaps.NONE, colors));
            assertThat(visible).contains(Spinner.PULSE_GLYPH);
            assertThat(visible).contains("Status");
            assertThat(visible).contains("Analyzing status...");
            // Settled menu glyph must not appear while analyzing.
            assertThat(visible).doesNotContain(Glyphs.MENU);
            return null;
        });
    }

    @Test
    void wedge_step_paints_chip_then_clears_on_close_in_ansi() throws Exception {
        NoAnsi.forcedAnsi(() -> {
            var buf = new ByteArrayOutputStream();
            var s = Spinner.wedge(stream(buf), "Status", "Analyzing status...");
            s.step();
            String painted = TestAnsi.strip(buf.toString(StandardCharsets.UTF_8));
            assertThat(painted).contains("Status");
            assertThat(painted).contains("Analyzing status...");
            assertThat(painted).contains(Spinner.PULSE_GLYPH);

            buf.reset();
            s.close();
            String closed = buf.toString(StandardCharsets.UTF_8);
            assertThat(closed).contains("\r\033[K"); // clear current line on close
            assertThat(closed).contains("\033[?25h"); // show cursor
            return null;
        });
    }

    @Test
    void wedge_step_paints_plain_working_and_done_lines_in_plain() throws Exception {
        NoAnsi.forced(() -> {
            var buf = new ByteArrayOutputStream();
            var s = Spinner.wedge(stream(buf), "Status", "Analyzing status...");
            s.step();
            // Plain multi-line working frame.
            assertThat(TestAnsi.strip(buf.toString(StandardCharsets.UTF_8)).trim())
                    .isEqualTo("jk: * Status > Analyzing status... - working...");

            buf.reset();
            s.close();
            assertThat(TestAnsi.strip(buf.toString(StandardCharsets.UTF_8)).trim())
                    .isEqualTo("jk: * Status > Analyzing status... - done.");
            return null;
        });
    }

    @Test
    void plain_working_and_done_line_shapes() {
        assertThat(Spinner.plainWorkingLine("Format", "Examining source files"))
                .isEqualTo("jk: * Format > Examining source files - working...");
        assertThat(Spinner.plainDoneLine("Format", "Examining source files"))
                .isEqualTo("jk: * Format > Examining source files - done.");
        assertThat(Spinner.plainWorkingLine(null, "Cleaning")).isEqualTo("jk: * Cleaning - working...");
    }

    @Test
    void plain_heartbeat_only_after_60s() throws Exception {
        NoAnsi.forced(() -> {
            var buf = new ByteArrayOutputStream();
            var clock = new AtomicLong(1_000L);
            var s = Spinner.wedge(stream(buf), "Format", "Examining");
            s.clockForTests(clock::get);
            s.step(); // start
            assertThat(countOccurrences(buf.toString(StandardCharsets.UTF_8), "working..."))
                    .isEqualTo(1);
            clock.addAndGet(30_000L);
            s.step(); // still within 60s — no second line
            assertThat(countOccurrences(buf.toString(StandardCharsets.UTF_8), "working..."))
                    .isEqualTo(1);
            clock.addAndGet(30_000L); // total +60s
            s.step();
            assertThat(countOccurrences(buf.toString(StandardCharsets.UTF_8), "working..."))
                    .isEqualTo(2);
            s.close();
            assertThat(buf.toString(StandardCharsets.UTF_8)).contains("done.");
            assertThat(buf.toString(StandardCharsets.UTF_8)).doesNotContain("\u001B[");
            assertThat(buf.toString(StandardCharsets.UTF_8)).doesNotContain(Spinner.PULSE_GLYPH);
            return null;
        });
    }

    @Test
    void the_pin_decides_the_glyph_even_with_the_static_left_in_plain() throws Exception {
        // The mechanism, stated the way stated its stamp boundary: poison the process
        // static in the plain direction, then assert the pinned scope still renders ANSI. If the
        // pin ever stopped out-ranking the static, every paired case above would quietly assert
        // whatever the last test to write the global happened to leave behind.
        SessionContext.installConfig(JkConfig.empty().withNoAnsi(true));

        var plainBuf = new ByteArrayOutputStream();
        new Spinner(stream(plainBuf), "Working").step();
        assertThat(TestAnsi.strip(plainBuf.toString(StandardCharsets.UTF_8))).contains(Glyphs.PULSE_PLAIN + " Working");

        NoAnsi.forcedAnsi(() -> {
            var buf = new ByteArrayOutputStream();
            new Spinner(stream(buf), "Working").step();
            assertThat(TestAnsi.strip(buf.toString(StandardCharsets.UTF_8))).contains(Spinner.PULSE_GLYPH + " Working");
            return null;
        });
    }

    @Test
    void a_plain_build_does_not_poison_the_pulse_cache_for_a_later_ansi_build() throws Exception {
        // . PULSE_CACHE memoizes Style[] and Theme.bright bakes the colour decision into
        // every Style it returns — a colourless one has an empty SGR body. The key carried
        // Theme.active(), which looks like it carries the mode and does not: one JkDarkTheme
        // instance serves both modes and re-derives the answer per call. So the FIRST caller's mode
        // won for the life of the JVM, and a plain frame rendered before an ANSI one silently
        // stripped its colour. Latent in production, not only in tests.
        //
        // Order matters: plain first, so a key that has lost the decision hands these styles back.
        NoAnsi.forced(() -> Spinner.buildOpenPulseStyles(Spinner.PULSE_FRAMES));

        NoAnsi.forcedAnsi(() -> {
            var colors = Spinner.buildOpenPulseStyles(Spinner.PULSE_FRAMES);
            var bright = Spinner.PULSE_OPEN_BRIGHT;
            assertThat(colors[0].sgrBody())
                    .describedAs("an ANSI build must not inherit the plain build's colourless styles")
                    .isEqualTo("38;2;" + bright.r() + ";" + bright.g() + ";" + bright.b());
            return null;
        });
    }

    private static PrintStream stream(ByteArrayOutputStream buf) {
        return new PrintStream(buf, true, StandardCharsets.UTF_8);
    }

    private static long countOccurrences(String haystack, String needle) {
        long count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
