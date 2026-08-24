// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.SecretRedactor;
import cc.jumpkick.config.Session;
import cc.jumpkick.engine.WireWriter;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.journal.BuildAccumulator;
import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.runtime.WorkspaceBuildListener;
import cc.jumpkick.runtime.WorkspaceResult;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The two laws of the workspace terminal, both learned the hard way.
 *
 * <p>A client that hangs up before the terminal is not a build result (JK-1521, JK-2386). The
 * verdict is computed from the build; whatever the socket does afterwards, that verdict is what the
 * envelope stamps and the journal records.
 *
 * <p>And no {@code .env} value rides out on it (JK-2387). Every workspace verb — {@code build},
 * {@code native}, {@code image}, workspace {@code compile} — settles through
 * {@link WorkspaceTerminal}, so masking the rows once here masks them for all four. That the
 * masking cannot be skipped is proved separately, by compiling code, in
 * {@code WorkspaceFinishRedactionTest}.
 */
class WorkspaceBuildFinishTest {

    /** The build the terminal is settling. Module outcomes are irrelevant here. */
    private static WorkspaceResult result(boolean success, int exitCode, List<String> errors) {
        return new WorkspaceResult(success, exitCode, List.of(), errors, false);
    }

    /** Exactly what the engine writes to a client whose socket is already gone. */
    private static BufferedWriter hungUpClient() {
        return new BufferedWriter(new Writer() {
            @Override
            public void write(char[] cbuf, int off, int len) throws IOException {
                throw new IOException("Broken pipe");
            }

            @Override
            public void flush() throws IOException {
                throw new IOException("Broken pipe");
            }

            @Override
            public void close() {}
        });
    }

    @Test
    void the_wire_write_really_does_fail_for_this_writer() {
        // Guard the guard: if the fake writer ever stopped throwing, every assertion below would
        // pass for the wrong reason.
        BufferedWriter dead = hungUpClient();
        assertThatThrownBy(() -> WireWriter.send(dead, "{}")).isInstanceOf(IOException.class);
    }

    @Test
    void a_green_build_whose_client_hung_up_journals_success() throws Exception {
        RecordingHost host = new RecordingHost();
        JobOutcome green = JobOutcome.of(true, 0);

        JobOutcome returned = WorkspaceTerminal.finish(host, hungUpClient(), "/w", result(true, 0, List.of()), false);

        assertThat(returned).isEqualTo(green);
        assertThat(host.throwingSends)
                .as("a journaled verb never uses the throwing send")
                .isZero();
        assertThat(host.quietSends).hasSize(1);
        assertThat(EngineProtocol.typeOf(host.quietSends.get(0))).isEqualTo(EngineProtocol.WORKSPACE_FINISH);
        assertThat(host.requestErrors).isEmpty();

        // …and the journal agrees. This is the stamp JobEnvelope applies to a non-null outcome.
        BuildRecord record = journal(returned);
        assertThat(record.success()).isTrue();
        assertThat(record.exitCode()).isZero();
        assertThat(record.cancelled()).isFalse();
    }

    @Test
    void a_failed_build_whose_client_hung_up_keeps_its_own_exit_code() throws Exception {
        RecordingHost host = new RecordingHost();
        JobOutcome red = JobOutcome.of(false, 3);

        JobOutcome returned =
                WorkspaceTerminal.finish(host, hungUpClient(), "/w", result(false, 3, List.of("boom", "bang")), false);

        assertThat(returned).isEqualTo(red);
        assertThat(host.throwingSends).isZero();
        assertThat(host.requestErrors).containsExactly("boom", "bang");

        BuildRecord record = journal(returned);
        assertThat(record.success()).isFalse();
        // Not the fabricated 1 the old catch produced.
        assertThat(record.exitCode()).isEqualTo(3);
    }

    @Test
    void a_live_client_still_receives_the_terminal() throws Exception {
        StringBuilder wire = new StringBuilder();
        BufferedWriter writer = new BufferedWriter(new Writer() {
            @Override
            public void write(char[] cbuf, int off, int len) {
                wire.append(cbuf, off, len);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        });
        RecordingHost host = new RecordingHost();

        WorkspaceTerminal.finish(host, writer, "/w", result(true, 0, List.of()), false);

        String line = wire.toString().strip();
        assertThat(EngineProtocol.typeOf(line)).isEqualTo(EngineProtocol.WORKSPACE_FINISH);
        assertThat(Jsonl.bool(line, "success", false)).isTrue();
        assertThat(Jsonl.bool(line, "cancelled", true)).isFalse();
    }

    // --- JK-2387: the terminal's error rows are worker output, and .env values are secret -------

    private static final String SECRET = "jk-2387-must-not-leak-s3cret-token";

    @Test
    void a_dotenv_value_in_worker_error_text_never_reaches_the_wire(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve(".env"), "REGISTRY_TOKEN=" + SECRET + "\n");
        RecordingHost host = new RecordingHost();

        WorkspaceTerminal.finish(
                host,
                null,
                project.toString(),
                result(false, 1, List.of("push rejected: bad credential " + SECRET)),
                false);

        assertThat(host.quietSends).hasSize(1);
        assertThat(host.quietSends.get(0)).doesNotContain(SECRET).contains(SecretRedactor.MASK);
    }

