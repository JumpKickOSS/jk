// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import cc.jumpkick.engine.jobs.MemoryAdmission.Verdict;
import cc.jumpkick.testing.Await;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
            (ahead, waited) -> fail("this job was expected to be admitted at once");

    /** A gate on a frozen clock with a generous host: only the heap arithmetic decides. */
    private static MemoryAdmission gate(FakeHeap heap, MemoryAdmission.Estimator estimator) {
        return new MemoryAdmission(heap, estimator, () -> 64L << 30, JobEnvelopeQueueTest.PATIENT, () -> 1_000L);
    }

    @Test
    void two_jobs_fit_the_third_queues_and_is_admitted_when_one_finishes() throws Exception {
        // 1000 MiB heap, 100 idle, 32 reserved: 868 to hand out. Two 350 MiB jobs fit; a third does not.
        FakeHeap heap = new FakeHeap(1000, 100);
        MemoryAdmission gate = gate(heap, (kind, dir) -> 350 * MIB);
        assertThat(gate.admit(1, "build", "/a", NEVER_QUEUED, () -> false)).isEqualTo(Verdict.ADMITTED);
        assertThat(gate.admit(2, "build", "/b", NEVER_QUEUED, () -> false)).isEqualTo(Verdict.ADMITTED);
        AtomicInteger aheadRef = new AtomicInteger(-1);
        CompletableFuture<Verdict> third =
                async(() -> gate.admit(3, "build", "/c", (ahead, waited) -> aheadRef.set(ahead), () -> false));
        Await.until(Duration.ofSeconds(5), () -> gate.queued() == 1);
        assertThat(aheadRef).hasValue(0);
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
        MemoryAdmission gate = gate(heap, (kind, dir) -> 100 * MIB);
        assertThat(gate.admit(1, "build", "/a", NEVER_QUEUED, () -> false)).isEqualTo(Verdict.ADMITTED);
        // The running job holds far more than it estimated: 900 committed leaves 68, not the 768 the ledger says.
        heap.committed = 900 * MIB;
        CompletableFuture<Verdict> second =
                async(() -> gate.admit(2, "build", "/b", (ahead, waited) -> {}, () -> false));
        Await.until(Duration.ofSeconds(5), () -> gate.queued() == 1);
        assertThat(second).isNotDone();
        // A collection (or the job's own release of memory) brings committed back; the poll notices.
        heap.committed = 300 * MIB;
        assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(Verdict.ADMITTED);
    }

    /** A job the whole heap could not hold is refused before it starts; one that fits alone is admitted at once. */
    @Test
    void a_job_larger_than_the_whole_heap_is_refused_at_once() {
        FakeHeap heap = new FakeHeap(256, 40);
        MemoryAdmission gate = gate(heap, (kind, dir) -> 5_000 * MIB);
        assertThat(gate.admit(1, "lock", "/huge", NEVER_QUEUED, () -> false)).isEqualTo(Verdict.TOO_LARGE);
        assertThat(gate.admittedCount()).isZero();
        assertThat(gate.estimateFor("lock", "/huge")).isEqualTo(5_000 * MIB);
        assertThat(gate.heapMaxBytes()).isEqualTo(256 * MIB);

        assertThat(gate(heap, (kind, dir) -> 200 * MIB).admit(2, "build", "/big", NEVER_QUEUED, () -> false))
                .as("an empty engine admits a job the heap holds alone")
                .isEqualTo(Verdict.ADMITTED);
    }

    /** A first lock, with no lock on disk, is sized by the distinct dependencies the manifests declare. */
    @Test
    void a_first_lock_is_sized_by_the_dependencies_the_manifests_declare(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "com.acme"
                name = "app"
                version = "0.1.0"

                [dependencies]
                guava = { group = "com.google.guava", name = "guava", version = "33.0.0-jre" }
                jackson = { group = "com.fasterxml.jackson.core", name = "jackson-databind", version = "2.17.0" }

                [test-dependencies]
                guava-again = { group = "com.google.guava", name = "guava", version = "33.0.0-jre" }
                assertj = { group = "org.assertj", name = "assertj-core", version = "3.26.0" }
                """);

        long lock = MemoryAdmission.estimate("lock", tmp.toString());
        long build = MemoryAdmission.estimate("build", tmp.toString());
        assertThat(lock - build).isEqualTo(3 * MemoryAdmission.LOCK_BYTES_PER_DECLARED_DEPENDENCY);
    }

    @Test
    void a_queued_job_is_cancelled_by_jid_or_by_dir() throws Exception {
        FakeHeap heap = new FakeHeap(1000, 100);
        MemoryAdmission gate = gate(heap, (kind, dir) -> 600 * MIB);
        assertThat(gate.admit(1, "build", "/a", NEVER_QUEUED, () -> false)).isEqualTo(Verdict.ADMITTED);
        CompletableFuture<Verdict> byJid =
                async(() -> gate.admit(2, "build", "/b", (ahead, waited) -> {}, () -> false));
        CompletableFuture<Verdict> byDir =
                async(() -> gate.admit(3, "build", "/c", (ahead, waited) -> {}, () -> false));
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
        MemoryAdmission gate = gate(heap, (kind, dir) -> 600 * MIB);
        assertThat(gate.admit(1, "build", "/a", NEVER_QUEUED, () -> false)).isEqualTo(Verdict.ADMITTED);
        AtomicBoolean draining = new AtomicBoolean();
        CompletableFuture<Verdict> second =
                async(() -> gate.admit(2, "build", "/b", (ahead, waited) -> {}, draining::get));
        Await.until(Duration.ofSeconds(5), () -> gate.queued() == 1);
        draining.set(true);
        assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(Verdict.DRAINING);
    }

    @Test
    void the_queue_is_first_come_first_served_even_when_a_later_small_job_would_fit() throws Exception {
        // 400 MiB jobs: two fit (100 + 800 = 900 of 968). A third queues; a 10 MiB job behind it would
        // fit the 68 MiB left, and still waits its turn.
        FakeHeap heap = new FakeHeap(1000, 100);
        MemoryAdmission gate = gate(heap, (kind, dir) -> dir.equals("/small") ? 10 * MIB : 400 * MIB);
        assertThat(gate.admit(1, "build", "/a", NEVER_QUEUED, () -> false)).isEqualTo(Verdict.ADMITTED);
        assertThat(gate.admit(2, "build", "/b", NEVER_QUEUED, () -> false)).isEqualTo(Verdict.ADMITTED);
        List<Long> admittedOrder = new CopyOnWriteArrayList<>();
        CompletableFuture<Verdict> third = async(() -> {
            Verdict v = gate.admit(3, "build", "/c", (ahead, waited) -> {}, () -> false);
            admittedOrder.add(3L);
            return v;
        });
        Await.until(Duration.ofSeconds(5), () -> gate.queued() == 1);
        AtomicInteger smallAhead = new AtomicInteger(-1);
        CompletableFuture<Verdict> small = async(() -> {
            Verdict v = gate.admit(4, "build", "/small", (ahead, waited) -> smallAhead.set(ahead), () -> false);
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

    /** A clock the test advances by hand. */
    static final class FakeClock implements LongSupplier {
        volatile long now = 1_000L;

        @Override
        public long getAsLong() {
            return now;
        }
    }

    /** One long job holds a 256 MiB heap: 60 idle, 100 estimated, and 250 committed once it runs. */
    private static FakeHeap heldByOneLongJob() {
        return new FakeHeap(256, 60);
    }

    @Test
    void a_brief_job_runs_beside_a_long_job_whose_committed_heap_would_refuse_it() throws Exception {
        FakeHeap heap = heldByOneLongJob();
        MemoryAdmission gate = new MemoryAdmission(
                heap,
                (kind, dir) -> "test".equals(kind) ? 100 * MIB : 30 * MIB,
                () -> 8L << 30,
                JobEnvelopeQueueTest.PATIENT,
                () -> 1_000L);
        assertThat(gate.admit(1, "test", "/suite", NEVER_QUEUED, () -> false)).isEqualTo(Verdict.ADMITTED);
        // The suite's heap grew to 250 of 256: the plain arithmetic admits nothing behind it.
        heap.committed = 250 * MIB;
        CompletableFuture<Verdict> build =
                async(() -> gate.admit(2, "build", "/lib", (ahead, waited) -> {}, () -> false));
        Await.until(Duration.ofSeconds(5), () -> gate.queued() == 1);
        assertThat(build).isNotDone();

        // format and guard are judged by the ledger (60 + 100 + 30 + 30 of 224) and the host, and do
        // not wait behind the build.
        assertThat(gate.admit(3, "format", "/tool", NEVER_QUEUED, () -> false)).isEqualTo(Verdict.ADMITTED);
        assertThat(gate.admit(4, "guard", "/tool", NEVER_QUEUED, () -> false)).isEqualTo(Verdict.ADMITTED);
        assertThat(gate.admittedCount()).isEqualTo(3);
        assertThat(gate.queued()).as("the build still waits its turn").isEqualTo(1);
        assertThat(build).isNotDone();

        // The ledger still caps brief jobs: a third one (250 of 224) queues like anything else.
        AtomicInteger treeAhead = new AtomicInteger(-1);
        CompletableFuture<Verdict> tree =
                async(() -> gate.admit(5, "tree", "/tool", (ahead, waited) -> treeAhead.set(ahead), () -> false));
        Await.until(Duration.ofSeconds(5), () -> gate.queued() == 2);
        assertThat(treeAhead).hasValue(1);
        assertThat(tree).isNotDone();

        gate.release(3);
        assertThat(tree.get(5, TimeUnit.SECONDS))
                .as("brief jobs do not wait behind the queued build")
                .isEqualTo(Verdict.ADMITTED);
        assertThat(build).isNotDone();
        gate.release(1);
        gate.release(4);
        gate.release(5);
        heap.committed = 60 * MIB;
        assertThat(build.get(5, TimeUnit.SECONDS)).isEqualTo(Verdict.ADMITTED);
    }

    @Test
    void a_brief_job_waits_while_the_host_itself_is_short_and_runs_when_it_frees() throws Exception {
        FakeHeap heap = heldByOneLongJob();
        AtomicLong hostFree = new AtomicLong(100 * MIB);
        MemoryAdmission gate = new MemoryAdmission(
                heap, (kind, dir) -> 50 * MIB, hostFree::get, JobEnvelopeQueueTest.PATIENT, () -> 1_000L);
        assertThat(gate.admit(1, "test", "/suite", NEVER_QUEUED, () -> false)).isEqualTo(Verdict.ADMITTED);
        heap.committed = 250 * MIB;
        AtomicInteger aheadRef = new AtomicInteger(-1);
        CompletableFuture<Verdict> format =
                async(() -> gate.admit(2, "format", "/tool", (ahead, waited) -> aheadRef.set(ahead), () -> false));
        Await.until(Duration.ofSeconds(5), () -> gate.queued() == 1);
        assertThat(aheadRef).hasValue(0);
        assertThat(format)
                .as("the host has 100 MiB free; a 50 MiB job needs 256 MiB of headroom beyond it")
                .isNotDone();

        hostFree.set(8L << 30);
        assertThat(format.get(5, TimeUnit.SECONDS)).isEqualTo(Verdict.ADMITTED);
    }

    @Test
    void the_head_of_the_queue_is_admitted_after_the_fair_wait_when_the_host_has_room() throws Exception {
        FakeHeap heap = heldByOneLongJob();
        FakeClock clock = new FakeClock();
        MemoryAdmission.Timing timing = new MemoryAdmission.Timing(10_000L, 0L, Long.MAX_VALUE / 4);
        MemoryAdmission gate = new MemoryAdmission(heap, (kind, dir) -> 100 * MIB, () -> 8L << 30, timing, clock);
        assertThat(gate.admit(1, "test", "/suite", NEVER_QUEUED, () -> false)).isEqualTo(Verdict.ADMITTED);
        heap.committed = 250 * MIB;
        CompletableFuture<Verdict> build =
                async(() -> gate.admit(2, "build", "/lib", (ahead, waited) -> {}, () -> false));
        Await.until(Duration.ofSeconds(5), () -> gate.queued() == 1);
        clock.now += 5_000L;
        CompletableFuture<Verdict> second =
                async(() -> gate.admit(3, "build", "/app", (ahead, waited) -> {}, () -> false));
        Await.until(Duration.ofSeconds(5), () -> gate.queued() == 2);
        clock.now += 4_999L;
        Thread.sleep(2 * MemoryAdmission.POLL_MS);
        assertThat(build).as("one millisecond short of the fair wait").isNotDone();

        clock.now += 1L;
        assertThat(build.get(5, TimeUnit.SECONDS)).isEqualTo(Verdict.ADMITTED);
        Thread.sleep(2 * MemoryAdmission.POLL_MS);
        assertThat(second)
                .as("the next head waits its own fair wait, measured from its own arrival")
                .isNotDone();
        assertThat(gate.queued()).isEqualTo(1);
        clock.now += 5_000L;
        assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(Verdict.ADMITTED);
    }

    @Test
    void the_fair_wait_does_not_admit_a_job_the_host_has_no_room_for() throws Exception {
        FakeHeap heap = heldByOneLongJob();
        FakeClock clock = new FakeClock();
        MemoryAdmission.Timing timing = new MemoryAdmission.Timing(10_000L, 0L, Long.MAX_VALUE / 4);
        MemoryAdmission gate = new MemoryAdmission(heap, (kind, dir) -> 100 * MIB, () -> 200 * MIB, timing, clock);
        assertThat(gate.admit(1, "test", "/suite", NEVER_QUEUED, () -> false)).isEqualTo(Verdict.ADMITTED);
        heap.committed = 250 * MIB;
        CompletableFuture<Verdict> build =
                async(() -> gate.admit(2, "build", "/lib", (ahead, waited) -> {}, () -> false));
        Await.until(Duration.ofSeconds(5), () -> gate.queued() == 1);
        clock.now += 60_000L;
        Thread.sleep(2 * MemoryAdmission.POLL_MS);
        assertThat(build).isNotDone();
        assertThat(gate.cancel(2)).isTrue();
        assertThat(build.get(5, TimeUnit.SECONDS)).isEqualTo(Verdict.CANCELLED);
    }

    @Test
    void a_job_that_waits_the_queue_wait_gives_up_and_leaves_the_queue() throws Exception {
        FakeHeap heap = heldByOneLongJob();
        FakeClock clock = new FakeClock();
        MemoryAdmission.Timing timing = new MemoryAdmission.Timing(Long.MAX_VALUE / 4, 5_000L, Long.MAX_VALUE / 4);
        MemoryAdmission gate = new MemoryAdmission(heap, (kind, dir) -> 100 * MIB, () -> 8L << 30, timing, clock);
        assertThat(gate.admit(1, "test", "/suite", NEVER_QUEUED, () -> false)).isEqualTo(Verdict.ADMITTED);
        heap.committed = 250 * MIB;
        CompletableFuture<Verdict> build =
                async(() -> gate.admit(2, "build", "/lib", (ahead, waited) -> {}, () -> false));
        Await.until(Duration.ofSeconds(5), () -> gate.queued() == 1);
        clock.now += 5_000L;
        assertThat(build.get(5, TimeUnit.SECONDS)).isEqualTo(Verdict.TIMED_OUT);
        assertThat(gate.queued()).isZero();
        assertThat(gate.admittedCount()).as("the live job is untouched").isEqualTo(1);
    }

    @Test
    void a_waiting_job_hears_its_position_every_report_interval() throws Exception {
        FakeHeap heap = heldByOneLongJob();
        FakeClock clock = new FakeClock();
        MemoryAdmission.Timing timing = new MemoryAdmission.Timing(Long.MAX_VALUE / 4, 0L, 1_000L);
        MemoryAdmission gate = new MemoryAdmission(heap, (kind, dir) -> 100 * MIB, () -> 8L << 30, timing, clock);
        assertThat(gate.admit(1, "test", "/suite", NEVER_QUEUED, () -> false)).isEqualTo(Verdict.ADMITTED);
        heap.committed = 250 * MIB;
        List<long[]> reports = new CopyOnWriteArrayList<>();
        CompletableFuture<Verdict> build = async(() -> gate.admit(
                2, "build", "/lib", (ahead, waited) -> reports.add(new long[] {ahead, waited}), () -> false));
        Await.until(Duration.ofSeconds(5), () -> reports.size() == 1);
        assertThat(reports.get(0)).containsExactly(0L, 0L);
        clock.now += 1_000L;
        Await.until(Duration.ofSeconds(5), () -> reports.size() == 2);
        assertThat(reports.get(1)).containsExactly(0L, 1_000L);
        clock.now += 1_000L;
        Await.until(Duration.ofSeconds(5), () -> reports.size() == 3);
        assertThat(reports.get(2)).containsExactly(0L, 2_000L);
        assertThat(gate.cancel(2)).isTrue();
        assertThat(build.get(5, TimeUnit.SECONDS)).isEqualTo(Verdict.CANCELLED);
    }

    @Test
    void queued_rows_carry_kind_dir_arrival_and_position() throws Exception {
        FakeHeap heap = heldByOneLongJob();
        FakeClock clock = new FakeClock();
        MemoryAdmission gate = new MemoryAdmission(
                heap, (kind, dir) -> 100 * MIB, () -> 8L << 30, JobEnvelopeQueueTest.PATIENT, clock);
        assertThat(gate.admit(1, "test", "/suite", NEVER_QUEUED, () -> false)).isEqualTo(Verdict.ADMITTED);
        heap.committed = 250 * MIB;
        CompletableFuture<Verdict> first =
                async(() -> gate.admit(2, "build", "/lib", (ahead, waited) -> {}, () -> false));
        Await.until(Duration.ofSeconds(5), () -> gate.queued() == 1);
        clock.now = 4_000L;
        CompletableFuture<Verdict> second =
                async(() -> gate.admit(3, "compile", "/app", (ahead, waited) -> {}, () -> false));
        Await.until(Duration.ofSeconds(5), () -> gate.queued() == 2);

        List<JobRow> rows = gate.queuedRows();
        assertThat(rows)
                .containsExactly(
                        JobRow.queued(2, "build", "/lib", 1_000L, 0), JobRow.queued(3, "compile", "/app", 4_000L, 1));
        assertThat(rows.get(1).toJson())
                .containsEntry("state", "queued")
                .containsEntry("ahead", 1)
                .containsEntry("since", 4_000L)
                .containsEntry("workers", -1);
        gate.cancel(2);
        gate.cancel(3);
        first.get(5, TimeUnit.SECONDS);
        second.get(5, TimeUnit.SECONDS);
    }

    @Test
    void an_import_is_estimated_from_the_pom_files_under_the_project(@TempDir Path dir) throws Exception {
        for (String pom : List.of("pom.xml", "core/pom.xml", "libs/util/pom.xml")) {
            Path file = dir.resolve(pom);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "<project/>");
        }
        // A build output tree carries copies of the reactor's POMs; they are not modules to import.
        for (String stale : List.of("core/target/classes/META-INF/maven/pom.xml", "build/pom.xml", ".git/pom.xml")) {
            Path file = dir.resolve(stale);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "<project/>");
        }

        assertThat(MemoryAdmission.estimate("import", dir.toString()))
                .as("one share per pom.xml on top of the base, the output trees pruned")
                .isEqualTo(MemoryAdmission.BASE_JOB_BYTES + 3 * MemoryAdmission.IMPORT_BYTES_PER_POM);
        assertThat(MemoryAdmission.estimate("build", dir.toString()))
                .as("a build reads the lock and the ledgers, not the POMs")
                .isEqualTo(MemoryAdmission.estimate(
                        "build", Files.createDirectories(dir.resolve("no-poms")).toString()));
    }
}
