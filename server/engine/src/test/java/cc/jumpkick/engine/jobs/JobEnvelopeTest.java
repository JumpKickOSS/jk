// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkHistoryConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.engine.InFlightBuilds;
import cc.jumpkick.engine.JsonOut;
import cc.jumpkick.engine.journal.BuildAccumulator;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.runtime.progress.ProgressBarMode;
import cc.jumpkick.task.IoLedger;
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
                    return JobOutcome.declined();
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
                    return JobOutcome.declined();
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
                JobRequest.maintenance("cache", "jk-test-", (line, tok, w) -> JobOutcome.declined()),
                new JobTransport.SocketWatch(new BufferedReader(new StringReader("")), new BufferedWriter(out)));
        // A clean that leaves a fresh target/jk-profile.json behind un-cleans itself.
        assertThat(host.lastNoTimeline).isTrue();

        env.submit(
                "{\"type\":\"build-request\",\"dir\":\"/tmp/job-env\"}",
                JobRequest.plan("build", "jk-test-", (line, tok, w) -> JobOutcome.declined()),
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
                    return JobOutcome.declined();
                }),
                new JobTransport.FireAndForget());
        assertThat(jid).isPositive();
        assertThat(ran.await(30, TimeUnit.SECONDS)).isTrue();
        // Await the WHOLE teardown, not just the finish flag: the detached worker appends
        // teardownOrder entries after finished++ lands, and containsExactly iterating the live
        // synchronizedList mid-append flaked under parallel suite load (the "expected X to
        // contain exactly X" failure). Snapshot before asserting.
        long deadline = System.currentTimeMillis() + 30_000;
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
        String line = "{\"type\":\"build-request\",\"dir\":" + Jsonl.quote(dir.toString()) + "}";
        env.submit(
                line,
                JobRequest.workspace("build", "jk-test-", (l, tok, w) -> {
                    started.countDown();
                    try {
                        // Generous: the first build must still be in flight when the duplicate is
                        // submitted, and the main thread always releases it in its finally.
                        release.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return JobOutcome.declined();
                }),
                new JobTransport.FireAndForget());
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> env.submit(
                            line,
                            JobRequest.workspace("build", "jk-test-", (l, tok, w) -> JobOutcome.declined()),
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
        env.enforceDeadline(7L, Session.CancelToken.live(), null, null, 1234L);
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

    /**
     * The end-of-request EOF is not a cancel. The engine half-closes the client's read to wake its
     * own connection thread the moment the runner is done, and that EOF arrives on the same path a
     * real hang-up does — so a body that has already ruled success must not be re-labelled.
     */
    @Test
    void a_body_that_ruled_success_is_not_relabelled_cancelled_by_the_end_of_request_eof() {
        FakeHost host = new FakeHost();
        host.accumulator = new BuildAccumulator("cache", "/tmp/job-env", null, "cli");
        JobEnvelope env = new JobEnvelope(host);
        StringWriter out = new StringWriter();

        env.submit(
                "{\"type\":\"cache-prune-request\",\"op\":\"prune\",\"dir\":\"/tmp/job-env\"}",
                JobRequest.maintenance("cache", "jk-test-", (line, tok, w) -> JobOutcome.ok()),
                new JobTransport.SocketWatch(new BufferedReader(new StringReader("")), new BufferedWriter(out)));

        assertThat(host.journalWritten).isTrue();
        BuildRecord record = host.journalRecord();
        assertThat(record.cancelled()).isFalse();
        assertThat(record.success()).isTrue();
        assertThat(record.exitCode()).isZero();
    }

    /**
     * A runner that dies without ruling leaves no rows behind, and no rows is not evidence of
     * success — the journal records a failure the user can act on rather than a green run.
     *
     * <p>Detached on purpose: a socket job's dead runner is caught by the cancel stamps, because
     * the connection thread reads EOF. A dashboard/MCP job has no reader and no cancel, so the
     * only thing standing between a dead runner and a green history row is the derivation itself.
     */
    @Test
    void a_detached_runner_that_dies_without_ruling_journals_a_failure() throws Exception {
        FakeHost host = new FakeHost();
        host.accumulator = new BuildAccumulator("build", "/tmp/job-env", null, "cli");
        JobEnvelope env = new JobEnvelope(host);

        env.submit(
                "{\"type\":\"build-request\",\"dir\":\"/tmp/job-env\"}",
                JobRequest.plan("build", "jk-test-", (line, tok, w) -> {
                    throw new IllegalStateException("runner died");
                }),
                new JobTransport.FireAndForget());

        long deadline = System.currentTimeMillis() + 30_000;
        while (!host.journalWritten && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(host.journalWritten).isTrue();
        assertThat(host.journalCancelled)
                .as("no reader, no cancel — nothing but the derivation is holding this row")
                .isFalse();
        BuildRecord record = host.journalRecord();
        assertThat(record.success()).isFalse();
        assertThat(record.exitCode()).isNotZero();
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
        public void putMode(long id, ProgressBarMode mode) {}

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
        public IoLedger runIo(long id) {
            return new IoLedger();
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

        volatile boolean journalWritten;
        volatile boolean journalCancelled;
        volatile long journalMillis;

        @Override
        public void writeJournal(long id, boolean cancelled, long millis, BufferedWriter writer) {
            teardownOrder.add("writeJournal");
            journalCancelled = cancelled;
            journalMillis = millis;
            journalWritten = true;
        }

        /** The row JournalWriter would persist for this run, built the same way it builds it. */
        BuildRecord journalRecord() {
            return accumulator.toRecord(
                    2_000L, journalCancelled || accumulator.wasCancelled(), journalMillis, version(), null);
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
