// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.config.PluginTuning;
import cc.jumpkick.config.Request;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.task.IoLedger;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Two builds in one engine each size their workers by the heap plan they made. */
class HeapPlanIsolationTest {

    @AfterEach
    void reset() {
        JvmOptions.resetSharedPlanForTests();
    }

    @Test
    void two_concurrent_builds_keep_separate_heap_plans() throws Exception {
        WorkerLeases.Ledger ledger = new WorkerLeases.Ledger(() -> 64L << 30, () -> 16, id -> false);
        CyclicBarrier bothPlanned = new CyclicBarrier(2);
        AtomicReference<HeapPlan.@Nullable Plan> narrowPlanned = new AtomicReference<>();
        AtomicReference<HeapPlan.@Nullable Plan> narrowSees = new AtomicReference<>();
        AtomicReference<HeapPlan.@Nullable Plan> widePlanned = new AtomicReference<>();
        AtomicReference<HeapPlan.@Nullable Plan> wideSees = new AtomicReference<>();
        SessionContext.where(SessionContext.installed().withJvm(PluginTuning.NONE), () -> {
            Thread narrow = SessionContext.startPlatform(
                    "narrow-build", () -> build(1, ledger, bothPlanned, narrowPlanned, narrowSees));
            Thread wide = SessionContext.startPlatform(
                    "wide-build", () -> build(8, ledger, bothPlanned, widePlanned, wideSees));
            narrow.join(TimeUnit.SECONDS.toMillis(30));
            wide.join(TimeUnit.SECONDS.toMillis(30));
            return null;
        });

        assumeTrue(
                narrowPlanned.get() != null
                        && widePlanned.get() != null
                        && narrowPlanned.get().parallelism()
                                != widePlanned.get().parallelism(),
                "the host has memory for more than one worker");
        assertThat(narrowSees.get()).isEqualTo(narrowPlanned.get());
        assertThat(wideSees.get()).isEqualTo(widePlanned.get());
        assertThat(JvmOptions.heapPlan())
                .as("neither build planned the engine's")
                .isNull();
    }

    private static void build(
            int width,
            WorkerLeases.Ledger ledger,
            CyclicBarrier bothPlanned,
            AtomicReference<HeapPlan.@Nullable Plan> planned,
            AtomicReference<HeapPlan.@Nullable Plan> sees) {
        Request.of(width, new IoLedger())
                .with(JvmOptions.PLAN, new JvmOptions.PlannedHeap())
                .run(() -> {
                    planned.set(JvmOptions.planAndApply(width, ledger));
                    try {
                        bothPlanned.await(10, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    sees.set(JvmOptions.heapPlan());
                });
    }
}
