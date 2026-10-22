// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.tui.StartupPulse.Gate;
import cc.jumpkick.terminal.Ansi;
import cc.jumpkick.testing.FakeClock;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StartupPulseTest {

    private final FakeClock clock = new FakeClock();
    private long nowMs;
    private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

    private StartupPulse pulse(boolean ansi) {
        return new StartupPulse(new PrintStream(buf, true, StandardCharsets.UTF_8), ansi, false, clock);
    }

    private void at(long ms) {
        clock.advance(Duration.ofMillis(ms - nowMs));
        nowMs = ms;
    }

    private String out() {
        return buf.toString(StandardCharsets.UTF_8);
    }

    @Test
    void the_first_paint_names_the_current_phase() {
        StartupPulse p = pulse(true);
        at(250);
        p.tick();
        assertThat(p.shown()).isEqualTo("Initializing");
        assertThat(out()).startsWith(System.lineSeparator()).contains(" ● Initializing…");
    }

    @Test
    void a_later_phase_shows_only_once_it_has_lasted_the_gate() {
        StartupPulse p = pulse(true);
        at(250);
        p.tick();
        at(300);
        p.enter("Starting engine");
        at(500);
        p.tick();
        assertThat(p.shown()).isEqualTo("Initializing");
        at(550);
        p.tick();
        assertThat(p.shown()).isEqualTo("Starting engine");
    }

    @Test
    void a_fast_phase_is_never_shown() {
        StartupPulse p = pulse(false);
        at(250);
        p.tick();
        at(300);
        p.enter("Connecting to engine");
        at(340);
        p.enter("Waiting for engine");
        for (long t = 340; t <= 660; t += 80) {
            at(t);
            p.tick();
        }
        p.close();
        assertThat(out())
                .isEqualTo(System.lineSeparator() + " * Initializing... waiting for engine... Done."
                        + System.lineSeparator());
    }

    @Test
    void ansi_release_wipes_the_row_and_steps_back_onto_the_blank() {
        StartupPulse p = pulse(true);
        at(250);
        p.tick();
        p.close();
        assertThat(out()).endsWith(Ansi.CLEAR_LINE + Ansi.CSI + "1A");
        int before = out().length();
        at(1000);
        assertThat(p.tick()).isFalse();
        assertThat(out()).hasSize(before);
    }

    @Test
    void a_release_before_the_first_paint_prints_nothing() {
        StartupPulse p = pulse(false);
        p.close();
        at(250);
        assertThat(p.tick()).isFalse();
        assertThat(out()).isEmpty();
    }

    @Test
    void elapsed_seconds_follow_the_label_after_three_seconds() {
        StartupPulse p = pulse(true);
        at(250);
        p.tick();
        assertThat(out()).doesNotContain("s" + Ansi.RESET);
        at(4_200);
        p.tick();
        assertThat(out()).contains(" 4s");
    }

    @Test
    void the_gate_follows_argv_and_environment() {
        Map<String, String> none = Map.of();
        assertThat(StartupPulse.gate(new String[] {"build"}, none::get)).isEqualTo(Gate.ANSI);
        assertThat(StartupPulse.gate(new String[] {"build", "--no-ansi"}, none::get))
                .isEqualTo(Gate.PLAIN);
        assertThat(StartupPulse.gate(new String[] {"--no-ansi", "build"}, Map.of("JK_FORCE_ANSI", "1")::get))
                .isEqualTo(Gate.ANSI);
        for (String off : new String[] {"-q", "--quiet", "--no-progress", "-O", "--output=json", "--help", "-V"}) {
            assertThat(StartupPulse.gate(new String[] {"build", off}, none::get))
                    .as(off)
                    .isEqualTo(Gate.OFF);
        }
        assertThat(StartupPulse.gate(new String[] {"run", "app", "--", "-q"}, none::get))
                .isEqualTo(Gate.ANSI);
        assertThat(StartupPulse.gate(new String[] {"build"}, Map.of("CI", "true")::get))
                .isEqualTo(Gate.OFF);
        assertThat(StartupPulse.gate(new String[] {"build"}, Map.of("TERM", "dumb")::get))
                .isEqualTo(Gate.OFF);
        assertThat(StartupPulse.gate(new String[] {"build"}, Map.of("JK_QUIET", "1")::get))
                .isEqualTo(Gate.OFF);
    }

    @Test
    void color_follows_the_color_switches() {
        Map<String, String> none = Map.of();
        assertThat(StartupPulse.colorWanted(new String[] {"build"}, none::get)).isTrue();
        assertThat(StartupPulse.colorWanted(new String[] {"--color", "never"}, none::get))
                .isFalse();
        assertThat(StartupPulse.colorWanted(new String[] {"--color=always"}, Map.of("NO_COLOR", "1")::get))
                .isTrue();
        assertThat(StartupPulse.colorWanted(new String[] {"build"}, Map.of("NO_COLOR", "1")::get))
                .isFalse();
    }
}
