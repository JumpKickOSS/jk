// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkHistoryConfig;
import cc.jumpkick.engine.InFlightBuilds;
import cc.jumpkick.engine.JsonOut;
import cc.jumpkick.engine.journal.BuildAccumulator;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.protocol.EngineProtocol;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
                JobRequest.plan("build", "jk-test-", (line, tok, w) -> {
                    ran.set(true);
                    return null;
                }),
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
                JobRequest.plan("lock", "jk-test-", (line, tok, w) -> {
                    ran.set(true);
                    return null;
                }),
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
    void maintenance_kind_never_writes_a_timeline() {
        FakeHost host = new FakeHost();
        JobEnvelope env = new JobEnvelope(host);
        StringWriter out = new StringWriter();
        env.submit(
                "{\"type\":\"cache-prune-request\",\"op\":\"clear\",\"dir\":\"/tmp/job-env\"}",
                JobRequest.maintenance("cache", "jk-test-", (line, tok, w) -> null),
                new JobTransport.SocketWatch(new BufferedReader(new StringReader("")), new BufferedWriter(out)));
        // A clean that leaves a fresh target/jk-profile.json behind un-cleans itself.
        assertThat(host.lastNoTimeline).isTrue();

        env.submit(
                "{\"type\":\"build-request\",\"dir\":\"/tmp/job-env\"}",
                JobRequest.plan("build", "jk-test-", (line, tok, w) -> null),
                new JobTransport.SocketWatch(new BufferedReader(new StringReader("")), new BufferedWriter(out)));
        assertThat(host.lastNoTimeline).isFalse();
    }

    @Test
    void fire_and_forget_returns_jid_and_finishes_detached() throws Exception {
        FakeHost host = new FakeHost();
        JobEnvelope env = new JobEnvelope(host);
        CountDownLatch ran = new CountDownLatch(1);
        long jid = env.submit(
                "{\"type\":\"lock-request\",\"dir\":\"/tmp/job-env\"}",
                JobRequest.plan("lock", "jk-test-", (line, tok, w) -> {
                    ran.countDown();
                    return null;
                }),
                new JobTransport.FireAndForget());
        assertThat(jid).isPositive();
        assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
        // Await the WHOLE teardown, not just the finish flag: the detached worker appends
        // teardownOrder entries after finished++ lands, and containsExactly iterating the live
        // synchronizedList mid-append flaked under parallel suite load (the "expected X to
        // contain exactly X" failure). Snapshot before asserting.
        long deadline = System.currentTimeMillis() + 5_000;
        while ((host.finished == 0 || host.teardownOrder.size() < 2) && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(host.finished).isEqualTo(1);
        assertThat(List.copyOf(host.teardownOrder)).containsExactly("writeJournal", "clearProgress");
        assertThat(host.events.stream().anyMatch(e -> e.contains("request-finish")))
                .isTrue();
    }

    @Test
    void duplicate_detached_build_is_refused_with_typed_already_running(@TempDir Path dir) throws Exception {
        FakeHost host = new FakeHost();
        JobEnvelope env = new JobEnvelope(host);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        String line = "{\"type\":\"build-request\",\"dir\":" + cc.jumpkick.jsonl.Jsonl.quote(dir.toString()) + "}";
        env.submit(
                line,
                JobRequest.workspace("build", "jk-test-", (l, tok, w) -> {
                    started.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                }),
                new JobTransport.FireAndForget());
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> env.submit(
                            line,
                            JobRequest.workspace("build", "jk-test-", (l, tok, w) -> null),
                            new JobTransport.FireAndForget()))
                    .isInstanceOf(JobEnvelope.AlreadyRunning.class);
        } finally {
            release.countDown();
        }
    }

    @Test
    void deadline_kill_records_the_reason_on_the_accumulator() {
        FakeHost host = new FakeHost();
        host.accumulator = new BuildAccumulator("build", "/p", null, "web");
        JobEnvelope env = new JobEnvelope(host);
        env.enforceDeadline(7L, cc.jumpkick.config.Session.CancelToken.live(), null, null, 1234L);
        assertThat(host.accumulator.wasCancelled()).isTrue();
        assertThat(host.accumulator.cancelReason()).contains("1234ms");
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
        volatile int abandoned;
        volatile int finished;
        volatile BuildAccumulator accumulator;
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        final List<Long> cleared = Collections.synchronizedList(new ArrayList<>());
        final List<String> teardownOrder = Collections.synchronizedList(new ArrayList<>());
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

        Boolean lastNoTimeline;

        @Override
        public void registerAccumulator(
                long id,
                String kind,
                String dir,
                String trigger,
                boolean noTimeline,
                boolean rebuild,
                long buildNumber,
                String journalId) {
            lastNoTimeline = noTimeline;
        }

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
            return accumulator;
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
            return new JkHistoryConfig(false, 0, 0); // never write real journal stubs from a unit test
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
