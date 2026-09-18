// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JobLimits;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.testing.Await;
import cc.jumpkick.wire.protocol.JobQueuedFrame;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** The envelope's memory queue as the wire, the dashboard and a cancel see it. */
class JobEnvelopeQueueTest {

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A heap whose committed size never moves: the gate's ledger alone decides. */
    private static MemoryAdmission gateFor(long maxMib, long idleMib, long perJobMib) {
        MemoryAdmission.Heap heap = new MemoryAdmission.Heap() {
            @Override
            public long maxBytes() {
                return maxMib << 20;
            }

            @Override
            public long committedBytes() {
                return idleMib << 20;
            }

            @Override
            public void collect() {}
        };
        return new MemoryAdmission(heap, (kind, dir) -> perJobMib << 20, () -> 64L << 30, PATIENT, () -> 1_000L);
    }

    /** No fairness admission and no give-up inside a test's lifetime; the ledger alone decides. */
    static final MemoryAdmission.Timing PATIENT =
            new MemoryAdmission.Timing(Long.MAX_VALUE / 4, 0L, Long.MAX_VALUE / 4);

    /** As {@link #gateFor} on the test's own clock and timing, so a wait can be advanced by hand. */
    private static MemoryAdmission gateFor(
            long maxMib,
            long idleMib,
            long perJobMib,
            MemoryAdmission.Timing timing,
            MemoryAdmissionTest.FakeClock clock) {
        MemoryAdmission.Heap heap = new MemoryAdmission.Heap() {
            @Override
            public long maxBytes() {
                return maxMib << 20;
            }

            @Override
            public long committedBytes() {
                return idleMib << 20;
            }

            @Override
            public void collect() {}
        };
        return new MemoryAdmission(heap, (kind, dir) -> perJobMib << 20, () -> 64L << 30, timing, clock);
    }

