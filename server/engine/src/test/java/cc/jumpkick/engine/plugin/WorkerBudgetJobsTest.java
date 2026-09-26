// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.PluginTuning;
import cc.jumpkick.config.SessionContext;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Two jobs in one engine, under a budget that holds one fork: both finish, and the second waits
 * instead of failing. Killing a fork returns its lease.
 */
class WorkerBudgetJobsTest {

    @AfterEach
    void reset() {
        JvmOptions.resetSharedPlanForTests();
    }

    @Test
    void the_heap_plan_fits_its_parallelism_in_the_budget() {
        WorkerLeases.Ledger ledger = ledger(2L << 30, 4);
        HeapPlan.Plan plan = Objects.requireNonNull(JvmOptions.planAndApply(8, ledger));
        long lease = WorkerLeases.jvmLease(plan.xmxBytes());
        assertThat((long) plan.parallelism() * lease).isLessThanOrEqualTo(ledger.capacityBytes());
    }

    @Test
    void a_tight_budget_keeps_the_planned_heap_and_drops_parallelism() {
        WorkerLeases.Ledger ledger = ledger(2560L << 20, 8);
        HeapPlan.Plan raw = HeapPlan.compute(14L << 30, 23);
        HeapPlan.Plan fit = JvmOptions.fitWorkerBudget(raw, ledger);
        assertThat(fit.xmxBytes()).isEqualTo(raw.xmxBytes());
        assertThat(fit.parallelism()).isLessThan(raw.parallelism()).isGreaterThan(0);
        assertThat((long) fit.parallelism() * WorkerLeases.jvmLease(fit.xmxBytes()))
                .isLessThanOrEqualTo(ledger.capacityBytes());
    }

    @Test
    void one_heap_bigger_than_the_budget_is_clamped_to_a_single_worker() {
        WorkerLeases.Ledger ledger = ledger(2L << 30, 4);
        HeapPlan.Plan raw = new HeapPlan.Plan(4, 64L << 20, 1L << 30, 8L << 30, null);
        HeapPlan.Plan fit = JvmOptions.fitWorkerBudget(raw, ledger);
        assertThat(fit.parallelism()).isEqualTo(1);
        assertThat(fit.xmxBytes()).isLessThan(raw.xmxBytes());
        assertThat(WorkerLeases.jvmLease(fit.xmxBytes())).isLessThanOrEqualTo(ledger.capacityBytes());
    }

    @Test
    void two_jobs_under_a_tiny_budget_both_finish_one_waiting() throws Exception {
        WorkerLeases.Ledger ledger = ledger(WorkerLeases.TOOL_BYTES, 8);
        CompletableFuture<Integer> first = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                first.complete(run(ledger, 1L, "sleep", "1"));
            } catch (RuntimeException e) {
                first.completeExceptionally(e);
            }
        });
        awaitLeased(ledger);
        CompletableFuture<Integer> second = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                second.complete(run(ledger, 2L, "sleep", "0.1"));
            } catch (RuntimeException e) {
                second.completeExceptionally(e);
            }
        });
        assertThat(first.get(10, TimeUnit.SECONDS)).isZero();
        assertThat(second.get(10, TimeUnit.SECONDS)).isZero();
        assertThat(ledger.waitedNanos(1L) + ledger.waitedNanos(2L)).isPositive();
        awaitReleased(ledger);
        assertThat(ledger.queued()).isZero();
    }

    @Test
    void killing_a_fork_returns_its_lease() throws Exception {
        WorkerLeases.Ledger ledger = ledger(WorkerLeases.TOOL_BYTES, 8);
        long before = ledger.snapshot().leasedBytes();
        JobWorkers.open(11L);
        Process process = JobWorkers.start(new ProcessBuilder("sleep", "30"), ledger);
        try {
            assertThat(ledger.snapshot().leasedBytes()).isGreaterThan(before);
            process.destroyForcibly();
            assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (ledger.snapshot().leasedBytes() != before) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("lease still held: " + ledger.snapshot());
                }
                Thread.sleep(20);
            }
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            JobWorkers.clear(11L);
            JobWorkers.close();
        }
    }

    @Test
    void a_heap_larger_than_the_budget_is_lowered_and_the_jvm_still_starts() throws Exception {
        SessionContext.where(SessionContext.installed().withJvm(PluginTuning.NONE), () -> {
            JvmOptions.notePlannedHeap("-Xmx2g");
            WorkerLeases.Ledger ledger = ledger(300L << 20, 4);
            String java =
                    Path.of(System.getProperty("java.home"), "bin", "java").toString();
            JobWorkers.open(12L);
            ProcessBuilder pb = new ProcessBuilder(java, "-Xmx2g", "-version")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD);
            Process process = JobWorkers.start(pb, ledger);
            try {
                assertThat(pb.command()).doesNotContain("-Xmx2g");
                assertThat(WorkerLeases.jvmLease(WorkerLeases.parseXmx(pb.command())))
                        .isLessThanOrEqualTo(ledger.capacityBytes());
                assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
                assertThat(process.exitValue()).isZero();
            } finally {
                JvmOptions.forgetPlannedHeapForTests("-Xmx2g");
                if (process.isAlive()) process.destroyForcibly();
                JobWorkers.clear(12L);
                JobWorkers.close();
            }
            awaitReleased(ledger);
            return null;
        });
    }

    /** Does not watch {@link JobWorkers} shutdown tombstones; those are process-wide. */
    private static WorkerLeases.Ledger ledger(long capacityBytes, int cpu) {
        return new WorkerLeases.Ledger(() -> capacityBytes, () -> cpu, id -> false);
    }

    private static int run(WorkerLeases.Ledger ledger, long requestId, String... command) {
        JobWorkers.open(requestId);
        try {
            Process process = JobWorkers.start(new ProcessBuilder(command), ledger);
            return process.waitFor();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            JobWorkers.clear(requestId);
            JobWorkers.close();
        }
    }

    private static void awaitReleased(WorkerLeases.Ledger ledger) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (ledger.snapshot().leasedBytes() != 0 || ledger.queued() != 0) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("ledger not idle: " + ledger.snapshot());
            }
            Thread.sleep(20);
        }
    }

    private static void awaitLeased(WorkerLeases.Ledger ledger) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (ledger.snapshot().leasedBytes() <= 0) {
            if (System.nanoTime() > deadline) throw new AssertionError("no lease taken");
            Thread.sleep(5);
        }
    }
}
