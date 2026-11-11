// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import static cc.jumpkick.engine.jobs.FakeEnvelopeHost.buildRequest;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JobLimits;
import cc.jumpkick.engine.api.BuildJobFingerprint;
import cc.jumpkick.engine.api.InFlightBuilds;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JobAdmitRaceTest {

    /**
     * A same-checkout build that claims the slot between the envelope's early check and admission
     * gets the request refused; the refused request must not leave a progress session behind, since
     * nothing ever retires the id of a job that never started.
     */
    @Test
    void a_build_refused_at_admission_opens_no_progress_session(@TempDir Path dir) {
        FakeEnvelopeHost host = new FakeEnvelopeHost();
        JobEnvelope env = new JobEnvelope(host, JobLimits.DEFAULTS);
        String line = buildRequest(dir);
        String fingerprint = requireNonNull(BuildJobFingerprint.ofRequest("build", line));
        AtomicBoolean raced = new AtomicBoolean();
        host.onNextRequestId = () -> {
            if (raced.compareAndSet(false, true)) {
                host.inFlight.tryAcquire(new InFlightBuilds.Hold(
                        999L, 1L, fingerprint, "build", dir.toString(), null, 1L, null, "cli", null));
            }
        };

        assertThatThrownBy(() -> env.submit(
                        line,
                        JobRequest.workspace("build", "jk-test-", (l, tok, w) -> JobOutcome.declined()),
                        new JobTransport.FireAndForget()))
                .isInstanceOf(JobEnvelope.AlreadyRunning.class);

        assertThat(raced).isTrue();
        assertThat(host.moded).allSatisfy(id -> assertThat(host.cleared).contains(id));
    }
}
