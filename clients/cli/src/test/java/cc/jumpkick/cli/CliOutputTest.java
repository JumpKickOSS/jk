// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.cli.testing.NoAnsi;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The blank-line envelope is owned by {@link CliOutput}, not by each command. */
class CliOutputTest {

    @BeforeEach
    @AfterEach
    void beginCommand() {
        CliOutput.beginCommand(false);
    }

    @Test
    void first_out_inserts_one_leading_blank() {
        String captured = Capture.stdout(() -> {
            CliOutput.out("hello");
            CliOutput.out("world");
        });
        assertThat(captured).isEqualTo("\nhello\nworld\n");
    }

    @Test
    void raw_wedge_line_still_gets_the_envelope() {
        // The engine-stop bug: CliOutput.out(pre-rendered chip) skipped the blank when the
        // envelope lived only in CommandWedge.print* helpers.
        String captured = Capture.stdout(() -> CliOutput.out(" ■ Engine  Engine stopped."));
        assertThat(captured).startsWith("\n");
        assertThat(captured).doesNotStartWith("\n\n");
        assertThat(captured).contains("Engine stopped.");
    }

    @Test
    void script_mode_prints_with_no_leading_blank() {
        String captured = Capture.stdout(() -> {
            CliOutput.beginCommand(true);
            CliOutput.out("export JAVA_HOME=/opt/jdk");
        });
        assertThat(captured).isEqualTo("export JAVA_HOME=/opt/jdk\n");
    }

    @Test
    void script_mode_stdout_is_byte_exact() throws Exception {
        String captured = NoAnsi.forced(() -> Capture.stdout(() -> {
            CliOutput.beginCommand(true);
            CliOutput.out("Core — utils");
        }));
        assertThat(captured).isEqualTo("Core — utils\n");
    }

    @Test
    void human_mode_stdout_is_ascii_rewritten_under_no_ansi() throws Exception {
        String captured = NoAnsi.forced(() -> Capture.stdout(() -> {
            CliOutput.beginCommand(false);
            CliOutput.out("Core — utils");
        }));
        assertThat(captured).isEqualTo("\nCore -- utils\n");
    }

    @Test
    void script_mode_leaves_stderr_human() throws Exception {
        Capture.Streams captured = NoAnsi.forced(() -> Capture.both(() -> {
            CliOutput.beginCommand(true);
            CliOutput.err("boom…");
            CliOutput.closeEnvelope();
        }));
        assertThat(captured.err()).isEqualTo("\nboom...\n\n");
        assertThat(captured.out()).isEmpty();
    }

    @Test
    void first_empty_out_is_the_envelope_not_a_double_blank() {
        String captured = Capture.stdout(() -> {
            CliOutput.out();
            CliOutput.out("chrome");
        });
        assertThat(captured).isEqualTo("\nchrome\n");
    }

    @Test
    void stdout_and_stderr_share_one_envelope() {
        Capture.Streams captured = Capture.both(() -> {
            CliOutput.err("working");
            CliOutput.out("done");
        });
        assertThat(captured.err()).isEqualTo("\nworking\n");
        assertThat(captured.out()).isEqualTo("done\n");
    }

    @Test
    void stdout_stream_first_write_opens_envelope() {
        String captured = Capture.stdout(() -> {
            CliOutput.stdout().println("spinner");
            CliOutput.stdout().println("settle");
        });
        assertThat(captured).isEqualTo("\nspinner\nsettle\n");
    }

    @Test
    void bare_carriage_return_still_gets_the_leading_blank() {
        // A live region returning the cursor to column 0 is not the envelope's blank line.
        String captured = Capture.stdout(() -> CliOutput.stdout().print('\r'));
        assertThat(captured).isEqualTo("\n\r");
    }

    @Test
    void leading_newline_write_is_the_envelope_itself() {
        String captured = Capture.stdout(() -> CliOutput.stdout().println());
        assertThat(captured).isEqualTo("\n");
    }

