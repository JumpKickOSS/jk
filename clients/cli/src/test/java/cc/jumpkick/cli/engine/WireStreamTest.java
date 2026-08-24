// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProtoEvents;
import cc.jumpkick.engine.protocol.ProtoLifecycle;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The beats every hosted verb inherits from the one read loop: the job's jid is a live cancel
 * handle for exactly as long as the stream is open, a job stream does not return until the engine
 * has finished writing, and an inline read never waits for a line no job will send.
 */
class WireStreamTest {

    @BeforeEach
    @AfterEach
    void reset() {
        EngineClient.ActiveJobs.forgetAll();
        SessionContext.reset();
    }

    private static BufferedReader stream(String... lines) {
        return new BufferedReader(new StringReader(String.join("\n", lines) + "\n"));
    }

    /** A reader that records every line the loop actually consumed. */
    private static BufferedReader recording(List<String> consumed, String... lines) {
        BufferedReader source = stream(lines);
        return new BufferedReader(source) {
            @Override
            public String readLine() throws IOException {
                String line = source.readLine();
                if (line != null) consumed.add(line);
                return line;
            }
        };
    }

    @Test
    void the_jid_is_a_live_cancel_handle_for_the_whole_job_stream() throws Exception {
        // This set is exactly what EngineClient.cancelBestEffortForInterrupt reads on Ctrl-C, and
        // it is the only handle that reaches a verb whose journal dir is the cache rather than the
        // user's project — jk cache prune, jk tool resolve, jk tool run <script>.
        List<Set<Long>> seenMidStream = new ArrayList<>();
        BufferedReader reader = stream(
                ProtoLifecycle.jobStart(77, "cache", "/cache", 0),
                ProtoEvents.planDone(0),
                ProtoEvents.planFinish("/cache", true),
                ProtoLifecycle.jobFinish(77));

        String outcome = WireStream.pumpJob(reader, null, (type, line) -> {
            seenMidStream.add(EngineClient.ActiveJobs.snapshot());
            return EngineProtocol.BUILDPLAN_FINISH.equals(type) ? "done" : null;
        });

        assertThat(outcome).isEqualTo("done");
        assertThat(seenMidStream).isNotEmpty().allMatch(live -> live.equals(Set.of(77L)));
        // …and it is dropped on the way out: a stale jid costs every later Ctrl-C a 2s cancel RPC.
        assertThat(EngineClient.ActiveJobs.snapshot()).isEmpty();
    }

    @Test
    void the_jid_is_forgotten_even_when_the_stream_fails() {
        BufferedReader reader = stream(ProtoLifecycle.jobStart(78, "tool", "/cache", 0), ProtoEvents.planDone(0));

        assertThatThrownBy(() -> WireStream.pumpJob(reader, null, (type, line) -> {
                    throw new IOException("boom");
                }))
                .hasMessage("boom");

        assertThat(EngineClient.ActiveJobs.snapshot()).isEmpty();
    }

    @Test
    void a_job_stream_waits_for_job_finish_before_returning() throws Exception {
        // The engine keeps writing after the verb's terminal — a build's preflight memos, and for
        // every journaled kind the journal run dir and its target/jk-results.md copy. Returning on
        // the terminal hands the caller a tree the engine is still writing into.
        List<String> consumed = new ArrayList<>();
        BufferedReader reader = recording(
                consumed,
                ProtoLifecycle.jobStart(79, "build", "/proj", 3),
                ProtoEvents.planFinish("/proj", true),
                ProtoLifecycle.jobFinish(79),
                "{\"type\":\"past-the-end\"}");

        String outcome = WireStream.pumpJob(
                reader, null, (type, line) -> EngineProtocol.BUILDPLAN_FINISH.equals(type) ? "done" : null);

        assertThat(outcome).isEqualTo("done");
        assertThat(consumed).anyMatch(l -> l.contains("job-finish"));
        // …and not one line further: the wait ends at job-finish, it does not drain the socket.
        assertThat(consumed).noneMatch(l -> l.contains("past-the-end"));
    }

    @Test
    void an_inline_read_returns_at_its_terminal_and_waits_for_nothing() throws Exception {
        // A VerbShape.SyncRead verb is answered on the engine's connection thread: no job is
        // admitted, so no job-finish will ever arrive and waiting would only stall on the socket.
        List<String> consumed = new ArrayList<>();
        BufferedReader reader = recording(consumed, ProtoEvents.planDone(0), ProtoEvents.planFinish("/proj", true));

        String outcome = WireStream.pumpRead(
                reader, (type, line) -> EngineProtocol.BUILDPLAN_FINISH.equals(type) ? "done" : null);

        assertThat(outcome).isEqualTo("done");
        assertThat(consumed).hasSize(2);
    }

    @Test
    void a_terminal_less_stream_reports_a_crash() {
        BufferedReader reader = stream(ProtoEvents.planDone(0));

        assertThatThrownBy(() -> WireStream.pumpJob(reader, null, (type, line) -> null))
                .isInstanceOf(IOException.class)
                .isNotInstanceOf(JobCancelledException.class)
                .hasMessageContaining("disconnected unexpectedly");
    }

    @Test
    void a_terminal_less_stream_after_ctrl_c_is_the_cancel_settling() {
        Session cancelling = Session.defaults().withCancel(Session.CancelToken.live());
        SessionContext.install(cancelling);
        cancelling.cancel().cancel();
        BufferedReader reader = stream(ProtoEvents.planDone(0));

        assertThatThrownBy(() -> WireStream.pumpJob(reader, null, (type, line) -> null))
                .isInstanceOf(JobCancelledException.class);
    }
}
