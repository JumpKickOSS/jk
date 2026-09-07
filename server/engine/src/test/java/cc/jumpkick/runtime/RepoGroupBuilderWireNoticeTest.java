// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkHistoryConfig;
import cc.jumpkick.config.JobLimits;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.api.InFlightBuilds;
import cc.jumpkick.engine.api.JsonOut;
import cc.jumpkick.engine.jobs.JobEnvelope;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.jobs.JobRequest;
import cc.jumpkick.engine.jobs.JobTransport;
import cc.jumpkick.engine.journal.BuildAccumulator;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.task.IoLedger;
import cc.jumpkick.task.RunNotices;
import cc.jumpkick.wire.runtime.progress.ProgressBarMode;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end witness for the run-notice channel: the dependency-confusion warning — a security
 * control — reaches the requesting client as a WARN wire line, not only the engine's stderr, when
 * the repository group is built inside an enveloped request.
 */
class RepoGroupBuilderWireNoticeTest {

    @BeforeEach
    @AfterEach
    void forgetRunNotices() {
        RunNotices.clear();
    }

    private static final List<RepositorySpec> UNBOUND_PAIR = List.of(
            new RepositorySpec("central", URI.create("https://repo.maven.apache.org/maven2/")),
            new RepositorySpec("corp", URI.create("https://corp.example/maven/")));

    private static final List<List<String>> NO_BINDINGS = List.of(List.of(), List.of());

    @Test
    void the_dependency_confusion_warning_reaches_the_client_as_a_warn_line() {
        EnvelopeHost host = new EnvelopeHost();
        JobEnvelope env = new JobEnvelope(host, JobLimits.DEFAULTS);
        StringWriter out = new StringWriter();
        var err = new ByteArrayOutputStream();
        var originalErr = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            env.submit(
                    "{\"type\":\"build-request\",\"dir\":\"/tmp/repo-notice\"}",
                    JobRequest.plan("build", "jk-test-", (line, tok, w) -> {
                        // The way the planner stands when it builds the group: a Session derived
                        // inside the request carries the run's ledger.
                        SessionContext.runWhere(
                                Session.defaults(),
                                () -> RepoGroupBuilder.maybeWarnMultiRepoWithoutBindings(UNBOUND_PAIR, NO_BINDINGS));
                        return JobOutcome.declined();
                    }),
                    new JobTransport.SocketWatch(new BufferedReader(new StringReader("")), new BufferedWriter(out)));
        } finally {
            System.setErr(originalErr);
        }
        assertThat(out.toString()).contains("\"code\":\"notice\"").contains("dependency-confusion risk");
        assertThat(err.toString(StandardCharsets.UTF_8))
                .as("with a sink open, the note rides the wire instead of stderr")
                .doesNotContain("dependency-confusion risk");
    }

    /** Just the seams this flow touches; everything else is inert. */
    private static final class EnvelopeHost implements JobEnvelope.Host {
        final IoLedger io = new IoLedger();
        final InFlightBuilds inFlight = new InFlightBuilds();
        final AtomicLong ids = new AtomicLong();
        final ReentrantReadWriteLock gate = new ReentrantReadWriteLock();

        @Override
        public boolean tryStartBuildPlan() {
            return true;
        }

        @Override
        public void abandonBuildPlanSlot() {}

        @Override
        public void noteBuildPlanFinished() {}

        @Override
        public boolean draining() {
            return false;
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
        public IoLedger runIo(long id) {
            return io;
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
        public void publishEvent(String type, JsonOut payload) {}

        @Override
        public void clearProgress(long id) {}

        @Override
        public void writeJournal(long id, boolean cancelled, long millis, BufferedWriter writer) {}

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
            return new JkHistoryConfig(false, 0, 0);
        }

        @Override
        public BuildJournal journal() {
            return BuildJournal.current();
        }

        @Override
        public String coordOf(String dir) {
            return "test:repo-notice";
        }
    }
}
