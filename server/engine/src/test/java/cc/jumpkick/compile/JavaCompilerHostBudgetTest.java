// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compile.JavaCompilerHost.Lanes;
import cc.jumpkick.compile.JavaCompilerHost.Session;
import cc.jumpkick.engine.plugin.JobWorkers;
import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.engine.plugin.WorkerLeases;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Idle lanes give their lease back, and every job draws lanes from one budget. */
class JavaCompilerHostBudgetTest {

    @AfterEach
    void reset() {
        JvmOptions.resetSharedPlanForTests();
        JavaCompilerHost.overrideLaneBudgetForTests(0);
        JavaCompilerHost.resetLaneCountForTests();
        JavaCompilerHost.onIdleLeaveGapForTests(null);
        JobWorkers.close();
    }

    @Test
    void an_idle_compile_lane_lets_a_test_jvm_start_when_the_budget_holds_one(@TempDir Path dir) throws Exception {
        long lease = 64L << 20;
        WorkerLeases.Ledger ledger = ledger(lease, 8);
        JavaCompilerHost.overrideLaneBudgetForTests(2);
        CountDownLatch compiling = new CountDownLatch(1);
        CountDownLatch finishCompile = new CountDownLatch(1);
        Lanes pool = new Lanes(
                2,
                (owner, index) -> new Session(
                        owner, 31L, index, self -> holdLeaseWhileCompiling(self, ledger, compiling, finishCompile)),
                req -> dir.resolve("spec"),
                (failed, bigger) -> {},
                true,
                ledger);
        CompileWork work = CompileWork.compile(request(dir, "mod"));
        try {
            pool.enqueue(work);
            assertThat(compiling.await(5, TimeUnit.SECONDS)).isTrue();
            finishCompile.countDown();
            assertThat(work.compile).succeedsWithin(DurationOf.FIVE);
            CompletableFuture<WorkerLeases.Grant> testJvm = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                try {
                    testJvm.complete(ledger.acquireBytes(lease, true, 32L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    testJvm.completeExceptionally(e);
                }
            });
            testJvm.get(5, TimeUnit.SECONDS).close();
        } finally {
            pool.close();
        }
    }