    @Test
    void a_dotenv_value_never_reaches_the_sse_and_journal_sink(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve(".env"), "REGISTRY_TOKEN=" + SECRET + "\n");
        RecordingHost host = new RecordingHost();

        WorkspaceTerminal.finish(
                host,
                null,
                project.toString(),
                result(false, 1, List.of("push rejected: bad credential " + SECRET)),
                false);

        // publishRequestError is the SSE hub's and the journal's view of the same rows.
        assertThat(host.requestErrors).hasSize(1);
        assertThat(host.requestErrors.get(0)).doesNotContain(SECRET).contains(SecretRedactor.MASK);
    }

    /** Guards the two above: without the .env there is nothing to mask, so they could pass empty. */
    @Test
    void the_same_text_is_left_alone_when_no_dotenv_declares_it(@TempDir Path project) {
        RecordingHost host = new RecordingHost();

        WorkspaceTerminal.finish(
                host,
                null,
                project.toString(),
                result(false, 1, List.of("push rejected: bad credential " + SECRET)),
                false);

        assertThat(host.requestErrors).containsExactly("push rejected: bad credential " + SECRET);
    }

    private static BuildRecord journal(JobOutcome outcome) {
        BuildAccumulator acc = new BuildAccumulator("build", "/w", "g:w", "cli");
        acc.setOutcome(outcome.success(), outcome.exitCode());
        return acc.toRecord(1_000L, false, 10L, "test", null);
    }

    /**
     * The real transport: {@link WireWriter} is the sole owner of the socket write, so the fake
     * host delegates to it rather than re-deciding what a broken pipe does.
     */
    private static final class RecordingHost implements VerbHost {
        private final List<String> quietSends = new ArrayList<>();
        private final List<String> requestErrors = new ArrayList<>();
        private int throwingSends;

        @Override
        public void send(BufferedWriter writer, String line) throws IOException {
            throwingSends++;
            WireWriter.send(writer, line);
        }

        @Override
        public void sendQuiet(BufferedWriter writer, String line) {
            quietSends.add(line);
            WireWriter.sendQuiet(writer, line);
        }

        @Override
        public void publishRequestError(long rid, String dir, String message) {
            requestErrors.add(message);
        }

        @Override
        public long eventRequestId() {
            return 7L;
        }

        @Override
        public void putProgressRoot(long rid, String dir) {}

        @Override
        public WorkspaceBuildListener workspaceListener(BufferedWriter writer, String dir) {
            return new WorkspaceBuildListener() {};
        }

        @Override
        public BuildPlanListener planListener(String dir, BufferedWriter writer, BuildPlan plan) {
            return new BuildPlanListener() {};
        }

        @Override
        public BuildPlanListener planListener(
                String dir, BufferedWriter writer, Function<BuildPlanResult, String> finishEncoder) {
            return new BuildPlanListener() {};
        }

        @Override
        public void releaseExclusiveSlot() {}

        @Override
        public boolean effectiveCancelled(long rid, boolean tokenCancelled) {
            return tokenCancelled;
        }

        @Override
        public void accTests(long rid, TestSummary tests) {}

        @Override
        public void finishProgress(long rid) {}

        @Override
        public void emitWorkspaceProgress(long rid, BufferedWriter writer, boolean force) {}

        @Override
        public void flushTimeline(long rid, BufferedWriter writer) {}

        @Override
        public String redactEnv(String dir, String text) {
            return text;
        }

        @Override
        public String requestFailedLine(String dir, Throwable e) {
            return "{\"type\":\"request-failed\"}";
        }

        @Override
        public Session resolveSession(String requestLine, Session.CancelToken cancel, boolean refresh) {
            return Session.defaults().withCancel(cancel);
        }

        @Override
        public void maybeEnqueuePrune(Path cache) {}
    }
}
