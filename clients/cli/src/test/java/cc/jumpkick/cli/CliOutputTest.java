// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The blank-line envelope is owned by {@link CliOutput}, not by each command. */
class CliOutputTest {

    @BeforeEach
    @AfterEach
    void resetEnvelope() {
        CliOutput.resetEnvelope();
    }

    @Test
    void first_out_inserts_one_leading_blank() {
        String captured = captureOut(() -> {
            CliOutput.out("hello");
            CliOutput.out("world");
        });
        assertThat(captured).isEqualTo("\nhello\nworld\n");
    }

    @Test
    void raw_wedge_line_still_gets_the_envelope() {
        // The engine-stop bug: CliOutput.out(pre-rendered chip) skipped the blank when the
        // envelope lived only in CommandWedge.print* helpers.
        String captured = captureOut(() -> CliOutput.out(" ■ Engine  Engine stopped."));
        assertThat(captured).startsWith("\n");
        assertThat(captured).doesNotStartWith("\n\n");
        assertThat(captured).contains("Engine stopped.");
    }

    @Test
    void skip_envelope_prints_with_no_leading_blank() {
        String captured = captureOut(() -> {
            CliOutput.skipEnvelope();
            CliOutput.out("export JAVA_HOME=/opt/jdk");
        });
        assertThat(captured).isEqualTo("export JAVA_HOME=/opt/jdk\n");
    }

    @Test
    void first_empty_out_is_the_envelope_not_a_double_blank() {
        String captured = captureOut(() -> {
            CliOutput.out();
            CliOutput.out("chrome");
        });
        assertThat(captured).isEqualTo("\nchrome\n");
    }

    @Test
    void stdout_and_stderr_share_one_envelope() {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        PrintStream prevOut = System.out;
        PrintStream prevErr = System.err;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            CliOutput.err("working");
            CliOutput.out("done");
        } finally {
            System.setOut(prevOut);
            System.setErr(prevErr);
        }
        assertThat(err.toString(StandardCharsets.UTF_8)).isEqualTo("\nworking\n");
        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("done\n");
    }

    @Test
    void stdout_stream_first_write_opens_envelope() {
        String captured = captureOut(() -> {
            CliOutput.stdout().println("spinner");
            CliOutput.stdout().println("settle");
        });
        assertThat(captured).isEqualTo("\nspinner\nsettle\n");
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
        assertThat(buf.toString(StandardCharsets.UTF_8)).isEqualTo("\nchip\n");
    }

    @Test
    void reset_allows_a_new_command_envelope() {
        captureOut(() -> CliOutput.out("first"));
        CliOutput.resetEnvelope();
        String captured = captureOut(() -> CliOutput.out("second"));
        assertThat(captured).isEqualTo("\nsecond\n");
    }

    @Test
    void close_envelope_adds_one_trailing_blank() {
        String captured = captureOut(() -> {
            CliOutput.out("hello");
            CliOutput.closeEnvelope();
            CliOutput.closeEnvelope(); // idempotent
        });
        assertThat(captured).isEqualTo("\nhello\n\n");
    }

    @Test
    void close_envelope_is_noop_when_never_opened() {
        String captured = captureOut(CliOutput::closeEnvelope);
        assertThat(captured).isEmpty();
    }

    @Test
    void close_envelope_is_noop_when_skipped() {
        String captured = captureOut(() -> {
            CliOutput.skipEnvelope();
            CliOutput.out("export JAVA_HOME=/opt/jdk");
            CliOutput.closeEnvelope();
        });
        assertThat(captured).isEqualTo("export JAVA_HOME=/opt/jdk\n");
    }

    @Test
    void close_envelope_follows_stderr_when_first_chrome_was_err() {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        PrintStream prevOut = System.out;
        PrintStream prevErr = System.err;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            CliOutput.err("boom");
            CliOutput.closeEnvelope();
        } finally {
            System.setOut(prevOut);
            System.setErr(prevErr);
        }
        assertThat(out.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(err.toString(StandardCharsets.UTF_8)).isEqualTo("\nboom\n\n");
    }

    private static String captureOut(Runnable body) {
        var buf = new ByteArrayOutputStream();
        var prev = System.out;
        try {
            System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
            body.run();
        } finally {
            System.setOut(prev);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }
}
