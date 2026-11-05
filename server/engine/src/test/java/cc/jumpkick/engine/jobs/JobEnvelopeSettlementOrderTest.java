// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import static cc.jumpkick.engine.jobs.FakeEnvelopeHost.buildRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JobLimits;
import cc.jumpkick.engine.journal.BuildAccumulator;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.testing.Await;
import cc.jumpkick.wire.protocol.EngineProtocol;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The order a job's observable effects leave the envelope in, on every way a job can end. */
class JobEnvelopeSettlementOrderTest {

    /**
     * The order one successful plan job's observable effects leave the envelope in. The journal is
     * written while the job holds its plan slot; the slot is released before request-finish so the
     * event carries the post-finish plan count; clearProgress retires the session after both; the
     * idle boundary comes last.
     */
    @Test
    void a_successful_job_emits_its_effects_in_one_order_with_the_record_written_first(@TempDir Path dir)
            throws Exception {
        FakeEnvelopeHost host = new FakeEnvelopeHost();
        host.accumulator = new BuildAccumulator("build", dir.toString(), null, "cli");
        // Detached: a socket whose reader is already at EOF reads as a client disconnect, which
        // would race the body's own verdict. The wire terminal is pinned by the journal test below.
        new JobEnvelope(host, JobLimits.DEFAULTS)
                .submit(
                        buildRequest(dir),
                        JobRequest.plan("build", "jk-test-", (line, tok, w) -> JobOutcome.ok()),
                        new JobTransport.FireAndForget());
        host.awaitSettled();

        assertThat(host.sequence)
                .containsExactly(
                        "request-start",
                        "progress-pinned:100.0",
                        "writeJournal",
                        "plan-slot-released",
                        "request-finish",
                        "clearProgress",
                        "idle-boundary");
        String finish = host.requestFinish();
        assertThat(keysOf(finish))
                .containsExactly(
                        "schema",
                        "type",
                        "jid",
                        "kind",
                        "dir",
                        "projectId",
                        "success",
                        "cancelled",
                        "millis",
                        "activeBuildPlans");
        assertThat(Jsonl.intValue(finish, "activeBuildPlans", -1))
                .as("the count after the release, so the dashboard settles in the same frame")
                .isZero();
        assertThat(Jsonl.intValue(finish, "schema", -1)).isEqualTo(1);
    }

    /**
     * A draining engine exits once no plan holds a slot, on a thread that does not wait for a
     * detached job's settlement. So the slot must outlive the record: while the record is still
     * being written the job counts as in flight, and once the count reads zero the record is there.
     */
    @Test
    void a_detached_job_holds_its_plan_slot_until_its_record_is_written(@TempDir Path dir) throws Exception {
        FakeEnvelopeHost host = new FakeEnvelopeHost();
        host.accumulator = new BuildAccumulator("build", dir.toString(), null, "mcp");
        CountDownLatch held = new CountDownLatch(1);
        host.journalHeld = held;
        new JobEnvelope(host, JobLimits.DEFAULTS)
                .submit(
                        buildRequest(dir),
                        JobRequest.plan("build", "jk-test-", (line, tok, w) -> JobOutcome.ok()),
                        new JobTransport.FireAndForget());
        Await.until(Duration.ofSeconds(30), () -> host.sequence.contains("writeJournal"));

        assertThat(host.activePlans.get())
                .as("a job whose record is still being written is in flight")
                .isEqualTo(1);
        assertThat(host.sequence).doesNotContain("plan-slot-released", "request-finish");

        held.countDown();
        host.awaitSettled();
        assertThat(host.activePlans.get()).isZero();
        assertThat(host.recordWrittenAtRelease)
                .as("no jobs in flight means every record written")
                .isTrue();
    }