    @Test
    void stream_keeps_writing_to_the_stdout_it_was_built_on() {
        var built = new ByteArrayOutputStream();
        var redirected = new ByteArrayOutputStream();
        PrintStream prev = System.out;
        try {
            System.setOut(new PrintStream(built, true, StandardCharsets.UTF_8));
            PrintStream stream = CliOutput.stdout();
            // What JkManager.captureOutput() does while a region owns the terminal.
            System.setOut(new PrintStream(redirected, true, StandardCharsets.UTF_8));
            stream.println("paint");
        } finally {
            System.setOut(prev);
        }
        assertThat(Capture.lf(built.toString(StandardCharsets.UTF_8))).isEqualTo("\npaint\n");
        assertThat(redirected.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void ensure_leading_blank_on_custom_stream_is_idempotent_with_out() {
        var buf = new ByteArrayOutputStream();
        var ps = new PrintStream(buf, true, StandardCharsets.UTF_8);
        var prev = System.out;
        try {
            System.setOut(ps);
            CliOutput.ensureLeadingBlank(ps);
            CliOutput.out("chip");
        } finally {
            System.setOut(prev);
        }
        assertThat(Capture.lf(buf.toString(StandardCharsets.UTF_8))).isEqualTo("\nchip\n");
    }

    @Test
    void begin_command_allows_a_new_command_envelope() {
        Capture.stdout(() -> CliOutput.out("first"));
        CliOutput.beginCommand(false);
        String captured = Capture.stdout(() -> CliOutput.out("second"));
        assertThat(captured).isEqualTo("\nsecond\n");
    }

    @Test
    void close_envelope_adds_one_trailing_blank() {
        String captured = Capture.stdout(() -> {
            CliOutput.out("hello");
            CliOutput.closeEnvelope();
            CliOutput.closeEnvelope(); // idempotent
        });
        assertThat(captured).isEqualTo("\nhello\n\n");
    }

    @Test
    void close_envelope_is_noop_when_never_opened() {
        String captured = Capture.stdout(CliOutput::closeEnvelope);
        assertThat(captured).isEmpty();
    }

    @Test
    void close_envelope_is_noop_in_script_mode() {
        String captured = Capture.stdout(() -> {
            CliOutput.beginCommand(true);
            CliOutput.out("export JAVA_HOME=/opt/jdk");
            CliOutput.closeEnvelope();
        });
        assertThat(captured).isEqualTo("export JAVA_HOME=/opt/jdk\n");
    }

    @Test
    void skip_trailing_blank_keeps_the_leading_one() {
        String captured = Capture.stdout(() -> {
            CliOutput.out("chrome");
            CliOutput.skipTrailingBlank();
            CliOutput.closeEnvelope();
        });
        assertThat(captured).isEqualTo("\nchrome\n");
    }

    @Test
    void close_envelope_follows_stderr_when_first_chrome_was_err() {
        Capture.Streams captured = Capture.both(() -> {
            CliOutput.err("boom");
            CliOutput.closeEnvelope();
        });
        assertThat(captured.out()).isEmpty();
        assertThat(captured.err()).isEqualTo("\nboom\n\n");
    }

    @Test
    void trailing_blank_follows_the_last_write_not_the_first() {
        Capture.Streams captured = Capture.both(() -> {
            CliOutput.out("plan");
            CliOutput.err("boom");
            CliOutput.closeEnvelope();
        });
        assertThat(captured.out()).isEqualTo("\nplan\n");
        assertThat(captured.err()).isEqualTo("boom\n\n");
    }

    @Test
    void stderr_stream_leading_blank_closes_on_stderr() {
        Capture.Streams captured = Capture.both(() -> {
            CliOutput.stderr().println("boom");
            CliOutput.closeEnvelope();
        });
        assertThat(captured.out()).isEmpty();
        assertThat(captured.err()).isEqualTo("\nboom\n\n");
    }

    @Test
    void envelope_stream_encodes_with_the_charset_system_out_uses() {
        // stdout() writes finished bytes past System.out's encoder, so it has to encode with that
        // stream's charset. Taking it from a property instead is how a Windows console flipped to
        // UTF-8 still received CP437 bytes: the property kept the pre-flip name.
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream previous = System.out;
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.ISO_8859_1));
            CliOutput.beginCommand(true); // script mode: bytes only, no envelope blank
            PrintStream out = CliOutput.stdout();
            out.print("caf\u00e9");
            out.flush();
        } finally {
            System.setOut(previous);
        }
        assertThat(buffer.toByteArray()).isEqualTo("caf\u00e9".getBytes(StandardCharsets.ISO_8859_1));
    }

    @Test
    void line_writes_hand_the_console_stream_a_string_to_encode() {
        // out()/outRaw() hand System.out a string, leaving that stream's own encoder as the only
        // one in the chain: a plain line write has no reason to take stdout()'s byte path, with its
        // extra encoder and its first-write envelope hook.
        var recorder = new RecordingStream();
        PrintStream prev = System.out;
        try {
            System.setOut(recorder);
            CliOutput.beginCommand(true);
            CliOutput.out("Core — utils");
            CliOutput.outRaw("Core — utils");
        } finally {
            System.setOut(prev);
        }
        assertThat(recorder.tookStrings).isTrue();
    }

    /**
     * Notes whether anything reached {@code System.out} as text. Byte writes are no signal: a
     * {@link PrintStream} encodes its own string writes through itself, so they show up as both.
     */
    private static final class RecordingStream extends PrintStream {
        private boolean tookStrings;

        RecordingStream() {
            super(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8);
        }

        @Override
        public void print(String s) {
            tookStrings = true;
            super.print(s);
        }

        @Override
        public void println(String s) {
            tookStrings = true;
            super.println(s);
        }
    }
}
