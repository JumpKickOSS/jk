// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkHistoryConfig;
import cc.jumpkick.engine.InFlightBuilds;
import cc.jumpkick.engine.http.JsonOut;
import cc.jumpkick.engine.journal.BuildAccumulator;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.protocol.EngineProtocol;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.jupiter.api.Test;

/** Fake-host envelope: refuse-when-draining, runner finishes, request-finish published. */
class JobEnvelopeTest {

    @Test
    void draining_plan_is_refused_without_running() {
        FakeHost host = new FakeHost();
        host.tryStart = false;
        JobEnvelope env = new JobEnvelope(host);
        AtomicBoolean ran = new AtomicBoolean();
        StringWriter out = new StringWriter();
        env.submit(
                "{\"type\":\"build-request\",\"dir\":\"/p\"}",
                JobRequest.plan("build", "jk-test-", (line, tok, w) -> ran.set(true)),
                new JobTransport.SocketWatch(new BufferedReader(new StringReader("")), new BufferedWriter(out)));
        assertThat(ran).isFalse();
        assertThat(out.toString()).contains("shutting down");
        assertThat(host.abandoned).isZero();
    }

    @Test
    void runner_completes_and_publishes_request_finish() {
        FakeHost host = new FakeHost();
        JobEnvelope env = new JobEnvelope(host);
        AtomicBoolean ran = new AtomicBoolean();
        StringWriter out = new StringWriter();
        env.submit(
                "{\"type\":\"build-request\",\"dir\":\"/tmp/job-env\"}",
                JobRequest.plan("lock", "jk-test-", (line, tok, w) -> ran.set(true)),
                new JobTransport.SocketWatch(new BufferedReader(new StringReader("")), new BufferedWriter(out)));
        assertThat(ran).isTrue();
        assertThat(host.events.stream().anyMatch(e -> e.contains("request-finish")))
                .isTrue();
        assertThat(host.finished).isEqualTo(1);
        assertThat(host.cleared).containsExactly(1L);
        // writeJournal must run before clearProgress: the real host retires the session on
        // clear, and takeAccumulator then returns null — permanent "Building" in jk jobs.
        assertThat(host.teardownOrder).containsExactly("writeJournal", "clearProgress");
    }

    @Test
    void cancelled_terminal_shape_matches_stream() {
        assertThat(EngineProtocol.typeOf(JobEnvelope.cancelledTerminalLine(true, "/ws")))
                .isEqualTo(EngineProtocol.WORKSPACE_FINISH);
        assertThat(EngineProtocol.typeOf(JobEnvelope.cancelledTerminalLine(false, "/p")))
                .isEqualTo(EngineProtocol.BUILDPLAN_FINISH);
    }

    private static final class FakeHost implements JobEnvelope.Host {
        boolean tryStart = true;
        int abandoned;
        int finished;
        final List<String> events = new ArrayList<>();
        final List<Long> cleared = new ArrayList<>();
        final List<String> teardownOrder = new ArrayList<>();
        final InFlightBuilds inFlight = new InFlightBuilds();
        final AtomicLong ids = new AtomicLong();
        final ReentrantReadWriteLock gate = new ReentrantReadWriteLock();

        @Override
        public boolean tryStartBuildPlan() {
            return tryStart;
        }

        @Override
        public void abandonBuildPlanSlot() {
            abandoned++;
        }

        @Override
        public void noteBuildPlanFinished() {
            finished++;
        }

        @Override
        public boolean draining() {
            return !tryStart;
        }

        @Override
        public long nextRequestId() {
            return ids.incrementAndGet();
        }

        @Override
        public long nowMillis() {
            return 1_000L;
        }

        @Override
        public void putMode(long id, cc.jumpkick.runtime.progress.ProgressBarMode mode) {}

        @Override
        public void publishRequestStart(long id, String kind, String dir, long buildNumber) {}

        @Override
        public void registerAccumulator(
                long id,
                String kind,
                String dir,
                String trigger,
                boolean noTimeline,
                boolean rebuild,
                long buildNumber,
                String journalId) {}

        @Override
        public ReentrantReadWriteLock cacheGate() {
            return gate;
        }

        @Override
        public void bindEventRequestId(long id) {}

        @Override
        public void unbindEventRequestId() {}

        @Override
        public cc.jumpkick.task.IoLedger runIo(long id) {
            return new cc.jumpkick.task.IoLedger();
        }

        @Override
        public InFlightBuilds inFlight() {
            return inFlight;
        }

        @Override
        public BuildAccumulator accumulatorOf(long id) {
            return null;
        }

        @Override
        public void putLastProgress(long id, double percent) {}

        @Override
        public int activeBuildPlans() {
            return 0;
        }

        @Override
        public JsonOut withProgress(JsonOut payload, long id) {
            return payload;
        }

        @Override
        public JsonOut withIo(JsonOut payload, long id) {
            return payload;
        }

        @Override
        public void publishEvent(String type, JsonOut payload) {
            events.add(type + ":" + payload);
        }

        @Override
        public void clearProgress(long id) {
            teardownOrder.add("clearProgress");
            cleared.add(id);
        }

        @Override
        public void writeJournal(long id, boolean cancelled, long millis, BufferedWriter writer) {
            teardownOrder.add("writeJournal");
        }

        @Override
        public void maybeIdleBoundary() {}

        @Override
        public void maybeIdleGc() {}

        @Override
        public void log(String message) {}

        @Override
        public String version() {
            return "0.0.0-test";
        }

        @Override
        public JkHistoryConfig historyConfig() {
            return JkHistoryConfig.resolve();
        }

        @Override
        public BuildJournal journal() {
            return BuildJournal.current();
        }

        @Override
        public String coordOf(String dir) {
            return "test:job";
        }
    }
}
