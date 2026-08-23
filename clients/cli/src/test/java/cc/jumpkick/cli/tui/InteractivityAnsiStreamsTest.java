// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.WriterOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Gates for {@link Interactivity#installAnsiTerminalStreams(String[])}. */
class InteractivityAnsiStreamsTest {

    @AfterEach
    void reset() {
        Interactivity.resetAnsiTerminalStreams();
        Interactivity.takeSharedTerminal(); // drain shared slot if a probe left one
    }

    @Test
    void json_stdout_requested_from_argv_and_env_shapes() {
        assertThat(Interactivity.jsonStdoutRequested(null)).isFalse();
        assertThat(Interactivity.jsonStdoutRequested(new String[] {"build"})).isFalse();
        assertThat(Interactivity.jsonStdoutRequested(new String[] {"build", "--output", "text"}))
                .isFalse();
        assertThat(Interactivity.jsonStdoutRequested(new String[] {"build", "--output", "json"}))
                .isTrue();
        assertThat(Interactivity.jsonStdoutRequested(new String[] {"build", "-O", "jsonl"}))
                .isTrue();
        assertThat(Interactivity.jsonStdoutRequested(new String[] {"build", "--output=JSON"}))
                .isTrue();
        assertThat(Interactivity.jsonStdoutRequested(new String[] {"build", "-O=json"}))
                .isTrue();
    }

    @Test
    void install_is_noop_under_no_ansi() {
        PrintStream before = System.out;
        JkConfig noAnsi = JkConfig.empty().withNoAnsi(Optional.of(true));
        SessionContext.runWhere(Session.defaults().withConfig(noAnsi), () -> {
            assertThat(Interactivity.installAnsiTerminalStreams(new String[] {"build"}))
                    .isFalse();
            assertThat(Interactivity.ansiTerminalStreamsInstalled()).isFalse();
            assertThat(System.out).isSameAs(before);
        });
    }

    @Test
    void install_is_noop_when_quiet() {
        PrintStream before = System.out;
        JkConfig quiet = JkConfig.empty().withQuiet(Optional.of(true));
        SessionContext.runWhere(Session.defaults().withConfig(quiet), () -> {
            assertThat(Interactivity.installAnsiTerminalStreams(new String[] {"build"}))
                    .isFalse();
            assertThat(System.out).isSameAs(before);
        });
    }

    @Test
    void install_is_noop_for_json_output_flag() {
        PrintStream before = System.out;
        // Even with ansi allowed, JSON stdout must stay on the raw stream.
        assertThat(Interactivity.installAnsiTerminalStreams(new String[] {"build", "--output", "json"}))
                .isFalse();
        assertThat(System.out).isSameAs(before);
    }

    @Test
    void utf8_printstream_over_oem_writer_output_stream_mojibakes_pulse_glyph() {
        // Documents the Windows failure mode: UTF-8 bytes for ● (E2 97 8F) decoded as IBM437
        // become ΓùÅ before WriteConsoleW. installAnsiTerminalStreams must not do this.
        Charset oem = Charset.forName("IBM437");
        StringWriter sw = new StringWriter();
        PrintStream mismatched = new PrintStream(new WriterOutputStream(sw, oem), true, StandardCharsets.UTF_8);
        mismatched.print("\u25CF");
        mismatched.flush();
        assertThat(sw.toString()).isEqualTo("\u0393\u00F9\u00C5"); // ΓùÅ
    }

    @Test
    void utf8_writer_bridge_round_trips_pulse_glyph() {
        // Same bridge newAnsiStdoutStream uses: PrintStream(UTF-8) → WriterOutputStream(UTF-8)
        // → Writer. Matching charsets preserve ●; mismatched OEM decode produces ΓùÅ (test above).
        StringWriter sw = new StringWriter();
        PrintStream bridged = new PrintStream(
                new WriterOutputStream(sw, Interactivity.ANSI_STDOUT_CHARSET), true, Interactivity.ANSI_STDOUT_CHARSET);
        bridged.print("\u25CF \u25B0 cache\u2026");
        bridged.flush();
        assertThat(sw.toString()).isEqualTo("\u25CF \u25B0 cache\u2026");
    }

    @Test
    void new_ansi_stdout_stream_does_not_mojibake_when_terminal_encoding_is_oem() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Charset oem = Charset.forName("IBM437");
        Terminal t = TerminalBuilder.builder()
                .system(false)
                .dumb(true)
                .type("dumb")
                // OEM encoding mirrors JLine's Windows console CP auto-detect poison.
                .encoding(oem)
                .streams(new ByteArrayInputStream(new byte[0]), bytes)
                .build();
        try {
            Interactivity.newAnsiStdoutStream(t).print("\u25CF");
            t.flush();
            // writer() got the real ● code point; OEM encoder may replace it with '?', but must
            // not emit the UTF-8-as-CP437 mojibake sequence ΓùÅ.
            assertThat(bytes.toString(oem)).isNotEqualTo("\u0393\u00F9\u00C5");
        } finally {
            t.close();
        }
    }
}
