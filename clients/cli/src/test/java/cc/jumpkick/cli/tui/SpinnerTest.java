// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.NerdFontCaps;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class SpinnerTest {

    @Test
    void step_uses_pulse_circle_glyph() {
        var buf = new ByteArrayOutputStream();
        var s = new Spinner(stream(buf), "Working");
        s.step();
        String visible = TestAnsi.strip(buf.toString(StandardCharsets.UTF_8));
        // ANSI: ● Working; plain / CI: * Working
        if (cc.jumpkick.cli.theme.Theme.active().isAnsi()) {
            assertThat(visible).contains(Spinner.PULSE_GLYPH + " Working");
        } else {
            assertThat(visible).contains(Glyphs.PULSE_PLAIN + " Working");
        }
    }

    @Test
    void step_cycles_pulse_frames_without_changing_glyph() {
        var buf = new ByteArrayOutputStream();
        var s = new Spinner(stream(buf), "Working");
        for (int i = 0; i < Spinner.PULSE_FRAMES; i++) {
            s.step();
        }
        String raw = buf.toString(StandardCharsets.UTF_8);
        if (!cc.jumpkick.cli.theme.Theme.active().isAnsi()) {
            // Plain: single static frame, no multi-frame thrash.
            assertThat(countOccurrences(TestAnsi.strip(raw), Glyphs.PULSE_PLAIN))
                    .isEqualTo(1);
            return;
        }
        // Same solid circle every frame; only ANSI FG changes.
        assertThat(countOccurrences(raw, Spinner.PULSE_GLYPH)).isEqualTo(Spinner.PULSE_FRAMES);
        assertThat(TestAnsi.strip(raw)).doesNotContain("·");
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
    void shrinking_message_pads_only_the_removed_tail() {
        if (!cc.jumpkick.cli.theme.Theme.active().isAnsi()) {
            // Plain mode does not pad tails (static single frame).
            return;
        }
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
    }

    @Test
    void open_pulse_styles_are_brand_blue_at_ends_and_dark_blue_at_midpoint() {
        var bright = Spinner.PULSE_OPEN_BRIGHT;
        var dim = Spinner.PULSE_OPEN_DIM;
        var colors = Spinner.buildOpenPulseStyles(Spinner.PULSE_FRAMES);
        // Ends: brand blue #3D9BFF
        assertThat(colors[0].toAnsi()).isEqualTo("38;2;" + bright.r() + ";" + bright.g() + ";" + bright.b());
        assertThat(colors[colors.length - 1].toAnsi())
                .isEqualTo("38;2;" + bright.r() + ";" + bright.g() + ";" + bright.b());
        // Midpoint: almost-black blue in the same family
        int mid = Spinner.PULSE_FRAMES / 2;
        assertThat(colors[mid].toAnsi()).isEqualTo("38;2;" + dim.r() + ";" + dim.g() + ";" + dim.b());
        // Dim is darker but still blue-dominant (not pure black).
        assertThat(dim.b()).isGreaterThan(dim.r());
        assertThat(dim.r() + dim.g() + dim.b()).isGreaterThan(0);
    }

    @Test
    void chip_pulse_styles_are_white_at_ends_and_dim_at_midpoint() {
        var dim = cc.jumpkick.cli.theme.Rgb.hex(0x0F4786); // plan/chip blue
        var colors = Spinner.buildChipPulseStyles(Spinner.PULSE_FRAMES, dim);
        assertThat(colors[0].toAnsi()).isEqualTo("38;2;255;255;255");
        assertThat(colors[colors.length - 1].toAnsi()).isEqualTo("38;2;255;255;255");
        int mid = Spinner.PULSE_FRAMES / 2;
        assertThat(colors[mid].toAnsi()).isEqualTo("38;2;" + dim.r() + ";" + dim.g() + ";" + dim.b());
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
    void close_clears_line_and_restores_cursor() {
        var buf = new ByteArrayOutputStream();
        var s = new Spinner(stream(buf), "Working");
        s.step();
        buf.reset();
        s.close();
        String out = buf.toString(StandardCharsets.UTF_8);
        if (!cc.jumpkick.cli.theme.Theme.active().isAnsi()) {
            // Plain: newline only (no cursor hide / clear sequence).
            assertThat(out).contains("\n");
            return;
        }
        assertThat(out).contains("\r\033[K"); // clear current line
        assertThat(out).contains("\033[?25h"); // show cursor
    }

    @Test
    void show_emits_osc94_indeterminate_indicator() {
        var buf = new ByteArrayOutputStream();
        try (var s = Spinner.show(stream(buf), "Working")) {
            Thread.yield();
        }
        String out = buf.toString(StandardCharsets.UTF_8);
        if (!cc.jumpkick.cli.theme.Theme.active().isAnsi()) {
            assertThat(out).doesNotContain("\033]9;4;3\007");
            return;
        }
        assertThat(out).contains("\033]9;4;3\007"); // indeterminate
        assertThat(out).contains("\033]9;4;0\007"); // cleared on close
        assertThat(out.indexOf("\033]9;4;3\007")).isLessThan(out.indexOf("\033]9;4;0\007"));
    }

    @Test
    void each_step_reasserts_osc94_indeterminate() {
        var buf = new ByteArrayOutputStream();
        var s = new Spinner(stream(buf), "Working");
        s.step();
        s.step();
        s.step();
        String out = buf.toString(StandardCharsets.UTF_8);
        if (!cc.jumpkick.cli.theme.Theme.active().isAnsi()) {
            assertThat(countOccurrences(out, "\033]9;4;3\007")).isZero();
            return;
        }
        long count = countOccurrences(out, "\033]9;4;3\007");
        assertThat(count).isEqualTo(3);
    }

    @Test
    void close_clear_precedes_show_cursor() {
        var buf = new ByteArrayOutputStream();
        var s = new Spinner(stream(buf), "Working");
        s.step();
        buf.reset();
        s.close();
        String out = buf.toString(StandardCharsets.UTF_8);
        if (!cc.jumpkick.cli.theme.Theme.active().isAnsi()) {
            return;
        }
        assertThat(out.indexOf("\033]9;4;0\007")).isLessThan(out.indexOf("\033[?25h"));
    }

    @Test
    void wedge_frame_uses_pulse_glyph_and_command_on_chip() {
        var colors = Spinner.buildChipPulseStyles(
                Spinner.PULSE_FRAMES, cc.jumpkick.cli.theme.Theme.active().planBadgeColor());
        String visible =
                TestAnsi.strip(Spinner.renderWedgeFrame(0, "Status", "Analyzing status...", NerdFontCaps.NONE, colors));
        assertThat(visible).contains(Spinner.PULSE_GLYPH);
        assertThat(visible).contains("Status");
        assertThat(visible).contains("Analyzing status...");
        // Settled menu glyph must not appear while analyzing.
        assertThat(visible).doesNotContain(Glyphs.MENU);
    }

    @Test
    void wedge_step_paints_chip_then_clears_on_close() {
        var buf = new ByteArrayOutputStream();
        var s = Spinner.wedge(stream(buf), "Status", "Analyzing status...");
        s.step();
        String painted = TestAnsi.strip(buf.toString(StandardCharsets.UTF_8));
        assertThat(painted).contains("Status");
        assertThat(painted).contains("Analyzing status...");
        if (cc.jumpkick.cli.theme.Theme.active().isAnsi()) {
            assertThat(painted).contains(Spinner.PULSE_GLYPH);
        } else {
            // Plain multi-line working frame.
            assertThat(painted.trim()).isEqualTo("jk: * Status > Analyzing status... - working...");
        }
        buf.reset();
        s.close();
        String closed = buf.toString(StandardCharsets.UTF_8);
        if (!cc.jumpkick.cli.theme.Theme.active().isAnsi()) {
            assertThat(TestAnsi.strip(closed).trim()).isEqualTo("jk: * Status > Analyzing status... - done.");
            return;
        }
        assertThat(closed).contains("\r\033[K"); // clear current line on close
        assertThat(closed).contains("\033[?25h"); // show cursor
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
        withNoAnsi(() -> {
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

    private static <T> T withNoAnsi(Supplier<T> body) throws Exception {
        JkConfig noAnsi = JkConfig.empty().withNoAnsi(Optional.of(true));
        Session original = SessionContext.current();
        try {
            return SessionContext.where(original.withConfig(noAnsi), body::get);
        } finally {
            SessionContext.install(original);
        }
    }
}
