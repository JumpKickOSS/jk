// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JobLimits;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.testing.Await;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.Duration;
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
        return new MemoryAdmission(heap, dir -> perJobMib << 20);
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
        assertThat(host.events).anyMatch(e -> e.startsWith("request-queued:"));
        assertThat(secondRan).isFalse();
        assertThat(host.activePlans).as("a queued job holds no plan slot").isEqualTo(1);

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
                .isZero();
        firstMayFinish.countDown();
    }
}
