// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import static cc.jumpkick.engine.jobs.FakeEnvelopeHost.buildRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JobLimits;
import cc.jumpkick.testing.Await;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * A throw between the plan slot and the runner's start: everything the job claimed is given back,
 * so the engine can drain and the next build of the same checkout runs. A start that fails once the
 * runner is running still settles the job. Each test is bounded, since the failure is a wait that never ends.
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class JobEnvelopeUnstartedTest {

    /** Threads that refuse to start the one named {@code refused}. */
    private static JobEnvelope.JobThreads refusing(String refused) {
        return (name, body) -> {
            if (name.equals(refused)) throw new OutOfMemoryError("unable to create thread");
            return JobEnvelope.JobThreads.VIRTUAL.start(name, body);
        };
    }

    private static JobEnvelope envelope(
            FakeEnvelopeHost host, MemoryAdmission admission, JobEnvelope.JobThreads threads) {
        return new JobEnvelope(host, JobLimits.DEFAULTS, admission, 50L, 0L, threads);
    }

    private static JobRequest declining(AtomicBoolean ran) {
        return JobRequest.plan("build", "jk-test-", (line, tok, w) -> {
            ran.set(true);
            return JobOutcome.declined();
        });
    }

    @Test
    void a_throw_after_admission_gives_back_the_slot_the_memory_and_the_checkout(@TempDir Path dir) throws Exception {
        FakeEnvelopeHost host = new FakeEnvelopeHost();
        MemoryAdmission admission = MemoryAdmission.forRuntime(JobLimits.DEFAULTS.queueWaitMs(), host::nowMillis);
        JobEnvelope env = new JobEnvelope(host, JobLimits.DEFAULTS, admission);
        host.registerThrows = new IllegalStateException("timeline unwritable");
        AtomicBoolean ran = new AtomicBoolean();
        StringWriter out = new StringWriter();

        // Nothing escapes to the connection loop, which would answer after the job's terminal.
        ConnectedClient.submit(env, buildRequest(dir), declining(ran), out);

        assertThat(ran).isFalse();
        assertThat(host.activeBuildPlans()).as("plan slots held").isZero();
        assertThat(admission.admittedCount()).as("memory shares held").isZero();
        assertThat(host.inFlight.list()).as("checkouts held").isEmpty();
        assertThat(env.jobs()).as("live jobs").isEmpty();
        assertThat(host.sequence).contains("request-start", "request-finish", "clearProgress");
        assertThat(out.toString()).contains("\"request-failed\"").contains("\"job-finish\"");
        assertThat(host.logs).anyMatch(l -> l.contains("died") && l.contains("timeline unwritable"));

        host.registerThrows = null;
        ConnectedClient.submit(env, buildRequest(dir), declining(ran), new StringWriter());
        assertThat(ran).as("the next build of the checkout is admitted").isTrue();
        assertThat(host.activeBuildPlans()).isZero();
    }

    @Test
    void a_detached_throw_after_admission_gives_back_what_it_claimed(@TempDir Path dir) throws Exception {
        FakeEnvelopeHost host = new FakeEnvelopeHost();
        MemoryAdmission admission = MemoryAdmission.forRuntime(JobLimits.DEFAULTS.queueWaitMs(), host::nowMillis);
        JobEnvelope env = new JobEnvelope(host, JobLimits.DEFAULTS, admission);
        host.registerThrows = new IllegalStateException("timeline unwritable");
        AtomicBoolean ran = new AtomicBoolean();

        assertThatThrownBy(() -> env.submit(buildRequest(dir), declining(ran), new JobTransport.FireAndForget()))
                .hasMessage("timeline unwritable");

        assertThat(host.activeBuildPlans()).isZero();
        assertThat(admission.admittedCount()).isZero();
        assertThat(host.inFlight.list()).isEmpty();
        assertThat(env.jobs()).isEmpty();
        assertThat(host.sequence).contains("request-finish");
    }

    @Test
    void a_detached_error_after_admission_reaches_its_caller(@TempDir Path dir) {
        FakeEnvelopeHost host = new FakeEnvelopeHost();
        MemoryAdmission admission = MemoryAdmission.forRuntime(JobLimits.DEFAULTS.queueWaitMs(), host::nowMillis);
        JobEnvelope env = new JobEnvelope(host, JobLimits.DEFAULTS, admission);
        host.registerThrows = new StackOverflowError("deep");
        AtomicBoolean ran = new AtomicBoolean();

        assertThatThrownBy(() -> env.submit(buildRequest(dir), declining(ran), new JobTransport.FireAndForget()))
                .isInstanceOf(StackOverflowError.class);

        assertThat(host.activeBuildPlans()).isZero();
        assertThat(admission.admittedCount()).isZero();
        assertThat(host.inFlight.list()).isEmpty();
    }

    @Test
    void a_throw_inside_admission_gives_back_the_slot_and_the_memory(@TempDir Path dir) {
        FakeEnvelopeHost host = new FakeEnvelopeHost();
        MemoryAdmission admission = MemoryAdmission.forRuntime(JobLimits.DEFAULTS.queueWaitMs(), host::nowMillis);
        JobEnvelope env = new JobEnvelope(host, JobLimits.DEFAULTS, admission);
        host.coordThrows = new IllegalStateException("no coordinate");
        AtomicBoolean ran = new AtomicBoolean();

        assertThatThrownBy(() -> ConnectedClient.submit(env, buildRequest(dir), declining(ran), new StringWriter()))
                .hasMessage("no coordinate");

        assertThat(host.abandoned)
                .as("the slot is given back as a refusal gives it back")
                .hasValue(1);
        assertThat(host.finished).hasValue(0);
        assertThat(admission.admittedCount()).isZero();
        assertThat(host.inFlight.list()).isEmpty();
    }

    @Test
    void a_watchdog_that_will_not_start_still_settles_the_job(@TempDir Path dir) throws Exception {
        FakeEnvelopeHost host = new FakeEnvelopeHost();
        MemoryAdmission admission = MemoryAdmission.forRuntime(JobLimits.DEFAULTS.queueWaitMs(), host::nowMillis);
        JobEnvelope env = envelope(host, admission, refusing("jk-job-watchdog"));
        AtomicBoolean ran = new AtomicBoolean();
        StringWriter out = new StringWriter();

        ConnectedClient.submit(env, buildRequest(dir), declining(ran), out);

        assertThat(ran).isTrue();
        assertThat(out.toString()).contains("\"job-finish\"");
        assertThat(host.finished).as("plan slots released").hasValue(1);
        assertThat(host.activeBuildPlans()).isZero();
        assertThat(admission.admittedCount()).isZero();
        assertThat(host.inFlight.list()).isEmpty();
        assertThat(host.sequence).contains("request-finish");
        assertThat(host.logs).anyMatch(l -> l.contains("without its watchdog"));
    }

    @Test
    void a_detached_watchdog_that_will_not_start_still_settles_the_job(@TempDir Path dir) throws Exception {
        FakeEnvelopeHost host = new FakeEnvelopeHost();
        MemoryAdmission admission = MemoryAdmission.forRuntime(JobLimits.DEFAULTS.queueWaitMs(), host::nowMillis);
        JobEnvelope env = envelope(host, admission, refusing("jk-job-watchdog"));
        AtomicBoolean ran = new AtomicBoolean();

        env.submit(buildRequest(dir), declining(ran), new JobTransport.FireAndForget());
        host.awaitSettled();

        assertThat(ran).isTrue();
        Await.until(Duration.ofSeconds(30), () -> host.finished.get() == 1, () -> "the plan slot was never released");
        assertThat(host.activeBuildPlans()).isZero();
        assertThat(admission.admittedCount()).isZero();
        assertThat(host.sequence).contains("request-finish");
    }

    @Test
    void a_joiner_that_will_not_start_settles_the_job_on_the_submitting_thread(@TempDir Path dir) {
        FakeEnvelopeHost host = new FakeEnvelopeHost();
        MemoryAdmission admission = MemoryAdmission.forRuntime(JobLimits.DEFAULTS.queueWaitMs(), host::nowMillis);
        JobEnvelope env = envelope(host, admission, refusing("jk-job-join-"));
        AtomicBoolean ran = new AtomicBoolean();

        long jid = env.submit(buildRequest(dir), declining(ran), new JobTransport.FireAndForget());

        assertThat(jid).isPositive();
        assertThat(ran).isTrue();
        assertThat(host.finished).as("plan slots released").hasValue(1);
        assertThat(host.activeBuildPlans()).isZero();
        assertThat(admission.admittedCount()).isZero();
        assertThat(host.inFlight.list()).isEmpty();
        assertThat(host.sequence).contains("request-finish");
        assertThat(host.logs).anyMatch(l -> l.contains("joined on its submitting thread"));
    }
}
