// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import static cc.jumpkick.engine.jobs.FakeEnvelopeHost.buildRequest;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JobLimits;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.run.JkThreads;
import cc.jumpkick.testing.Await;
import cc.jumpkick.wire.protocol.EngineProtocol;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Every job streams its run's downloads to its client, whichever code moved the bytes. */
class JobEnvelopeDownloadsTest {

    /**
     * The job's body downloads on a pool thread, the way every fetch does, through the session it
     * already has. The frame reaches this job's own stream with nothing passed down to the code that
     * moved the bytes — which is what lets every verb and every caller show its downloads.
     */
    @Test
    void a_download_anywhere_in_the_job_reaches_its_stream(@TempDir Path dir) {
        FakeEnvelopeHost host = new FakeEnvelopeHost();
        JobEnvelope env = new JobEnvelope(host, JobLimits.DEFAULTS);
        StringWriter out = new StringWriter();
        ConnectedClient.submit(
                env,
                buildRequest(dir),
                JobRequest.plan("sync", "jk-test-", (line, tok, w) -> {
                    // A verb's body runs under the session it builds inside the job, as every verb
                    // does (ProtoSession.sessionOf): that session takes the run's ledger, and with
                    // it the run's board.
                    try {
                        SessionContext.where(Session.defaults(), () -> JkThreads.io()
                                .submit(() -> {
                                    try (var t = SessionContext.current()
                                            .io()
                                            .transfers()
                                            .open("g:a:1")) {
                                        t.started(4_096);
                                        t.advanced(4_096);
                                        t.finished();
                                    }
                                })
                                .get());
                        Await.until(Duration.ofSeconds(10), () -> out.toString().contains("\"type\":\"downloads\""));
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    return JobOutcome.ok();
                }),
                out);
        String frame = out.toString()
                .lines()
                .filter(l -> EngineProtocol.DOWNLOADS.equals(EngineProtocol.typeOf(l)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no downloads frame on the stream:\n" + out));
        assertThat(Jsonl.longValue(frame, "bytes", 0)).isEqualTo(4_096);
        assertThat(Jsonl.intValue(frame, "files", 0)).isEqualTo(1);
    }

    @Test
    void a_job_that_downloads_nothing_sends_no_download_frames(@TempDir Path dir) {
        FakeEnvelopeHost host = new FakeEnvelopeHost();
        JobEnvelope env = new JobEnvelope(host, JobLimits.DEFAULTS);
        StringWriter out = new StringWriter();
        ConnectedClient.submit(
                env, buildRequest(dir), JobRequest.plan("build", "jk-test-", (line, tok, w) -> JobOutcome.ok()), out);
        assertThat(out.toString()).doesNotContain("\"type\":\"downloads\"");
    }

    /**
     * A job whose download ends faster than one sample still says so: the final state goes out as
     * the job winds down, before its job-finish line.
     */
    @Test
    void a_download_no_sample_caught_is_sent_as_the_job_ends(@TempDir Path dir) {
        FakeEnvelopeHost host = new FakeEnvelopeHost();
        JobEnvelope env = new JobEnvelope(host, JobLimits.DEFAULTS);
        StringWriter out = new StringWriter();
        ConnectedClient.submit(
                env,
                buildRequest(dir),
                JobRequest.plan("sync", "jk-test-", (line, tok, w) -> {
                    try {
                        SessionContext.where(Session.defaults(), () -> {
                            try (var t =
                                    SessionContext.current().io().transfers().open("g:a:1")) {
                                t.started(100);
                                t.advanced(100);
                                t.finished();
                            }
                            return null;
                        });
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    return JobOutcome.ok();
                }),
                out);
        List<String> lines = out.toString().lines().toList();
        int downloads = -1;
        int finish = -1;
        for (int i = 0; i < lines.size(); i++) {
            String type = EngineProtocol.typeOf(lines.get(i));
            if (EngineProtocol.DOWNLOADS.equals(type)) downloads = i;
            if (EngineProtocol.JOB_FINISH.equals(type)) finish = i;
        }
        assertThat(downloads).as("a downloads frame:\n%s", out).isNotNegative();
        assertThat(downloads).isLessThan(finish);
        assertThat(Jsonl.intValue(lines.get(downloads), "files", 0)).isEqualTo(1);
        assertThat(Jsonl.intValue(lines.get(downloads), "inflight", -1)).isZero();
    }

    /**
     * A job the web UI or an agent started has no CLI stream, and its downloads still reach the
     * dashboard and MCP subscribers: the same event, field for field, tagged with the job.
     */
    @Test
    void a_detached_jobs_downloads_reach_the_dashboard_and_mcp_events(@TempDir Path dir) throws Exception {
        FakeEnvelopeHost host = new FakeEnvelopeHost();
        JobEnvelope env = new JobEnvelope(host, JobLimits.DEFAULTS);
        long jid = env.submit(
                buildRequest(dir),
                JobRequest.plan("sync", "jk-test-", (line, tok, w) -> {
                    try {
                        SessionContext.where(Session.defaults(), () -> {
                            try (var t =
                                    SessionContext.current().io().transfers().open("g:a:1")) {
                                t.started(2_048);
                                t.advanced(2_048);
                                t.finished();
                            }
                            return null;
                        });
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    return JobOutcome.ok();
                }),
                new JobTransport.FireAndForget());
        Await.until(Duration.ofSeconds(30), () -> host.finished.get() == 1);

        String event = List.copyOf(host.events).stream()
                .filter(e -> e.startsWith(EngineProtocol.DOWNLOADS + ":"))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("no downloads event published: " + host.events));
        String json = event.substring(EngineProtocol.DOWNLOADS.length() + 1);
        assertThat(Jsonl.longValue(json, "jid", -1)).isEqualTo(jid);
        assertThat(Jsonl.longValue(json, "bytes", 0)).isEqualTo(2_048);
        assertThat(Jsonl.intValue(json, "files", 0)).isEqualTo(1);
        host.awaitSettled();
    }
}
