// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SpinnerTest {

    @Test
    void step_uses_pulse_circle_glyph() {
        var buf = new ByteArrayOutputStream();
        var s = new Spinner(stream(buf), "Working");
        s.step();
        String visible = stripAnsi(buf.toString(StandardCharsets.UTF_8));
        assertThat(visible).contains(Spinner.PULSE_GLYPH + " Working");
    }

    @Test
    void step_cycles_pulse_frames_without_changing_glyph() {
        var buf = new ByteArrayOutputStream();
        var s = new Spinner(stream(buf), "Working");
        for (int i = 0; i < Spinner.PULSE_FRAMES; i++) {
            s.step();
        }
        String raw = buf.toString(StandardCharsets.UTF_8);
        // Same solid circle every frame; only ANSI FG changes.
        assertThat(countOccurrences(raw, Spinner.PULSE_GLYPH)).isEqualTo(Spinner.PULSE_FRAMES);
        assertThat(stripAnsi(raw)).doesNotContain("·");
    }

    @Test
    void update_changes_message_on_next_step() {
        var buf = new ByteArrayOutputStream();
        var s = new Spinner(stream(buf), "first");
        s.step();
        s.update("second");
        buf.reset();
        s.step();
        String visible = stripAnsi(buf.toString(StandardCharsets.UTF_8));
        assertThat(visible).contains("second");
    }

    @Test
    void shrinking_message_pads_only_the_removed_tail() {
        String longMsg = "downloading temurin-25.tar.gz";
        String shortMsg = "done";
        int expectedShrink = longMsg.length() - shortMsg.length();

        var buf = new ByteArrayOutputStream();
        var s = new Spinner(stream(buf), longMsg);
        s.step();
        s.update(shortMsg);
        buf.reset();
        s.step();
        String visible = stripAnsi(buf.toString(StandardCharsets.UTF_8));
        int idx = visible.indexOf(shortMsg);
        assertThat(idx).isGreaterThanOrEqualTo(0);
        long spaces = visible.substring(idx + shortMsg.length())
                .chars()
                .takeWhile(c -> c == ' ')
                .count();
        assertThat(spaces).isEqualTo(expectedShrink);
    }

    @Test
    void pulse_styles_are_white_at_ends_and_dim_at_midpoint() {
        var dim = Spinner.PULSE_DIM;
        var colors = Spinner.buildPulseStyles(Spinner.PULSE_FRAMES, dim);
        // Ends: white #FFFFFF
        assertThat(colors[0].toAnsi()).isEqualTo("38;2;255;255;255");
        assertThat(colors[colors.length - 1].toAnsi()).isEqualTo("38;2;255;255;255");
        // Midpoint: near dim end
        int mid = Spinner.PULSE_FRAMES / 2;
        assertThat(colors[mid].toAnsi()).isEqualTo("38;2;" + dim.r() + ";" + dim.g() + ";" + dim.b());
    }

    @Test
    void close_clears_line_and_restores_cursor() {
        var buf = new ByteArrayOutputStream();
        var s = new Spinner(stream(buf), "Working");
        s.step();
        buf.reset();
        s.close();
        String out = buf.toString(StandardCharsets.UTF_8);
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
        assertThat(out.indexOf("\033]9;4;0\007")).isLessThan(out.indexOf("\033[?25h"));
    }

    private static PrintStream stream(ByteArrayOutputStream buf) {
        return new PrintStream(buf, true, StandardCharsets.UTF_8);
    }

    private static String stripAnsi(String s) {
        return s.replaceAll("\033\\[[0-9;?]*[a-zA-Z]", "");
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
