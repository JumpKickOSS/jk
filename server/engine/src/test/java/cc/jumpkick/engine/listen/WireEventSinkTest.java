// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.listen;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.api.WireWriter;
import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ErrorLineEvent;
import cc.jumpkick.wire.protocol.FailureTextRefs;
import java.io.BufferedWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class WireEventSinkTest {

    private static final FailureTextRefs TEXTS = new FailureTextRefs();

    /**
     * A suite whose tests all fail on one setup error repeats one text per failure; the stream
     * carries it once and names it after that.
     */
    @Test
    void a_repeated_failure_text_crosses_the_stream_once() {
        FailureTextRefs texts = new FailureTextRefs();
        String stack = "java.lang.AssertionError: war missing\n\tat org.jvnet.hudson.test.JenkinsRule.before(J.java:1)";
        TestFailureInfo first =
                new TestFailureInfo("g:core", "", "a.FooTest", "x()", "AssertionError", "war missing", stack);
        TestFailureInfo second =
                new TestFailureInfo("g:core", "", "a.BarTest", "y()", "AssertionError", "war missing", stack);

        String one =
                WireEventSink.encode(new EngineEvent.ErrorFailure("d", "run-tests", "test-failure", "", first), texts);
        String two =
                WireEventSink.encode(new EngineEvent.ErrorFailure("d", "run-tests", "test-failure", "", second), texts);
        String plan = WireEventSink.encode(
                new EngineEvent.PlanDiagnosticFailure("d", "run-tests", "test-failure", "", first), texts);

        assertThat(one).contains("\"textId\":1").contains("JenkinsRule.before").contains("war missing");
        assertThat(two)
                .contains("\"sameText\":1")
                .contains("a.BarTest")
                .doesNotContain("JenkinsRule.before")
                .doesNotContain("war missing");
        assertThat(plan).contains("\"sameText\":1").doesNotContain("JenkinsRule.before");

        FailureTextRefs reader = new FailureTextRefs();
        ErrorLineEvent a = ErrorLineEvent.decode(requireNonNull(one));
        ErrorLineEvent b = ErrorLineEvent.decode(requireNonNull(two));
        reader.resolve(a.textId(), a.sameText(), a.message(), a.stack());
        assertThat(reader.resolve(b.textId(), b.sameText(), b.message(), b.stack()))
                .isEqualTo(new FailureTextRefs.Text("war missing", stack));
    }

    @Test
    void plan_start_encodes_existing_wire_token() {
        String line =
                requireNonNull(WireEventSink.encode(new EngineEvent.PlanStart("d", "build", 1, 2, 3, 0, false), TEXTS));
        assertThat(EngineProtocol.typeOf(line)).isEqualTo(EngineProtocol.BUILDPLAN_START);
        assertThat(line).contains("\"dir\":\"d\"");
    }

    @Test
    void recording_sink_keeps_order() {
        RecordingEventSink rec = new RecordingEventSink();
        rec.emit(new EngineEvent.StepStart("d", "javac", "compile", 1));
        rec.emit(new EngineEvent.Label("d", "javac", "ok"));
        assertThat(rec.events()).hasSize(2);
        assertThat(rec.events().getFirst()).isInstanceOf(EngineEvent.StepStart.class);
    }

    @Test
    void workspace_events_keep_existing_wire_tokens() {
        assertThat(EngineProtocol.typeOf(requireNonNull(
                        WireEventSink.encode(new EngineEvent.Preflight("lock", 0, 1, "locking"), TEXTS))))
                .isEqualTo(EngineProtocol.PREFLIGHT);
        assertThat(EngineProtocol.typeOf(requireNonNull(WireEventSink.encode(new EngineEvent.PlanDone(3), TEXTS))))
                .isEqualTo(EngineProtocol.PLAN_DONE);
        assertThat(EngineProtocol.typeOf(
                        requireNonNull(WireEventSink.encode(new EngineEvent.ModuleStart("d", "g:a"), TEXTS))))
                .isEqualTo(EngineProtocol.MODULE_START);
        assertThat(EngineProtocol.typeOf(requireNonNull(WireEventSink.encode(new EngineEvent.Eta(9), TEXTS))))
                .isEqualTo(EngineProtocol.ETA);
    }

    @Test
    void composite_fans_out() {
        RecordingEventSink a = new RecordingEventSink();
        RecordingEventSink b = new RecordingEventSink();
        new CompositeEventSink(a, b).emit(new EngineEvent.PlanDone(1));
        assertThat(a.events()).hasSize(1);
        assertThat(b.events()).hasSize(1);
    }

    @Test
    void concurrent_emits_do_not_interleave_jsonl_lines() throws Exception {
        // Parallel modules share one writer; unsynchronized writes used to corrupt lines so the
        // client dropped task-finish and left zombie ACTIVE rows (resolve JDK stuck in the tree).
        StringWriter sw = new StringWriter();
        BufferedWriter bw = new BufferedWriter(sw);
        WireEventSink sink = new WireEventSink(bw);
        int threads = 8;
        int perThread = 200;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                final int id = t;
                pool.execute(() -> {
                    try {
                        assertThat(start.await(30, TimeUnit.SECONDS)).isTrue();
                        for (int i = 0; i < perThread; i++) {
                            sink.emit(
                                    new EngineEvent.StepFinish("mod-" + id, "ensure-jdk", "resolve", "SUCCESS", i, 0L));
                        }
                    } catch (Throwable e) {
                        failures.add(e); // a pool task's throw is invisible to the main thread
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
            WireWriter.awaitLanded(bw);
        }
        bw.flush();
        assertThat(failures).isEmpty();
        List<String> lines = new ArrayList<>();
        for (String line : sw.toString().split("\n", -1)) {
            if (!line.isEmpty()) lines.add(line);
        }
        assertThat(lines).hasSize(threads * perThread);
        for (String line : lines) {
            assertThat(EngineProtocol.typeOf(line)).isEqualTo(EngineProtocol.TASK_FINISH);
            assertThat(line).startsWith("{").endsWith("}");
        }
    }
}