    private static String firstLine(StringWriter out, String type) {
        return out.toString()
                .lines()
                .filter(l -> l.contains("\"type\":\"" + type + "\""))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void a_job_short_of_engine_memory_queues_with_one_wire_line_and_runs_when_the_heap_frees() throws Exception {
        FakeEnvelopeHost host = new FakeEnvelopeHost();
        // 200 MiB heap, 40 idle, 32 reserved: one 100 MiB job fits, the next waits for it.
        JobEnvelope env = new JobEnvelope(host, JobLimits.DEFAULTS, gateFor(200, 40, 100));
        CountDownLatch firstMayFinish = new CountDownLatch(1);
        long first = env.submit(
                "{\"type\":\"build-request\",\"dir\":\"/tmp/job-env-a\"}",
                JobRequest.plan("build", "jk-test-", (line, tok, w) -> {
                    awaitQuietly(firstMayFinish);
                    return JobOutcome.declined();
                }),
                new JobTransport.FireAndForget());
        assertThat(first).isPositive();
        AtomicBoolean secondRan = new AtomicBoolean();
        StringWriter out = new StringWriter();
        Thread second = Thread.ofVirtual()
                .start(() -> env.submit(
                        "{\"type\":\"build-request\",\"dir\":\"/tmp/job-env-b\"}",
                        JobRequest.plan("build", "jk-test-", (line, tok, w) -> {
                            secondRan.set(true);
                            return JobOutcome.declined();
                        }),
                        new JobTransport.SocketWatch(
                                new BufferedReader(new StringReader("")), new BufferedWriter(out))));
        // The wire line is written after the queue slot is taken, so it is the line that is awaited.
        Await.until(Duration.ofSeconds(5), () -> out.toString().contains("job-queued"));
        assertThat(env.queued()).isEqualTo(1);
        assertThat(out.toString()).contains("\"type\":\"job-queued\"").contains("\"ahead\":0");
        // The line lands on the stream's writer thread; the dashboard frame follows on this one.
        Await.until(Duration.ofSeconds(5), () -> host.events.stream().anyMatch(e -> e.startsWith("request-queued:")));
        assertThat(secondRan).isFalse();
        assertThat(host.activePlans).as("a queued job holds no plan slot").hasValue(1);

        firstMayFinish.countDown();
        second.join(Duration.ofSeconds(10));

        assertThat(secondRan).isTrue();
        assertThat(env.queued()).isZero();
        assertThat(out.toString()).contains("\"type\":\"job-start\"");
        assertThat(host.sequence.indexOf("request-queued"))
                .as("queued is announced before the second job's start")
                .isLessThan(host.sequence.lastIndexOf("request-start"));
    }

    @Test
    void a_queued_job_cancelled_by_jid_ends_on_the_cancelled_terminal_without_running() throws Exception {
        FakeEnvelopeHost host = new FakeEnvelopeHost();
        JobEnvelope env = new JobEnvelope(host, JobLimits.DEFAULTS, gateFor(200, 40, 100));
        CountDownLatch firstMayFinish = new CountDownLatch(1);
        env.submit(
                "{\"type\":\"build-request\",\"dir\":\"/tmp/job-env-a\"}",
                JobRequest.plan("build", "jk-test-", (line, tok, w) -> {
                    awaitQuietly(firstMayFinish);
                    return JobOutcome.declined();
                }),
                new JobTransport.FireAndForget());
        AtomicBoolean secondRan = new AtomicBoolean();
        StringWriter out = new StringWriter();
        AtomicLong secondResult = new AtomicLong(Long.MIN_VALUE);
        Thread second = Thread.ofVirtual()
                .start(() -> secondResult.set(env.submit(
                        "{\"type\":\"build-request\",\"dir\":\"/tmp/job-env-b\"}",
                        JobRequest.plan("build", "jk-test-", (line, tok, w) -> {
                            secondRan.set(true);
                            return JobOutcome.declined();
                        }),
                        new JobTransport.SocketWatch(
                                new BufferedReader(new StringReader("")), new BufferedWriter(out)))));
        // The wire line is written after the queue slot is taken, so it is the line that is awaited.
        Await.until(Duration.ofSeconds(5), () -> out.toString().contains("job-queued"));
        assertThat(env.queued()).isEqualTo(1);
        long queuedJid = Jsonl.longValue(
                out.toString()
                        .lines()
                        .filter(l -> l.contains("job-queued"))
                        .findFirst()
                        .orElseThrow(),
                "jid",
                -1);

        assertThat(env.cancelJob(queuedJid)).isTrue();
        second.join(Duration.ofSeconds(10));

        assertThat(secondResult).hasValue(-1L);
        assertThat(secondRan).isFalse();
        assertThat(out.toString()).contains("\"cancelled\":true");
        assertThat(host.events).anyMatch(e -> e.startsWith("request-finish:") && e.contains("\"cancelled\":true"));
        assertThat(host.abandoned)
                .as("no slot was claimed, so none is given back")
                .hasValue(0);
        firstMayFinish.countDown();
    }

    @Test
    void the_listing_names_the_live_job_and_the_queued_one_and_the_queued_line_names_who_it_waits_on()
            throws Exception {
        FakeEnvelopeHost host = new FakeEnvelopeHost();
        host.lastEventAt = 1_500L;
        JobEnvelope env = new JobEnvelope(host, JobLimits.DEFAULTS, gateFor(200, 40, 100));
        CountDownLatch firstMayFinish = new CountDownLatch(1);
        long first = env.submit(
                "{\"type\":\"test-request\",\"dir\":\"/tmp/job-env-suite\"}",
                JobRequest.plan("test", "jk-test-", (line, tok, w) -> {
                    awaitQuietly(firstMayFinish);
                    return JobOutcome.declined();
                }),
                new JobTransport.FireAndForget());
        StringWriter out = new StringWriter();
        Thread second = Thread.ofVirtual()
                .start(() -> env.submit(
                        "{\"type\":\"build-request\",\"dir\":\"/tmp/job-env-lib\"}",
                        JobRequest.plan("build", "jk-test-", (line, tok, w) -> JobOutcome.declined()),
                        new JobTransport.SocketWatch(
                                new BufferedReader(new StringReader("")), new BufferedWriter(out))));
        Await.until(Duration.ofSeconds(5), () -> out.toString().contains("job-queued"));

        List<JobRow> rows = env.jobs();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0))
                .as("the live job leads: admitted on the host's clock, no forked worker, its last event")
                .isEqualTo(JobRow.live(first, "test", "/tmp/job-env-suite", 1_000L, 0, 1_500L));
        assertThat(rows.get(1).live()).isFalse();
        assertThat(rows.get(1).kind()).isEqualTo("build");
        assertThat(rows.get(1).dir()).isEqualTo("/tmp/job-env-lib");
        assertThat(rows.get(1).ahead()).isZero();
        assertThat(env.jobsJson().get(0)).containsEntry("state", "live").containsEntry("jid", first);
        assertThat(env.jobsJson().get(1)).containsEntry("state", "queued").containsEntry("ahead", 0);

