// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import cc.jumpkick.engine.jobs.MemoryAdmission.Verdict;
import cc.jumpkick.testing.Await;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** The admission arithmetic on a synthetic heap and a fixed per-job cost. */
class MemoryAdmissionTest {

    private static final long MIB = 1L << 20;

    /** A heap whose committed size is whatever the test says it is. */
    static final class FakeHeap implements MemoryAdmission.Heap {
        final long max;
        volatile long committed;
        final AtomicInteger collections = new AtomicInteger();

        FakeHeap(long maxMib, long committedMib) {
            this.max = maxMib * MIB;
            this.committed = committedMib * MIB;
        }

        @Override
        public long maxBytes() {
            return max;
        }

        @Override
        public long committedBytes() {
            return committed;
        }

        @Override
        public void collect() {
            collections.incrementAndGet();
        }
    }

    /** Waiters park for real; a virtual thread each, never the common pool a small test JVM starves. */
    private static CompletableFuture<Verdict> async(Supplier<Verdict> wait) {
        return CompletableFuture.supplyAsync(wait, Executors.newVirtualThreadPerTaskExecutor());
    }

    private static final MemoryAdmission.QueuedListener NEVER_QUEUED =
            ahead -> fail("this job was expected to be admitted at once");

    @Test
    void two_jobs_fit_the_third_queues_and_is_admitted_when_one_finishes() throws Exception {
        // 1000 MiB heap, 100 idle, 32 reserved: 868 to hand out. Two 350 MiB jobs fit; a third does not.
        FakeHeap heap = new FakeHeap(1000, 100);
        MemoryAdmission gate = new MemoryAdmission(heap, dir -> 350 * MIB);
        assertThat(gate.admit(1, "/a", NEVER_QUEUED, () -> false)).isEqualTo(Verdict.ADMITTED);
        assertThat(gate.admit(2, "/b", NEVER_QUEUED, () -> false)).isEqualTo(Verdict.ADMITTED);
        AtomicInteger ahead = new AtomicInteger(-1);
        CompletableFuture<Verdict> third = async(() -> gate.admit(3, "/c", ahead::set, () -> false));
        Await.until(Duration.ofSeconds(5), () -> gate.queued() == 1);
        assertThat(ahead).hasValue(0);
        assertThat(third).isNotDone();
        assertThat(gate.admittedCount()).isEqualTo(2);

        gate.release(1);

        assertThat(third.get(5, TimeUnit.SECONDS)).isEqualTo(Verdict.ADMITTED);
        assertThat(heap.collections)
                .as("waiters are re-judged after a collection")
                .hasValue(1);
        assertThat(gate.queued()).isZero();
        assertThat(gate.admittedCount()).isEqualTo(2);
    }

    @Test
    void committed_heap_beyond_the_estimates_holds_the_door_until_it_shrinks() throws Exception {
        FakeHeap heap = new FakeHeap(1000, 100);
        MemoryAdmission gate = new MemoryAdmission(heap, dir -> 100 * MIB);
        assertThat(gate.admit(1, "/a", NEVER_QUEUED, () -> false)).isEqualTo(Verdict.ADMITTED);
        // The running job holds far more than it estimated: 900 committed leaves 68, not the 768 the ledger says.
        heap.committed = 900 * MIB;
        CompletableFuture<Verdict> second = async(() -> gate.admit(2, "/b", ahead -> {}, () -> false));
        Await.until(Duration.ofSeconds(5), () -> gate.queued() == 1);
        assertThat(second).isNotDone();
        // A collection (or the job's own release of memory) brings committed back; the poll notices.
        heap.committed = 300 * MIB;
        assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(Verdict.ADMITTED);
    }

    @Test
    void an_empty_engine_admits_a_job_that_would_never_fit() {
        FakeHeap heap = new FakeHeap(256, 40);
        MemoryAdmission gate = new MemoryAdmission(heap, dir -> 5_000 * MIB);
        assertThat(gate.admit(1, "/huge", NEVER_QUEUED, () -> false)).isEqualTo(Verdict.ADMITTED);
    }