    @Test
    void a_lane_grown_by_another_jobs_exit_forks_for_its_own_job(@TempDir Path dir) throws Exception {
        JavaCompilerHost.overrideLaneBudgetForTests(1);
        WorkerLeases.Ledger ledger = roomy();
        AtomicReference<Long> owner = new AtomicReference<>();
        AtomicReference<Long> nudged = new AtomicReference<>();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch arrived = new CountDownLatch(1);
        Lanes first = new Lanes(
                1,
                (lanes, index) -> new Session(lanes, 9101L, index, self -> {
                    owner.set(JobWorkers.currentRequestId());
                    CompileWork next = self.takeNext();
                    holding.countDown();
                    release.await(5, TimeUnit.SECONDS);
                    next.compile.complete(ok());
                    self.takeNext();
                }),
                req -> dir.resolve("spec-a"),
                (failed, bigger) -> {},
                true,
                ledger);
        Lanes second = new Lanes(
                1,
                (lanes, index) -> new Session(lanes, 9102L, index, self -> {
                    nudged.set(JobWorkers.currentRequestId());
                    arrived.countDown();
                    for (CompileWork next = self.takeNext(); next != CompileWork.POISON; next = self.takeNext()) {
                        next.compile.complete(ok());
                    }
                }),
                req -> dir.resolve("spec-b"),
                (failed, bigger) -> {},
                true,
                ledger);
        CompileWork a = CompileWork.compile(request(dir, "a"));
        CompileWork b = CompileWork.compile(request(dir, "b"));
        JobWorkers.open(9101L);
        try {
            first.enqueue(a);
            assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();
            second.enqueue(b);
            Thread.sleep(150);
            assertThat(second.liveLanes()).isZero();
            release.countDown();
            assertThat(arrived.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(owner.get()).isEqualTo(9101L);
            assertThat(nudged.get())
                    .as("the lane another job's exit started still belongs to its own job")
                    .isEqualTo(9102L);
            assertThat(b.compile).succeedsWithin(DurationOf.FIVE);
        } finally {
            release.countDown();
            JobWorkers.close();
            first.close();
            second.close();
        }
    }

    @Test
    void work_assigned_as_a_lane_decides_to_leave_stays_on_that_lane(@TempDir Path dir) throws Exception {
        JavaCompilerHost.overrideLaneBudgetForTests(1);
        WorkerLeases.Ledger ledger = roomy();
        AtomicReference<Session> origin = new AtomicReference<>();
        AtomicReference<Session> ran = new AtomicReference<>();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean armed = new AtomicBoolean();
        CompileWork held = CompileWork.compile(request(dir, "held"));
        CompileWork arrived = CompileWork.compile(request(dir, "arrived"));
        Lanes home = new Lanes(
                1,
                (lanes, index) -> {
                    Session s = new Session(lanes, 71L, index, self -> {
                        for (CompileWork next = self.takeNext(); next != CompileWork.POISON; next = self.takeNext()) {
                            if (next == held) {
                                holding.countDown();
                                release.await(5, TimeUnit.SECONDS);
                            } else ran.set(self);
                            next.compile.complete(ok());
                        }
                    });
                    if (origin.get() == null) origin.set(s);
                    return s;
                },
                req -> dir.resolve("spec-home"),
                (failed, bigger) -> {},
                true,
                ledger);
        Lanes other = new Lanes(
                1,
                (lanes, index) -> new Session(lanes, 72L, index, self -> {
                    for (CompileWork next = self.takeNext(); next != CompileWork.POISON; next = self.takeNext()) {
                        next.compile.complete(ok());
                    }
                }),
                req -> dir.resolve("spec-other"),
                (failed, bigger) -> {},
                true,
                ledger);
        JavaCompilerHost.onIdleLeaveGapForTests(() -> {
            if (armed.compareAndSet(false, true)) home.enqueue(arrived);
        });
        CompileWork blocked = CompileWork.compile(request(dir, "blocked"));
        try {
            home.enqueue(held);
            assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();
            other.enqueue(blocked);
            release.countDown();
            assertThat(arrived.compile).succeedsWithin(DurationOf.FIVE);
            assertThat(ran.get())
                    .as("the lane that was about to leave took the work itself")
                    .isSameAs(origin.get());
        } finally {
            release.countDown();
            JavaCompilerHost.onIdleLeaveGapForTests(null);
            home.close();
            other.close();
        }
    }

    @Test
    void two_jobs_share_one_lane_budget_and_an_idle_lane_yields(@TempDir Path dir) throws Exception {
        JavaCompilerHost.overrideLaneBudgetForTests(1);
        WorkerLeases.Ledger ledger = roomy();
        CountDownLatch busy = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Lanes first = pool(dir, 41L, busy, release, ledger);
        Lanes second = pool(dir, 42L, new CountDownLatch(1), new CountDownLatch(0), ledger);
        CompileWork a = CompileWork.compile(request(dir, "a"));
        CompileWork b = CompileWork.compile(request(dir, "b"));
        try {
            first.enqueue(a);
            assertThat(busy.await(5, TimeUnit.SECONDS)).isTrue();
            second.enqueue(b);
            Thread.sleep(150);
            assertThat(second.liveLanes())
                    .as("the second job waits while the only lane is busy")
                    .isZero();
            release.countDown();
            assertThat(b.compile).succeedsWithin(DurationOf.FIVE);
            assertThat(a.compile).isDone();
        } finally {
            release.countDown();
            first.close();
            second.close();
        }
    }

    /** Does not watch {@link JobWorkers} shutdown tombstones; those are process-wide. */
    private static WorkerLeases.Ledger ledger(long capacityBytes, int cpu) {
        return new WorkerLeases.Ledger(() -> capacityBytes, () -> cpu, id -> false);
    }

    /** Memory is not the constraint; the lane count is. */
    private static WorkerLeases.Ledger roomy() {
        return ledger(8L << 30, 8);
    }

    private static void holdLeaseWhileCompiling(
            Session self, WorkerLeases.Ledger ledger, CountDownLatch compiling, CountDownLatch finishCompile)
            throws Exception {
        try (WorkerLeases.Grant ignored = ledger.acquireBytes(ledger.capacityBytes(), true, 31L)) {
            for (CompileWork next = self.takeNext(); next != CompileWork.POISON; next = self.takeNext()) {
                compiling.countDown();
                finishCompile.await(5, TimeUnit.SECONDS);
                next.compile.complete(ok());
            }
        }
    }

    private static Lanes pool(
            Path dir, long id, CountDownLatch busy, CountDownLatch release, WorkerLeases.Ledger ledger) {
        return new Lanes(
                4,
                (owner, index) -> new Session(owner, id, index, self -> {
                    for (CompileWork next = self.takeNext(); next != CompileWork.POISON; next = self.takeNext()) {
                        busy.countDown();
                        release.await(5, TimeUnit.SECONDS);
                        next.compile.complete(ok());
                    }
                }),
                req -> dir.resolve("spec-" + id),
                (failed, bigger) -> {},
                true,
                ledger);
    }

    private static ForkedJavac.Result ok() {
        return new ForkedJavac.Result(true, List.of(), Map.of(), List.of(), 0L);
    }

    private static ForkedJavac.Request request(Path dir, String name) {
        Path root = dir.resolve(name);
        return new ForkedJavac.Request(
                null,
                root.resolve("worker.jar"),
                List.of(root.resolve("C.java")),
                List.of(),
                List.of(),
                root.resolve("classes"),
                root.resolve("gen"),
                21,
                List.of());
    }

    /** {@link org.assertj.core.api.AbstractFutureAssert#succeedsWithin(java.time.Duration)} argument. */
    private static final class DurationOf {
        static final Duration FIVE = Duration.ofSeconds(5);

        private DurationOf() {}
    }
}
