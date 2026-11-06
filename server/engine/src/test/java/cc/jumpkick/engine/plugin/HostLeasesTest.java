// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Every engine of one state directory leases from one budget, and a dead engine's share comes back. */
class HostLeasesTest {

    private static final long MIB = 1L << 20;
    private static final long GIB = 1L << 30;
    private static final long BUDGET = 4 * GIB;

    /** Plenty free and no pressure: overbooking is limited by the cap alone. */
    private static final OverbookSignals.Source ROOMY = () -> new OverbookSignals.Reading(true, 1024 * GIB, 0, 0);

    @TempDir
    Path engineDir;

    @Test
    void a_draining_engine_and_its_successor_never_lease_past_twice_the_budget_together() throws Exception {
        WorkerLeases.Ledger draining = engine(1001, ROOMY);
        WorkerLeases.Ledger successor = engine(1002, ROOMY);
        AtomicLong held = new AtomicLong();
        AtomicLong peak = new AtomicLong();
        AtomicReference<@Nullable Throwable> failed = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 12; t++) {
            WorkerLeases.Ledger ledger = t % 2 == 0 ? draining : successor;
            long requestId = t;
            threads.add(Thread.ofVirtual().start(() -> {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                try {
                    for (int i = 0; i < 25; i++) {
                        long bytes = (256 + random.nextLong(1280)) * MIB;
                        try (WorkerLeases.Grant grant = ledger.acquireBytes(bytes, false, requestId)) {
                            peak.accumulateAndGet(held.addAndGet(grant.bytes()), Math::max);
                            Thread.sleep(random.nextInt(3));
                            held.addAndGet(-grant.bytes());
                        }
                    }
                } catch (Throwable e) {
                    failed.compareAndSet(null, e);
                }
            }));
        }
        for (Thread thread : threads) thread.join(TimeUnit.SECONDS.toMillis(60));
        assertThat(failed.get()).isNull();
        assertThat(peak.get()).isGreaterThan(BUDGET).isLessThanOrEqualTo(2 * BUDGET);
        assertThat(draining.snapshot().leasedBytes()).isZero();
        assertThat(successor.snapshot().leasedBytes()).isZero();
    }

    @Test
    void a_successor_waits_for_what_the_draining_engine_holds_and_starts_when_it_lets_go() throws Exception {
        WorkerLeases.Ledger draining = engine(1001, OverbookSignals.off());
        WorkerLeases.Ledger successor = engine(1002, OverbookSignals.off());
        WorkerLeases.Grant drainingJob = draining.acquireBytes(3 * GIB, true, 3L);
        assertThat(successor.snapshot().leasedBytes()).isEqualTo(3 * GIB);

        CompletableFuture<WorkerLeases.Grant> waiting = acquire(successor, 2 * GIB, 7L);
        awaitQueued(successor);
        assertThat(successor.snapshot().waiting())
                .isEqualTo("job #7 needs 2.0 GiB (1.0 GiB free); held by other engines 3.0 GiB");

        drainingJob.close();
        try (WorkerLeases.Grant grant = waiting.get(5, TimeUnit.SECONDS)) {
            assertThat(grant.bytes()).isEqualTo(2 * GIB);
            awaitLeased(draining, 2 * GIB);
        }
        assertThat(Files.readString(engineDir.resolve(HostLeases.FILE))).isEmpty();
    }

    @Test
    void the_cap_counts_the_other_engine_too() throws Exception {
        WorkerLeases.Ledger draining = engine(1001, ROOMY).waitAtMost(Duration.ofMillis(300));
        WorkerLeases.Ledger successor = engine(1002, ROOMY).waitAtMost(Duration.ofMillis(300));
        try (WorkerLeases.Grant a = draining.acquireBytes(4 * GIB, false, 3L);
                WorkerLeases.Grant b = successor.acquireBytes(3 * GIB, false, 7L)) {
            assertThat(b.overbooked()).isTrue();
            Throwable cause = failure(acquire(successor, 2 * GIB, 8L));
            assertThat(cause).isInstanceOf(WorkerLeases.LeaseRefused.class);
            assertThat(cause.getMessage()).contains("held by other engines 4.0 GiB");
        }
    }

    @Test
    void a_crashed_engines_leases_are_reclaimed_by_the_next_reader() throws Exception {
        long gonePid = 2_000_000_000L;
        assertThat(new HostLeases.Owner(gonePid, 0).alive()).isFalse();
        Files.writeString(engineDir.resolve(HostLeases.FILE), gonePid + " 0 " + BUDGET + "\n");

        WorkerLeases.Ledger next = new WorkerLeases.Ledger(() -> BUDGET, () -> 8, id -> false, OverbookSignals.off())
                .waitAtMost(Duration.ofSeconds(2))
                .shareHost(HostLeases.forThisProcess(engineDir));
        try (WorkerLeases.Grant grant = next.acquireBytes(3 * GIB, true, 7L)) {
            assertThat(grant.bytes()).isEqualTo(3 * GIB);
            String file = Files.readString(engineDir.resolve(HostLeases.FILE));
            assertThat(file).doesNotContain(Long.toString(gonePid)).contains(" " + 3 * GIB);
        }
    }

    @Test
    void a_reused_pid_does_not_keep_the_dead_engines_leases() throws Exception {
        long parent = ProcessHandle.current().parent().orElseThrow().pid();
        Files.writeString(engineDir.resolve(HostLeases.FILE), parent + " 1 " + BUDGET + "\n");

        WorkerLeases.Ledger next = new WorkerLeases.Ledger(() -> BUDGET, () -> 8, id -> false, OverbookSignals.off())
                .waitAtMost(Duration.ofSeconds(2))
                .shareHost(HostLeases.forThisProcess(engineDir));
        try (WorkerLeases.Grant grant = next.acquireBytes(BUDGET, true, 7L)) {
            assertThat(grant.bytes()).isEqualTo(BUDGET);
        }
    }

    @Test
    void a_line_that_does_not_parse_is_dropped() throws Exception {
        Files.writeString(engineDir.resolve(HostLeases.FILE), "garbage\n1001 0 not-bytes\n");
        WorkerLeases.Ledger ledger = engine(1002, OverbookSignals.off());
        try (WorkerLeases.Grant grant = ledger.acquireBytes(BUDGET, true, 7L)) {
            assertThat(Files.readString(engineDir.resolve(HostLeases.FILE))).isEqualTo("1002 0 " + BUDGET + "\n");
        }
    }

    /** One engine of {@link #engineDir} with a made-up pid; every engine of the test counts as alive. */
    private WorkerLeases.Ledger engine(long pid, OverbookSignals.Source signals) {
        return new WorkerLeases.Ledger(() -> BUDGET, () -> 8, id -> false, signals)
                .shareHost(new HostLeases(engineDir, new HostLeases.Owner(pid, 0), owner -> true));
    }

    private static CompletableFuture<WorkerLeases.Grant> acquire(WorkerLeases.Ledger ledger, long bytes, long id) {
        CompletableFuture<WorkerLeases.Grant> out = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                out.complete(ledger.acquireBytes(bytes, true, id));
            } catch (InterruptedException | RuntimeException e) {
                out.completeExceptionally(e);
            }
        });
        return out;
    }

    private static Throwable failure(CompletableFuture<WorkerLeases.Grant> future) throws Exception {
        try {
            WorkerLeases.Grant grant = future.get(5, TimeUnit.SECONDS);
            grant.close();
            throw new AssertionError("granted " + grant.bytes() + " bytes");
        } catch (ExecutionException e) {
            return Objects.requireNonNull(e.getCause());
        }
    }

    /** The other engine's total is re-read at most every 250 ms. */
    private static void awaitLeased(WorkerLeases.Ledger ledger, long bytes) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (ledger.snapshot().leasedBytes() != bytes) {
            if (System.nanoTime() > deadline)
                throw new AssertionError("leased " + ledger.snapshot().leasedBytes());
            Thread.sleep(20);
        }
    }

    private static void awaitQueued(WorkerLeases.Ledger ledger) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (ledger.queued() < 1) {
            if (System.nanoTime() > deadline) throw new AssertionError("nothing queued");
            Thread.sleep(5);
        }
    }
}
