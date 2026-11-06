// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every engine of one state directory leases from one budget and one set of JVM slots, and a dead
 * engine's share comes back, its leftover workers stopped.
 */
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
        assertThat(Files.readString(engineDir.resolve(HostLeases.FILE)))
                .isEqualTo("1001 0 " + BUDGET + " 8 0 0 0\n1002 0 " + BUDGET + " 8 0 0 0\n");
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
        Files.writeString(engineDir.resolve(HostLeases.FILE), gonePid + " 0 " + BUDGET + " 8 " + BUDGET + " 1 0\n");

        WorkerLeases.Ledger next = new WorkerLeases.Ledger(() -> BUDGET, () -> 8, id -> false, OverbookSignals.off())
                .waitAtMost(Duration.ofSeconds(2))
                .shareHost(HostLeases.forThisProcess(engineDir));
        try (WorkerLeases.Grant grant = next.acquireBytes(3 * GIB, true, 7L)) {
            assertThat(grant.bytes()).isEqualTo(3 * GIB);
            String file = Files.readString(engineDir.resolve(HostLeases.FILE));
            assertThat(file).doesNotContain(Long.toString(gonePid)).contains(" " + 3 * GIB + " 1");
        }
    }

    @Test
    void a_reused_pid_does_not_keep_the_dead_engines_leases() throws Exception {
        long parent = ProcessHandle.current().parent().orElseThrow().pid();
        Files.writeString(engineDir.resolve(HostLeases.FILE), parent + " 1 " + BUDGET + " 8 " + BUDGET + " 1 0\n");

        WorkerLeases.Ledger next = new WorkerLeases.Ledger(() -> BUDGET, () -> 8, id -> false, OverbookSignals.off())
                .waitAtMost(Duration.ofSeconds(2))
                .shareHost(HostLeases.forThisProcess(engineDir));
        try (WorkerLeases.Grant grant = next.acquireBytes(BUDGET, true, 7L)) {
            assertThat(grant.bytes()).isEqualTo(BUDGET);
        }
    }

    @Test
    void a_line_that_does_not_parse_is_dropped() throws Exception {
        Files.writeString(
                engineDir.resolve(HostLeases.FILE), "garbage\n1001 0 1 8 not-bytes 0 0\n1003 0 1 8 1 0 0 7\n");
        WorkerLeases.Ledger ledger = engine(1002, OverbookSignals.off());
        try (WorkerLeases.Grant grant = ledger.acquireBytes(BUDGET, true, 7L)) {
            assertThat(Files.readString(engineDir.resolve(HostLeases.FILE)))
                    .isEqualTo("1002 0 " + BUDGET + " 8 " + BUDGET + " 1 0\n");
        }
    }

    @Test
    void two_engines_together_never_run_more_jvms_than_the_host_cap() throws Exception {
        WorkerLeases.Ledger draining = engine(1001, BUDGET, 4, owner -> true);
        WorkerLeases.Ledger successor = engine(1002, BUDGET, 4, owner -> true);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        AtomicReference<@Nullable Throwable> failed = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 10; t++) {
            WorkerLeases.Ledger ledger = t % 2 == 0 ? draining : successor;
            long requestId = t;
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    for (int i = 0; i < 15; i++) {
                        try (WorkerLeases.Grant grant = ledger.acquireBytes(64 * MIB, true, requestId)) {
                            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
                            Thread.sleep(2);
                            running.decrementAndGet();
                        }
                    }
                } catch (Throwable e) {
                    failed.compareAndSet(null, e);
                }
            }));
        }
        for (Thread thread : threads) thread.join(TimeUnit.SECONDS.toMillis(60));
        assertThat(failed.get()).isNull();
        assertThat(peak.get()).isBetween(2, 4);
    }

    @Test
    void a_jvm_slot_the_other_engine_holds_is_waited_for() throws Exception {
        WorkerLeases.Ledger draining = engine(1001, BUDGET, 2, owner -> true);
        WorkerLeases.Ledger successor = engine(1002, BUDGET, 2, owner -> true);
        WorkerLeases.Grant first = draining.acquireBytes(64 * MIB, true, 3L);
        WorkerLeases.Grant second = draining.acquireBytes(64 * MIB, true, 3L);
        assertThat(successor.snapshot().runningJvms()).isEqualTo(2);
        assertThat(successor.otherEnginesJvms()).isEqualTo(2);

        CompletableFuture<WorkerLeases.Grant> waiting = acquire(successor, 64 * MIB, 7L);
        awaitQueued(successor);
        assertThat(successor.snapshot().waiting()).startsWith("job #7 needs a JVM slot (2/2 running)");
        first.close();
        try (WorkerLeases.Grant grant = waiting.get(5, TimeUnit.SECONDS)) {
            assertThat(grant.jvm()).isTrue();
        }
        second.close();
    }

    @Test
    void the_budget_the_first_engine_recorded_is_the_one_every_engine_leases_against() throws Exception {
        Set<Long> live = ConcurrentHashMap.newKeySet();
        live.add(1001L);
        live.add(1002L);
        WorkerLeases.Ledger first = engine(1001, BUDGET, 8, owner -> live.contains(owner.pid()));
        WorkerLeases.Ledger later = engine(1002, 2 * BUDGET, 8, owner -> live.contains(owner.pid()));
        assertThat(later.capacityBytes()).isEqualTo(BUDGET);
        assertThat(later.snapshot().budgetBytes()).isEqualTo(BUDGET);
        assertThat(first.snapshot().budgetBytes()).isEqualTo(BUDGET);

        live.remove(1001L);
        assertThat(later.snapshot().budgetBytes()).isEqualTo(2 * BUDGET);
        assertThat(Files.readString(engineDir.resolve(HostLeases.FILE)))
                .isEqualTo("1002 0 " + 2 * BUDGET + " 8 0 0 0\n");
    }

    @Test
    void the_jvm_cap_the_first_engine_recorded_is_the_one_every_engine_leases_against() throws Exception {
        Set<Long> live = ConcurrentHashMap.newKeySet();
        live.add(1001L);
        live.add(1002L);
        WorkerLeases.Ledger first = engine(1001, BUDGET, 2, owner -> live.contains(owner.pid()));
        WorkerLeases.Ledger later = engine(1002, BUDGET, 8, owner -> live.contains(owner.pid()));
        assertThat(later.cpuCap()).isEqualTo(2);
        assertThat(later.snapshot().cpuCap()).isEqualTo(2);
        assertThat(first.snapshot().cpuCap()).isEqualTo(2);

        try (WorkerLeases.Grant a = first.acquireBytes(64 * MIB, true, 3L);
                WorkerLeases.Grant b = later.acquireBytes(64 * MIB, true, 7L)) {
            CompletableFuture<WorkerLeases.Grant> third = acquire(later, 64 * MIB, 8L);
            awaitQueued(later);
            assertThat(later.snapshot().waiting()).startsWith("job #8 needs a JVM slot (2/2 running)");
            a.close();
            third.get(5, TimeUnit.SECONDS).close();
        }

        live.remove(1001L);
        assertThat(later.snapshot().cpuCap()).isEqualTo(8);
        assertThat(Files.readString(engineDir.resolve(HostLeases.FILE))).isEqualTo("1002 0 " + BUDGET + " 8 0 0 0\n");
    }

    @Test
    void a_lease_waiting_in_one_engine_is_counted_by_the_others() throws Exception {
        WorkerLeases.Ledger draining = engine(1001, BUDGET, 1, owner -> true);
        WorkerLeases.Ledger successor = engine(1002, BUDGET, 1, owner -> true);
        WorkerLeases.Grant held = draining.acquireBytes(64 * MIB, true, 3L);
        assertThat(draining.otherEnginesWaiting()).isZero();

        CompletableFuture<WorkerLeases.Grant> waiting = acquire(successor, 64 * MIB, 7L);
        awaitQueued(successor);
        awaitOthersWaiting(draining, 1);
        held.close();
        waiting.get(5, TimeUnit.SECONDS).close();
        awaitOthersWaiting(draining, 0);
    }

    @Test
    void a_watched_worker_is_named_on_its_engines_line() throws Exception {
        Process worker = sleeper();
        try {
            WorkerLeases.Ledger ledger = engine(1001, BUDGET, 8, owner -> true);
            long started = HostLeases.Owner.of(worker.toHandle()).startedAtMillis();
            try (WorkerLeases.Grant grant = ledger.acquireBytes(GIB, true, 3L)) {
                grant.watch(worker.pid(), "test JVM", null);
                assertThat(Files.readString(engineDir.resolve(HostLeases.FILE)))
                        .isEqualTo("1001 0 " + BUDGET + " 8 " + GIB + " 1 0 " + worker.pid() + ":" + started + "\n");
            }
            assertThat(Files.readString(engineDir.resolve(HostLeases.FILE)))
                    .isEqualTo("1001 0 " + BUDGET + " 8 0 0 0\n");
        } finally {
            worker.destroyForcibly();
        }
    }

    @Test
    void a_dead_engines_surviving_workers_are_stopped_when_its_line_is_reclaimed() throws Exception {
        Process orphan = sleeper();
        Process stranger = sleeper();
        try {
            long gonePid = 2_000_000_000L;
            long started = HostLeases.Owner.of(orphan.toHandle()).startedAtMillis();
            long strangerStarted = HostLeases.Owner.of(stranger.toHandle()).startedAtMillis();
            assertThat(started).isPositive();
            // The second pid names a process that started a minute after the dead engine's worker did.
            Files.writeString(
                    engineDir.resolve(HostLeases.FILE),
                    gonePid + " 0 " + BUDGET + " 8 " + 2 * GIB + " 2 0 " + orphan.pid() + ":" + started + " "
                            + stranger.pid() + ":" + (strangerStarted - 60_000) + "\n");

            WorkerLeases.Ledger next = new WorkerLeases.Ledger(
                            () -> BUDGET, () -> 8, id -> false, OverbookSignals.off())
                    .shareHost(HostLeases.forThisProcess(engineDir));
            assertThat(orphan.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(stranger.isAlive()).isTrue();
            assertThat(next.snapshot().leasedBytes()).isZero();
            assertThat(Files.readString(engineDir.resolve(HostLeases.FILE))).doesNotContain(Long.toString(gonePid));
        } finally {
            orphan.destroyForcibly();
            stranger.destroyForcibly();
        }
    }

    /** One engine of {@link #engineDir} with a made-up pid; every engine of the test counts as alive. */
    private WorkerLeases.Ledger engine(long pid, OverbookSignals.Source signals) {
        return new WorkerLeases.Ledger(() -> BUDGET, () -> 8, id -> false, signals)
                .shareHost(new HostLeases(engineDir, new HostLeases.Owner(pid, 0), owner -> true, workers -> 0));
    }

    /** As {@link #engine(long, OverbookSignals.Source)} with its own budget and slot cap, overbooking off. */
    private WorkerLeases.Ledger engine(long pid, long budget, int slots, Predicate<HostLeases.Owner> alive) {
        return new WorkerLeases.Ledger(() -> budget, () -> slots, id -> false, OverbookSignals.off())
                .waitAtMost(Duration.ofSeconds(10))
                .shareHost(new HostLeases(engineDir, new HostLeases.Owner(pid, 0), alive, workers -> 0));
    }

    /** A process that outlives the test unless it is stopped. */
    private static Process sleeper() throws Exception {
        return new ProcessBuilder("sleep", "120")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectErrorStream(true)
                .start();
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

    /** The other engines' queue is re-read at most every 250 ms. */
    private static void awaitOthersWaiting(WorkerLeases.Ledger ledger, int waiting) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (ledger.otherEnginesWaiting() != waiting) {
            if (System.nanoTime() > deadline)
                throw new AssertionError("other engines waiting: " + ledger.otherEnginesWaiting());
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
