// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JobLimits;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.run.JkThreads;
import cc.jumpkick.testing.Await;
import cc.jumpkick.wire.protocol.EngineProtocol;
import java.io.StringWriter;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Every job streams its run's downloads to its client, whichever code moved the bytes. */
class JobEnvelopeDownloadsTest {

    /**
     * The job's body downloads on a pool thread, the way every fetch does, through the session it
     * already has. The frame reaches this job's own stream with nothing passed down to the code that
     * moved the bytes — which is what lets every verb and every caller show its downloads.
     */
    @Test
    void a_download_anywhere_in_the_job_reaches_its_stream() {
        FakeEnvelopeHost host = new FakeEnvelopeHost();
        JobEnvelope env = new JobEnvelope(host, JobLimits.DEFAULTS);
        StringWriter out = new StringWriter();
        ConnectedClient.submit(
                env,
                "{\"type\":\"build-request\",\"dir\":\"/tmp/job-env\"}",
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
    void a_job_that_downloads_nothing_sends_no_download_frames() {
        FakeEnvelopeHost host = new FakeEnvelopeHost();
        JobEnvelope env = new JobEnvelope(host, JobLimits.DEFAULTS);
        StringWriter out = new StringWriter();
        ConnectedClient.submit(
                env,
                "{\"type\":\"build-request\",\"dir\":\"/tmp/job-env\"}",
                JobRequest.plan("build", "jk-test-", (line, tok, w) -> JobOutcome.ok()),
                out);
        assertThat(out.toString()).doesNotContain("\"type\":\"downloads\"");
    }
}
