// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.jsonl.Jsonl;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Drives {@link PluginProcess} against a real forked JVM ({@link EchoPluginMain}) — the first
 * end-to-end coverage of the fork + read-loop and the two-way stdin conversation the test-runner's
 * pull mode relies on.
 */
@Tag("integration")
class PluginProcessTest {

    private static List<String> cmd(String... extra) {
        String javaExe = System.getProperty("java.home") + "/bin/java";
        List<String> c = new ArrayList<>(List.of(
                javaExe, "-cp", System.getProperty("java.class.path"), "cc.jumpkick.engine.plugin.EchoPluginMain"));
        c.addAll(List.of(extra));
        return c;
    }

    @Test
    void run_splits_protocol_from_passthrough_and_returns_exit_code() throws Exception {
        var events = new ArrayList<String>();
        var chatter = new ArrayList<String>();

        int exit = PluginProcess.run(cmd("oneshot"), "##T:", json -> events.add(Jsonl.str(json, "e")), chatter::add);

        assertThat(exit).isZero();
        assertThat(events).containsExactly("a", "b");
        assertThat(chatter).contains("plain chatter");
    }

    @Test
    void a_protocol_line_glued_to_unterminated_chatter_is_still_an_event() throws Exception {
        var events = new ArrayList<String>();
        var chatter = new ArrayList<String>();
        int exit = PluginProcess.run(cmd("glued"), "##T:", json -> events.add(Jsonl.str(json, "e")), chatter::add);
        assertThat(exit).isZero();
        assertThat(events).containsExactly("a", "b");
        assertThat(chatter).contains("progress 42%");
    }

    @Test
    void run_drops_passthrough_when_sink_is_null() throws Exception {
        var events = new ArrayList<String>();
        int exit = PluginProcess.run(cmd("oneshot"), "##T:", json -> events.add(Jsonl.str(json, "e")), null);
        assertThat(exit).isZero();
        assertThat(events).containsExactly("a", "b");
    }

    @Test
    void converse_drives_a_pull_queue_over_stdin() throws Exception {
        var queue = new ArrayDeque<>(List.of("alpha", "beta", "gamma"));
        var ran = new ArrayList<String>();
        var chatter = new ArrayList<String>();

        int exit = PluginProcess.converse(
                cmd(),
                "##T:",
                (json, convo) -> {
                    String event = Jsonl.str(json, "e");
                    if ("ready".equals(event)) {
                        String next = queue.pollFirst();
                        if (next != null) {
                            convo.send("RUN " + next);
                        } else {
                            convo.send("DONE");
                            convo.closeInput();
                        }
                    } else if ("ran".equals(event)) {
                        ran.add(Jsonl.str(json, "what"));
                    }
                },
                chatter::add);

        assertThat(exit).isZero();
        assertThat(ran).containsExactly("alpha", "beta", "gamma");
        assertThat(chatter).contains("plain chatter line");
    }

    @Test
    void converseNoSlot_does_not_wait_for_a_worker_slot() {
        // : the Zinc pull session must not pin a PluginSlots permit for the worker's whole
        // life, or a nested fork (the test runner) deadlocks at parallelism 1. converseNoSlot must
        // proceed even while every permit is held elsewhere — converse() would block here forever.
        PluginSlots.configure(1);
        try (PluginSlots.Lease held = PluginSlots.acquire()) {
            var ran = new ArrayList<String>();
            var queue = new ArrayDeque<>(List.of("solo"));
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
                int exit = PluginProcess.converseNoSlot(
                        cmd(),
                        WorkerEnv.strict(),
                        "##T:",
                        (json, convo) -> {
                            String e = Jsonl.str(json, "e");
                            if ("ready".equals(e)) {
                                String next = queue.pollFirst();
                                if (next != null) {
                                    convo.send("RUN " + next);
                                } else {
                                    convo.send("DONE");
                                    convo.closeInput();
                                }
                            } else if ("ran".equals(e)) {
                                ran.add(Jsonl.str(json, "what"));
                            }
                        },
                        null);
                assertThat(exit).isZero();
            });
            assertThat(ran).containsExactly("solo");
        } finally {
            PluginSlots.configure(0); // reopen the gate for other tests
        }
    }

    @Test
    void the_pump_delivers_events_under_the_session_the_conversation_was_started_in() throws Exception {
        // The handler runs on the pump thread. A request's tuning and cancel token live on the
        // session bound to the thread that started the conversation, so the pump must carry it.
        var marked = SessionContext.current().withRequestedTestWorkers(41);
        var seen = new ArrayList<Integer>();
        int exit = SessionContext.where(
                marked,
                () -> PluginProcess.converse(
                        cmd(),
                        "##T:",
                        (json, convo) -> {
                            seen.add(SessionContext.current().requestedTestWorkers());
                            if ("ready".equals(Jsonl.str(json, "e"))) {
                                convo.send("DONE");
                                convo.closeInput();
                            }
                        },
                        null));
        assertThat(exit).isZero();
        assertThat(seen).isNotEmpty().allMatch(w -> w == 41);
    }

    @Test
    void converse_exits_cleanly_when_queue_is_empty_immediately() throws Exception {
        // First ready → empty queue → DONE/closeInput, no RUN ever sent.
        var ran = new ArrayList<String>();
        int exit = PluginProcess.converse(
                cmd(),
                "##T:",
                (json, convo) -> {
                    if ("ready".equals(Jsonl.str(json, "e"))) {
                        convo.send("DONE");
                        convo.closeInput();
                    } else if ("ran".equals(Jsonl.str(json, "e"))) {
                        ran.add(Jsonl.str(json, "what"));
                    }
                },
                null);
        assertThat(exit).isZero();
        assertThat(ran).isEmpty();
    }

    /**
     * A protocol handler that throws is the parent's bug, and the diagnostic has to say so. The
     * pump must not die quietly: a dead pump leaves the worker alive, the job force-stops it as a
     * hung child and reports the kill's exit code in place of the exception that caused it.
     */
    @Test
    void a_handler_that_throws_ends_the_conversation_with_a_diagnostic_naming_the_exception() {
        assertThatThrownBy(() -> PluginProcess.converse(
                        cmd(),
                        "##T:",
                        (json, convo) -> {
                            throw new IllegalStateException("handler boom");
                        },
                        null))
                .isInstanceOf(PluginProcess.HandlerFailure.class)
                .hasMessageContaining("handler boom")
                .hasCauseInstanceOf(IllegalStateException.class)
                .extracting(e -> ((PluginProcess.HandlerFailure) e).handler().getMessage())
                .isEqualTo("handler boom");
    }
}
