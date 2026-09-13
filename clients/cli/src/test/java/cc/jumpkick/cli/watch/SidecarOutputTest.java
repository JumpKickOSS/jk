// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.watch;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.testing.NoAnsi;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.terminal.Ansi;
import cc.jumpkick.testing.FakeClock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Every sidecar line says where it came from — in colour on a terminal, as a typed event on the wire. */
class SidecarOutputTest {

    @Test
    void a_name_keeps_one_colour_and_two_names_usually_differ() {
        assertThat(SidecarOutput.style("web")).isEqualTo(SidecarOutput.style("web"));
        assertThat(SidecarOutput.style("web")).isNotEqualTo(SidecarOutput.style("docs"));
        assertThat(SidecarOutput.prefix("web", true))
                .startsWith(Ansi.CSI)
                .contains("web" + Ansi.RESET)
                .endsWith(Sidecars.PREFIX_SEPARATOR);
    }

    @Test
    void with_colour_off_the_prefix_is_the_bare_name_and_bar() {
        List<String> lines = new ArrayList<>();
        SidecarOutput.terminal(lines::add, false).output("web", "stdout", "vite ready");
        assertThat(lines).containsExactly("web" + Sidecars.PREFIX_SEPARATOR + "vite ready");
    }

    @Test
    void the_terminal_listener_follows_the_session_s_no_ansi_setting() throws Exception {
        List<String> plain = new ArrayList<>();
        NoAnsi.forced(() -> {
            assertThat(GlobalConfig.colorEnabled()).isFalse();
            SidecarOutput.terminal(plain::add).output("web", "stdout", "x");
            return null;
        });
        assertThat(plain).containsExactly("web" + Sidecars.PREFIX_SEPARATOR + "x");

        List<String> coloured = new ArrayList<>();
        NoAnsi.forcedAnsi(() -> {
            SidecarOutput.terminal(coloured::add).output("web", "stdout", "x");
            return null;
        });
        assertThat(coloured.getFirst()).startsWith(Ansi.CSI).endsWith(Sidecars.PREFIX_SEPARATOR + "x");
    }

    @Test
    void exits_read_as_one_sentence_with_what_happens_next() {
        List<String> lines = new ArrayList<>();
        Sidecars.Listener t = SidecarOutput.terminal(lines::add, false);
        t.exited("web", 41, 1, -1, false);
        t.exited("web", 42, 1, 500, false);
        t.exited("web", 43, 1, -1, true);
        assertThat(lines)
                .containsExactly(
                        "web exited with 1",
                        "web exited with 1 — restarting in 500 ms",
                        "web exited with 1 — gave up after " + Sidecars.MAX_RESTARTS + " restarts");
    }

    @Test
    void jsonl_events_carry_the_documented_fields() {
        List<String> lines = new ArrayList<>();
        FakeClock clock = new FakeClock();
        Sidecars.Listener j = SidecarOutput.jsonl(lines::add, clock);
        j.started("web", 4242);
        j.output("web", "stderr", "warn: \"quoted\"");
        j.ready("web", "http://localhost:5173", true);
        j.exited("web", 4242, 1, 500, false);
        j.exited("web", 4243, 1, -1, true);
        assertThat(lines).hasSize(5);
        for (String line : lines) {
            assertThat(Jsonl.intValue(line, "schema", -1)).isEqualTo(1);
            assertThat(Jsonl.longValue(line, "ts", -1))
                    .as("stamped by the session clock")
                    .isEqualTo(clock.millis());
            assertThat(Jsonl.str(line, "name")).isEqualTo("web");
        }
        assertThat(Jsonl.str(lines.get(0), "type")).isEqualTo("sidecar-started");
        assertThat(Jsonl.longValue(lines.get(0), "pid", -1)).isEqualTo(4242);
        assertThat(Jsonl.str(lines.get(1), "type")).isEqualTo("sidecar-output");
        assertThat(Jsonl.str(lines.get(1), "stream")).isEqualTo("stderr");
        assertThat(Jsonl.str(lines.get(1), "line")).isEqualTo("warn: \"quoted\"");
        assertThat(Jsonl.str(lines.get(2), "type")).isEqualTo("sidecar-ready");
        assertThat(Jsonl.str(lines.get(2), "url")).isEqualTo("http://localhost:5173");
        assertThat(Jsonl.bool(lines.get(2), "frontDoor", false)).isTrue();
        assertThat(Jsonl.str(lines.get(3), "type")).isEqualTo("sidecar-exited");
        assertThat(Jsonl.intValue(lines.get(3), "exit", -1)).isEqualTo(1);
        assertThat(Jsonl.longValue(lines.get(3), "restartInMs", -1)).isEqualTo(500);
        assertThat(Jsonl.has(lines.get(3), "gaveUp")).isFalse();
        assertThat(Jsonl.bool(lines.get(4), "gaveUp", false)).isTrue();
        assertThat(Jsonl.has(lines.get(4), "restartInMs")).isFalse();
    }

    @Test
    void the_app_s_own_events_name_the_stream_and_the_pid() {
        FakeClock clock = new FakeClock();
        String started = SidecarOutput.appStarted(clock, 7);
        String out = SidecarOutput.appOutput(clock, "stdout", "Started App in 0.4s");
        String exited = SidecarOutput.appExited(clock, 7, 143);
        assertThat(Jsonl.longValue(started, "ts", -1)).isEqualTo(clock.millis());
        assertThat(Jsonl.str(started, "type")).isEqualTo("app-started");
        assertThat(Jsonl.longValue(started, "pid", -1)).isEqualTo(7);
        assertThat(Jsonl.str(out, "type")).isEqualTo("app-output");
        assertThat(Jsonl.str(out, "stream")).isEqualTo("stdout");
        assertThat(Jsonl.str(out, "line")).isEqualTo("Started App in 0.4s");
        assertThat(Jsonl.str(exited, "type")).isEqualTo("app-exited");
        assertThat(Jsonl.intValue(exited, "exit", -1)).isEqualTo(143);
    }
}