    /** A failed and a thrown body share the success path's order and never pin the bar at 100%. */
    @Test
    void a_failed_and_a_thrown_job_emit_the_same_order_without_the_progress_pin(@TempDir Path dir) throws Exception {
        for (JobBody body : List.<JobBody>of((line, tok, w) -> JobOutcome.failed(Exit.SOFTWARE), (line, tok, w) -> {
            throw new IllegalStateException("boom");
        })) {
            FakeEnvelopeHost host = new FakeEnvelopeHost();
            host.accumulator = new BuildAccumulator("build", dir.toString(), null, "cli");
            new JobEnvelope(host, JobLimits.DEFAULTS)
                    .submit(
                            buildRequest(dir),
                            JobRequest.plan("build", "jk-test-", body),
                            new JobTransport.FireAndForget());
            host.awaitSettled();
            assertThat(host.sequence)
                    .containsExactly(
                            "request-start",
                            "writeJournal",
                            "plan-slot-released",
                            "request-finish",
                            "clearProgress",
                            "idle-boundary");
            String finish = host.requestFinish();
            assertThat(Jsonl.bool(finish, "success", true)).isFalse();
            assertThat(Jsonl.has(finish, "cancelReason")).isFalse();
        }
    }

    /** A plan admitted in another workspace than the one before it tells the host which it left. */
    @Test
    void a_plan_in_another_workspace_reports_the_switch_and_one_in_the_same_does_not(@TempDir Path dir)
            throws Exception {
        FakeEnvelopeHost host = new FakeEnvelopeHost();
        Path a = dir.resolve("switch-a");
        Path b = dir.resolve("switch-b");
        for (Path workspace : List.of(a, a, b)) {
            host.accumulator = new BuildAccumulator("build", workspace.toString(), null, "cli");
            new JobEnvelope(host, JobLimits.DEFAULTS)
                    .submit(
                            buildRequest(workspace),
                            JobRequest.plan("build", "jk-test-", (line, tok, w) -> JobOutcome.ok()),
                            new JobTransport.FireAndForget());
            host.awaitSettled();
            host.sequence.clear();
        }
        assertThat(host.switches).endsWith(a + " -> " + b);
        assertThat(host.switches).doesNotContain(a + " -> " + a);
    }

    /** A cancelled job adds exactly one key, cancelReason, and still ends on the same tail. */
    @Test
    void a_cancelled_job_adds_cancel_reason_and_nothing_else(@TempDir Path dir) throws Exception {
        FakeEnvelopeHost host = new FakeEnvelopeHost();
        host.accumulator = new BuildAccumulator("build", dir.toString(), null, "cli");
        JobEnvelope env = new JobEnvelope(host, JobLimits.DEFAULTS);
        env.submit(
                buildRequest(dir),
                JobRequest.plan("build", "jk-test-", (line, tok, w) -> {
                    env.live().beginUserCancel(1L, tok, null, 0L, true);
                    return JobOutcome.declined();
                }),
                new JobTransport.FireAndForget());
        host.awaitSettled();

        String finish = host.requestFinish();
        assertThat(Jsonl.bool(finish, "cancelled", false)).isTrue();
        assertThat(keysOf(finish)).endsWith("activeBuildPlans", "cancelReason");
        assertThat(Jsonl.str(finish, "cancelReason")).contains("cancelled by the user");
        assertThat(host.sequence)
                .endsWith("writeJournal", "plan-slot-released", "request-finish", "clearProgress", "idle-boundary");
    }

    /**
     * The slot release, request-finish and job-finish are in a finally, so a throwing journal
     * cannot strand the client on EOF or hold a draining engine open.
     */
    @Test
    void a_throwing_journal_still_delivers_job_finish(@TempDir Path dir) throws Exception {
        FakeEnvelopeHost host = new FakeEnvelopeHost();
        host.accumulator = new BuildAccumulator("build", dir.toString(), null, "cli");
        host.journalThrows = true;
        StringWriter out = new StringWriter();
        assertThatThrownBy(() -> ConnectedClient.submit(
                        new JobEnvelope(host, JobLimits.DEFAULTS),
                        buildRequest(dir),
                        JobRequest.plan("build", "jk-test-", (line, tok, w) -> JobOutcome.ok()),
                        out))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("journal disk full");
        assertThat(out.toString().lines().map(EngineProtocol::typeOf))
                .as("the client reads its terminal, not EOF")
                .contains(EngineProtocol.JOB_FINISH);
        assertThat(host.sequence).containsSubsequence("writeJournal", "plan-slot-released", "request-finish");
        assertThat(host.activePlans.get()).isZero();
    }

    /** Top-level keys of a flat JSON object, in document order. */
    private static List<String> keysOf(String json) {
        List<String> keys = new ArrayList<>();
        Matcher m = Pattern.compile("\"(\\w+)\":").matcher(json);
        while (m.find()) keys.add(m.group(1));
        return keys;
    }
}
