// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.tui.TerminalStatus.Detected;
import cc.jumpkick.cli.tui.TerminalStatus.Protocol;
import cc.jumpkick.config.TerminalIdentity;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.terminal.MemoryTerminal;
import cc.jumpkick.terminal.ProgramStatus;
import cc.jumpkick.terminal.ProgramStatus.Report;
import cc.jumpkick.terminal.ProgramStatus.Waiting;
import cc.jumpkick.terminal.Terminals;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TerminalStatusTest {

    private static final String ESC = "\u001b";

    private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);

    private static TerminalStatus speaking(Protocol protocol) {
        return new TerminalStatus(() -> new Detected(protocol, true));
    }

    private String written() {
        return buf.toString(StandardCharsets.UTF_8);
    }

    @Test
    void nothing_is_sent_before_a_surface_begins() {
        TerminalStatus status = speaking(Protocol.OSC_7501);
        assertThat(status.working(10)).isEmpty();
        status.send(Report.working());
        status.exit(Exit.SUCCESS);
        status.cancelled();
        assertThat(written()).isEmpty();
    }

    @Test
    void a_7501_session_never_sends_osc_9_4() {
        TerminalStatus status = speaking(Protocol.OSC_7501);
        status.begin(out);
        out.print(status.working(-1));
        out.print(status.working(40));
        try (var waiting = status.blocked(Waiting.QUESTION, out)) {
            out.print(""); // the prompt
        }
        status.exit(Exit.FAILURE);
        assertThat(written())
                .contains("]7501;state=working:app=jk" + ESC + "\\")
                .contains("]7501;state=working:app=jk:progress=40")
                .contains("]7501;state=blocked:app=jk:kind=question")
                .contains("]7501;state=error:app=jk")
                .doesNotContain("]9;4");
    }

    @Test
    void a_9_4_session_never_sends_osc_7501() {
        TerminalStatus status = speaking(Protocol.OSC_9_4);
        status.begin(out);
        out.print(status.working(40));
        try (var waiting = status.blocked(Waiting.PERMISSION, out)) {
            out.print("");
        }
        status.exit(Exit.SUCCESS);
        assertThat(written())
                .isEqualTo(ESC + "]9;4;1;40\u0007" + ESC + "]9;4;4\u0007" + ESC + "]9;4;1;40\u0007" + ESC
                        + "]9;4;0\u0007");
    }

    @Test
    void the_terminal_is_detected_once_while_the_stream_holds() {
        AtomicInteger asked = new AtomicInteger();
        TerminalStatus status = new TerminalStatus(() -> {
            asked.incrementAndGet();
            return new Detected(Protocol.OSC_9_4, true);
        });
        status.begin(out);
        status.begin(out);
        status.begin(new PrintStream(new ByteArrayOutputStream()));
        assertThat(asked).hasValue(1);
    }

    @Test
    void a_new_stream_is_checked_again_when_the_terminal_was_never_asked() {
        AtomicInteger asked = new AtomicInteger();
        TerminalStatus status = new TerminalStatus(() -> {
            asked.incrementAndGet();
            return new Detected(Protocol.NONE, false);
        });
        status.begin(out);
        status.begin(new PrintStream(new ByteArrayOutputStream()));
        assertThat(asked).hasValue(2);
    }

    @Test
    void osc_turned_off_sends_nothing() {
        TerminalStatus status = speaking(Protocol.NONE);
        status.begin(out);
        out.print(status.working(40));
        status.exit(Exit.SUCCESS);
        assertThat(written()).isEmpty();
    }

    @Test
    void a_report_identical_to_the_last_is_not_sent_again() {
        TerminalStatus status = speaking(Protocol.OSC_7501);
        status.begin(out);
        assertThat(status.working(5)).isNotEmpty();
        assertThat(status.working(5)).isEmpty();
        assertThat(status.between()).isNotEmpty();
        assertThat(status.between()).isEmpty();
        assertThat(status.working(6)).isNotEmpty();
    }

    @Test
    void a_region_ending_mid_command_leaves_the_session_working_never_done() {
        TerminalStatus status = speaking(Protocol.OSC_7501);
        status.begin(out);
        out.print(status.working(100));
        out.print(status.between());
        assertThat(written()).contains("state=working:app=jk" + ESC + "\\").doesNotContain("state=done");
    }

    @Test
    void the_end_of_the_command_is_done_error_or_idle() {
        assertThat(TerminalStatus.finalReport(Exit.SUCCESS)).isEqualTo(Report.done());
        assertThat(TerminalStatus.finalReport(Exit.FAILURE)).isEqualTo(Report.error());
        assertThat(TerminalStatus.finalReport(Exit.TESTS_FAILED)).isEqualTo(Report.error());
        assertThat(TerminalStatus.finalReport(Exit.INTERRUPTED)).isEqualTo(Report.idle());
        assertThat(TerminalStatus.finalReport(GlobalCancel.TERMINATED)).isEqualTo(Report.idle());
        assertThat(TerminalStatus.finalReport(GlobalCancel.HUNG_UP)).isEqualTo(Report.idle());
    }

    @Test
    void the_wait_ends_back_in_the_state_before_it() {
        TerminalStatus status = speaking(Protocol.OSC_7501);
        status.begin(out);
        out.print(status.working(30));
        buf.reset();
        try (var waiting = status.blocked(Waiting.AUTH, out)) {
            assertThat(written()).isEqualTo(ProgramStatus.osc7501(Report.blocked(Waiting.AUTH)));
            buf.reset();
        }
        assertThat(written()).isEqualTo(ProgramStatus.osc7501(Report.working(30)));
    }

    @Test
    void exit_and_cancel_clear_child_records_first() {
        TerminalStatus status = speaking(Protocol.OSC_7501);
        status.begin(out);
        status.send(Report.working(20).child("build", "Build"));
        status.send(Report.working().child("build/test", "Tests"));
        status.send(Report.working().child("lock", null));
        buf.reset();
        status.exit(Exit.SUCCESS);
        assertThat(written())
                .isEqualTo(ESC + "]7501;state=clear:id=build" + ESC + "\\"
                        + ESC + "]7501;state=clear:id=lock" + ESC + "\\"
                        + ESC + "]7501;state=done:app=jk" + ESC + "\\");

        TerminalStatus cancelled = speaking(Protocol.OSC_7501);
        var other = new ByteArrayOutputStream();
        cancelled.begin(new PrintStream(other, true, StandardCharsets.UTF_8));
        cancelled.send(Report.working().child("build", null));
        other.reset();
        cancelled.cancelled();
        assertThat(other.toString(StandardCharsets.UTF_8))
                .isEqualTo(
                        ESC + "]7501;state=clear:id=build" + ESC + "\\" + ESC + "]7501;state=idle:app=jk" + ESC + "\\");
    }

    @Test
    void the_terminal_is_asked_only_from_a_terminal_and_never_through_tmux_or_screen() {
        TerminalIdentity ghostty = TerminalIdentity.of(Map.of("TERM_PROGRAM", "ghostty")::get);
        assertThat(TerminalStatus.askable(true, true, ghostty)).isTrue();
        assertThat(TerminalStatus.askable(false, true, ghostty)).isFalse();
        assertThat(TerminalStatus.askable(true, false, ghostty)).isFalse();
        assertThat(TerminalStatus.askable(true, true, TerminalIdentity.of(Map.of("TMUX", "/tmp/tmux-1/default")::get)))
                .isFalse();
        assertThat(TerminalStatus.askable(true, true, TerminalIdentity.of(Map.of("STY", "1.pts-0")::get)))
                .isFalse();
        assertThat(TerminalStatus.askable(true, true, TerminalIdentity.of(Map.of("TERM", "screen-256color")::get)))
                .isFalse();
    }

    @Test
    void the_probe_reads_the_7501_reply_before_the_attributes_as_supported() {
        String reply = ESC + "]7501;?:kinds=permission,question,auth" + ESC + "\\" + ESC + "[?62;22c";
        try (MemoryTerminal tty = terminal(reply)) {
            assertThat(TerminalStatus.probe(tty, Duration.ofMillis(500))).isEqualTo(Protocol.OSC_7501);
            assertThat(new String(tty.written(), StandardCharsets.UTF_8))
                    .isEqualTo(ProgramStatus.QUERY + ProgramStatus.DEVICE_ATTRIBUTES);
        }
    }

    @Test
    void the_probe_falls_back_when_the_attributes_come_first() {
        try (MemoryTerminal tty = terminal(ESC + "[?1;2c")) {
            assertThat(TerminalStatus.probe(tty, Duration.ofMillis(500))).isEqualTo(Protocol.OSC_9_4);
        }
    }

    @Test
    void the_probe_falls_back_when_nothing_answers_in_time() {
        try (MemoryTerminal tty = terminal("")) {
            long start = System.nanoTime();
            assertThat(TerminalStatus.probe(tty, Duration.ofMillis(100))).isEqualTo(Protocol.OSC_9_4);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        }
    }

    @Test
    void the_probe_ignores_replies_it_does_not_understand() {
        String reply = ESC + "]11;rgb:0000/0000/0000\u0007" + ESC + "]7501;?" + ESC + "\\" + ESC + "[?62c";
        try (MemoryTerminal tty = terminal(reply)) {
            assertThat(TerminalStatus.probe(tty, Duration.ofMillis(500))).isEqualTo(Protocol.OSC_7501);
        }
    }

    private static MemoryTerminal terminal(String reply) {
        return Terminals.memory(
                new ByteArrayInputStream(reply.getBytes(StandardCharsets.ISO_8859_1)), new ByteArrayOutputStream());
    }
}
