// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.TaskContext;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The ledger charges a watched worker its measured resident set when that is above its lease. */
class WorkerLeasesRssTest {

    private static final long GIB = 1L << 30;
    private static final long MIB = 1L << 20;

    @Test
    void far_over_lease_is_a_quarter_of_the_lease_or_one_gib_past_it() {
        assertThat(WorkerRss.farOverLease(700 * MIB, 700 * MIB + GIB)).isFalse();
        assertThat(WorkerRss.farOverLease(700 * MIB, 700 * MIB + GIB + 1)).isTrue();
        assertThat(WorkerRss.farOverLease(8 * GIB, 10 * GIB)).isFalse();
        assertThat(WorkerRss.farOverLease(8 * GIB, 10 * GIB + 1)).isTrue();
        assertThat(WorkerRss.farOverLease(GIB, -1)).isFalse();
    }

    @Test
    void a_worker_cap_is_three_quarters_of_the_budget_never_below_the_lease_nor_above_the_budget() {
        long budget = 12 * GIB;
        assertThat(WorkerRss.workerCapBytes(700 * MIB, budget)).isEqualTo(9 * GIB);
        assertThat(WorkerRss.workerCapBytes(10 * GIB, budget)).isEqualTo(10 * GIB);
        assertThat(WorkerRss.workerCapBytes(20 * GIB, budget)).isEqualTo(budget);
        assertThat(WorkerRss.workerCapBytes(GIB, 0)).isEqualTo(-1);
        assertThat(WorkerRss.workerCapBytes(0, 14_521_000_001L) % 4096)
                .as("page-aligned")
                .isZero();
    }

    @Test
    void forks_are_described_by_what_they_run() {
        assertThat(WorkerRss.describe(
                        List.of("/jdk/bin/java", "-Xmx512m", "-Djk.plugin.class=cc.jumpkick.testrunner.TestRunner")))
                .isEqualTo("test JVM");
        assertThat(WorkerRss.describe(List.of("/jdk/bin/java", "-cp", "x", "Main")))
                .isEqualTo("worker JVM");
        assertThat(WorkerRss.describe(List.of("/usr/bin/git", "status"))).isEqualTo("git");
    }

    @Test
    void a_worker_over_its_lease_is_charged_its_resident_set_and_holds_back_the_next_grant() throws Exception {
        Map<Long, Long> rss = new ConcurrentHashMap<>();
        WorkerLeases.Ledger ledger = ledger(10 * GIB, rss);
        try (WorkerLeases.Grant hog = ledger.acquireBytes(GIB, true, 7L)) {
            hog.watch(101, "test JVM", null);
            rss.put(101L, 9 * GIB);
            ledger.sample();
            assertThat(ledger.snapshot().leasedBytes()).isEqualTo(9 * GIB);

            CompletableFuture<WorkerLeases.Grant> next = acquireOnThread(() -> ledger.acquireBytes(2 * GIB, true, 8L));
            awaitQueued(ledger, 1);
            assertThat(next).isNotDone();
            assertThat(ledger.snapshot().waiting()).contains("job #8 needs 2.0 GiB (1.0 GiB free)");

            rss.put(101L, 3 * GIB);
            ledger.sample();
            try (WorkerLeases.Grant granted = next.get(5, TimeUnit.SECONDS)) {
                assertThat(granted.bytes()).isEqualTo(2 * GIB);
                assertThat(ledger.snapshot().leasedBytes()).isEqualTo(5 * GIB);
            }
        }
        assertThat(ledger.snapshot().leasedBytes()).isZero();
    }

    @Test
    void a_worker_under_its_lease_is_charged_the_lease() throws Exception {
        Map<Long, Long> rss = new ConcurrentHashMap<>();
        WorkerLeases.Ledger ledger = ledger(10 * GIB, rss);
        try (WorkerLeases.Grant grant = ledger.acquireBytes(2 * GIB, true, null)) {
            grant.watch(101, "worker JVM", null);
            rss.put(101L, 300 * MIB);
            ledger.sample();
            assertThat(ledger.snapshot().leasedBytes()).isEqualTo(2 * GIB);
            assertThat(ledger.snapshot().overLease()).isEmpty();
        }
    }

    @Test
    void a_worker_far_over_its_lease_is_named_once_per_step_and_in_status() throws Exception {
        Map<Long, Long> rss = new ConcurrentHashMap<>();
        List<String> warnings = new CopyOnWriteArrayList<>();
        TaskContext step = recording(warnings);
        WorkerLeases.Ledger ledger = ledger(16 * GIB, rss);
        try (WorkerLeases.Grant first = ledger.acquireBytes(700 * MIB, true, 7L);
                WorkerLeases.Grant second = ledger.acquireBytes(700 * MIB, true, 7L)) {
            first.watch(101, "test JVM", step);
            second.watch(102, "test JVM", step);
            rss.put(101L, 1500 * MIB);
            ledger.sample();
            assertThat(warnings).isEmpty();
            assertThat(ledger.snapshot().overLease()).isEmpty();

            rss.put(101L, 12 * GIB + 200 * MIB);
            ledger.sample();
            ledger.sample();
            assertThat(warnings).containsExactly("memory-over-lease test JVM using 12.2 GiB, leased 700 MiB");
            assertThat(ledger.snapshot().overLease())
                    .isEqualTo("test JVM pid 101 (job #7) using 12.2 GiB, leased 700 MiB");

            rss.put(102L, 3 * GIB);
            ledger.sample();
            assertThat(warnings).hasSize(1);
            assertThat(ledger.snapshot().overLease())
                    .isEqualTo("test JVM pid 101 (job #7) using 12.2 GiB, leased 700 MiB; "
                            + "test JVM pid 102 (job #7) using 3.0 GiB, leased 700 MiB");
        }
        assertThat(ledger.snapshot().overLease()).isEmpty();
    }