    @Test
    void a_queued_job_is_cancelled_by_jid_or_by_dir() throws Exception {
        FakeHeap heap = new FakeHeap(1000, 100);
        MemoryAdmission gate = new MemoryAdmission(heap, dir -> 600 * MIB);
        assertThat(gate.admit(1, "/a", NEVER_QUEUED, () -> false)).isEqualTo(Verdict.ADMITTED);
        CompletableFuture<Verdict> byJid = async(() -> gate.admit(2, "/b", ahead -> {}, () -> false));
        CompletableFuture<Verdict> byDir = async(() -> gate.admit(3, "/c", ahead -> {}, () -> false));
        Await.until(Duration.ofSeconds(5), () -> gate.queued() == 2);
        assertThat(gate.cancel(99)).as("an unknown jid is not waiting").isFalse();
        assertThat(gate.cancel(2)).isTrue();
        assertThat(byJid.get(5, TimeUnit.SECONDS)).isEqualTo(Verdict.CANCELLED);
        assertThat(gate.cancelForDir("/c")).isEqualTo(1);
        assertThat(byDir.get(5, TimeUnit.SECONDS)).isEqualTo(Verdict.CANCELLED);
        assertThat(gate.queued()).isZero();
        assertThat(gate.admittedCount()).as("the running job is untouched").isEqualTo(1);
    }

    @Test
    void a_drain_ends_the_wait() throws Exception {
        FakeHeap heap = new FakeHeap(1000, 100);
        MemoryAdmission gate = new MemoryAdmission(heap, dir -> 600 * MIB);
        assertThat(gate.admit(1, "/a", NEVER_QUEUED, () -> false)).isEqualTo(Verdict.ADMITTED);
        AtomicBoolean draining = new AtomicBoolean();
        CompletableFuture<Verdict> second = async(() -> gate.admit(2, "/b", ahead -> {}, draining::get));
        Await.until(Duration.ofSeconds(5), () -> gate.queued() == 1);
        draining.set(true);
        assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(Verdict.DRAINING);
    }

    @Test
    void the_queue_is_first_come_first_served_even_when_a_later_small_job_would_fit() throws Exception {
        // 400 MiB jobs: two fit (100 + 800 = 900 of 968). A third queues; a 10 MiB job behind it would
        // fit the 68 MiB left, and still waits its turn.
        FakeHeap heap = new FakeHeap(1000, 100);
        MemoryAdmission gate = new MemoryAdmission(heap, dir -> dir.equals("/small") ? 10 * MIB : 400 * MIB);
        assertThat(gate.admit(1, "/a", NEVER_QUEUED, () -> false)).isEqualTo(Verdict.ADMITTED);
        assertThat(gate.admit(2, "/b", NEVER_QUEUED, () -> false)).isEqualTo(Verdict.ADMITTED);
        List<Long> admittedOrder = new CopyOnWriteArrayList<>();
        CompletableFuture<Verdict> third = async(() -> {
            Verdict v = gate.admit(3, "/c", ahead -> {}, () -> false);
            admittedOrder.add(3L);
            return v;
        });
        Await.until(Duration.ofSeconds(5), () -> gate.queued() == 1);
        AtomicInteger smallAhead = new AtomicInteger(-1);
        CompletableFuture<Verdict> small = async(() -> {
            Verdict v = gate.admit(4, "/small", smallAhead::set, () -> false);
            admittedOrder.add(4L);
            return v;
        });
        Await.until(Duration.ofSeconds(5), () -> gate.queued() == 2);
        assertThat(smallAhead).hasValue(1);
        assertThat(small).isNotDone();

        gate.release(1);

        assertThat(third.get(5, TimeUnit.SECONDS)).isEqualTo(Verdict.ADMITTED);
        assertThat(small.get(5, TimeUnit.SECONDS)).isEqualTo(Verdict.ADMITTED);
        assertThat(admittedOrder).containsExactly(3L, 4L);
    }
}
