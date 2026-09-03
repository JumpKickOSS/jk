// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.listen;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.wire.protocol.EngineProtocol;
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

    @Test
    void plan_start_encodes_existing_wire_token() {
        String line = WireEventSink.encode(new EngineEvent.PlanStart("d", "build", 1, 2, 3, 0, false));
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
        assertThat(EngineProtocol.typeOf(WireEventSink.encode(new EngineEvent.Preflight("lock", 0, 1, "locking"))))
                .isEqualTo(EngineProtocol.PREFLIGHT);
        assertThat(EngineProtocol.typeOf(WireEventSink.encode(new EngineEvent.PlanDone(3))))
                .isEqualTo(EngineProtocol.PLAN_DONE);
        assertThat(EngineProtocol.typeOf(WireEventSink.encode(new EngineEvent.ModuleStart("d", "g:a"))))
                .isEqualTo(EngineProtocol.MODULE_START);
        assertThat(EngineProtocol.typeOf(WireEventSink.encode(new EngineEvent.Eta(9))))
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