    @Test
    void nothing_is_overbooked_while_a_worker_is_far_over_its_lease() throws Exception {
        Map<Long, Long> rss = new ConcurrentHashMap<>();
        long budget = 4 * GIB;
        OverbookSignals.Source healthy = () -> new OverbookSignals.Reading(true, 64 * GIB, 0, 0);
        WorkerLeases.Ledger ledger =
                new WorkerLeases.Ledger(() -> budget, () -> 8, id -> false, healthy, pid -> rss.getOrDefault(pid, -1L));
        try (WorkerLeases.Grant held = ledger.acquireBytes(GIB, true, null)) {
            held.watch(101, "test JVM", null);
            try (WorkerLeases.Grant over = ledger.acquireBytes(budget, true, null)) {
                assertThat(over.overbooked()).isTrue();
            }
            rss.put(101L, 3 * GIB);
            ledger.sample();
            CompletableFuture<WorkerLeases.Grant> next = acquireOnThread(() -> ledger.acquireBytes(budget, true, null));
            awaitQueued(ledger, 1);
            assertThat(next).isNotDone();
            rss.put(101L, 500 * MIB);
            ledger.sample();
            try (WorkerLeases.Grant granted = next.get(5, TimeUnit.SECONDS)) {
                assertThat(granted.overbooked()).isTrue();
            }
        }
    }

    @Test
    void a_closed_grant_ignores_watch_and_its_excess_leaves_with_it() throws Exception {
        Map<Long, Long> rss = new ConcurrentHashMap<>();
        WorkerLeases.Ledger ledger = ledger(10 * GIB, rss);
        WorkerLeases.Grant grant = ledger.acquireBytes(GIB, true, null);
        grant.watch(101, "test JVM", null);
        rss.put(101L, 4 * GIB);
        ledger.sample();
        assertThat(ledger.snapshot().leasedBytes()).isEqualTo(4 * GIB);
        grant.close();
        assertThat(ledger.snapshot().leasedBytes()).isZero();
        grant.watch(102, "test JVM", null);
        rss.put(102L, 4 * GIB);
        ledger.sample();
        assertThat(ledger.snapshot().leasedBytes()).isZero();
    }

    private static WorkerLeases.Ledger ledger(long capacity, Map<Long, Long> rss) {
        return new WorkerLeases.Ledger(
                () -> capacity, () -> 8, id -> false, OverbookSignals.off(), pid -> rss.getOrDefault(pid, -1L));
    }

    private static void awaitQueued(WorkerLeases.Ledger ledger, int n) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (ledger.queued() < n) {
            if (System.nanoTime() > deadline) throw new AssertionError("queued " + ledger.queued());
            Thread.sleep(5);
        }
    }

    /** Blocks on its own thread: a waiting lease must not sit on the common pool that completes {@code onExit}. */
    private static CompletableFuture<WorkerLeases.Grant> acquireOnThread(Acquire acquire) {
        CompletableFuture<WorkerLeases.Grant> out = new CompletableFuture<>();
        Thread.ofPlatform().daemon().start(() -> {
            try {
                out.complete(acquire.get());
            } catch (InterruptedException | RuntimeException e) {
                out.completeExceptionally(e);
            }
        });
        return out;
    }

    @FunctionalInterface
    private interface Acquire {
        WorkerLeases.Grant get() throws InterruptedException;
    }

    private static TaskContext recording(List<String> warnings) {
        return new TaskContext() {
            @Override
            public void progress(int delta) {}

            @Override
            public void updateTicks(int additional) {}

            @Override
            public void label(@Nullable String description) {}

            @Override
            public void output(@Nullable String line) {}

            @Override
            public void waited(Duration blocked) {}

            @Override
            public void warn(String code, String message) {
                warnings.add(code + " " + message);
            }

            @Override
            public void error(String code, String message) {}

            @Override
            public boolean cancelled() {
                return false;
            }

            @Override
            public <T> void put(BuildPlanKey<T> key, T value) {}

            @Override
            public <T> Optional<T> get(BuildPlanKey<T> key) {
                return Optional.empty();
            }

            @Override
            public <T> T require(BuildPlanKey<T> key) {
                throw new IllegalStateException(key.toString());
            }
        };
    }
}