        String queued = firstLine(out, "job-queued");
        assertThat(Jsonl.longValue(queued, "waitedMs", -1)).isZero();
        JobQueuedFrame frame = JobQueuedFrame.decode(queued);
        assertThat(frame.live()).containsExactly(new JobQueuedFrame.Live(first, "test", "/tmp/job-env-suite", 1_000L));
        assertThat(host.logs)
                .anyMatch(l -> l.contains("waits for engine memory behind 0 jobs; live: test /tmp/job-env-suite (jid "
                        + first + ") since"));

        firstMayFinish.countDown();
        second.join(Duration.ofSeconds(10));
        assertThat(env.jobs()).isEmpty();
    }

    @Test
    void a_queued_job_that_waits_the_bound_ends_on_an_error_naming_the_live_job_not_a_closed_connection()
            throws Exception {
        FakeEnvelopeHost host = new FakeEnvelopeHost();
        MemoryAdmissionTest.FakeClock clock = new MemoryAdmissionTest.FakeClock();
        MemoryAdmission.Timing timing = new MemoryAdmission.Timing(Long.MAX_VALUE / 4, 120_000L, Long.MAX_VALUE / 4);
        JobEnvelope env = new JobEnvelope(host, JobLimits.DEFAULTS, gateFor(200, 40, 100, timing, clock));
        CountDownLatch firstMayFinish = new CountDownLatch(1);
        long first = env.submit(
                "{\"type\":\"test-request\",\"dir\":\"/tmp/job-env-suite\"}",
                JobRequest.plan("test", "jk-test-", (line, tok, w) -> {
                    awaitQuietly(firstMayFinish);
                    return JobOutcome.declined();
                }),
                new JobTransport.FireAndForget());
        AtomicBoolean secondRan = new AtomicBoolean();
        StringWriter out = new StringWriter();
        AtomicLong secondResult = new AtomicLong(Long.MIN_VALUE);
        Thread second = Thread.ofVirtual()
                .start(() -> secondResult.set(env.submit(
                        "{\"type\":\"build-request\",\"dir\":\"/tmp/job-env-lib\"}",
                        JobRequest.plan("build", "jk-test-", (line, tok, w) -> {
                            secondRan.set(true);
                            return JobOutcome.declined();
                        }),
                        new JobTransport.SocketWatch(
                                new BufferedReader(new StringReader("")), new BufferedWriter(out)))));
        Await.until(Duration.ofSeconds(5), () -> out.toString().contains("job-queued"));

        clock.now += 120_000L;
        second.join(Duration.ofSeconds(10));

        assertThat(secondResult).hasValue(-1L);
        assertThat(secondRan).isFalse();
        // The terminal is queued before submit returns and lands on the stream's writer thread.
        Await.until(Duration.ofSeconds(5), () -> out.toString().contains("\"type\":\"error\""));
        String error = firstLine(out, "error");
        assertThat(Jsonl.str(error, "code")).isEqualTo("queue-wait");
        assertThat(Jsonl.str(error, "message"))
                .startsWith(
                        "gave up after waiting 2m for engine memory behind 0 jobs; live: test /tmp/job-env-suite (jid "
                                + first + ") since ")
                .contains("`jk engine status` lists them")
                .contains("[engine] queue-wait-ms / JK_ENGINE_QUEUE_WAIT_MS");
        assertThat(host.events)
                .anyMatch(e -> e.startsWith("request-finish:")
                        && e.contains("\"success\":false")
                        && e.contains("\"cancelled\":false")
                        && e.contains("gave up after waiting"));
        assertThat(env.queued()).isZero();
        assertThat(env.jobs()).as("the live job is untouched").hasSize(1);
        firstMayFinish.countDown();
    }
}
