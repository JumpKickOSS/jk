// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.PluginTuning;
import cc.jumpkick.engine.plugin.JvmOptions;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The compiler worker's heap ceiling agrees with its cgroup cap: a contained worker gets the whole
 * worker budget as its cap and an explicit heap that leaves room under it, an uncontained one keeps
 * the JVM's share of RAM, and a second worker gets what the first leaves of the budget.
 */
class CompilerBudgetTest {

    private static final long GIB = 1L << 30;

    @Test
    void a_contained_worker_is_capped_at_the_budget_with_its_heap_below_the_cap() {
        CompilerBudget.Heap heap = CompilerBudget.plan(64 * GIB, 24 * GIB, 0, true);
        assertThat(heap.capBytes()).isEqualTo(24 * GIB);
        assertThat(heap.xmxBytes()).isEqualTo(24 * GIB - CompilerBudget.NATIVE_HEADROOM_BYTES);
        assertThat(heap.ceilingBytes()).isEqualTo(heap.xmxBytes());

        CompilerBudget.Heap small = CompilerBudget.plan(8 * GIB, 24 * GIB, 0, true);
        assertThat(small.xmxBytes())
                .as("68% of RAM when that is lower than the cap allows")
                .isEqualTo((long) (8 * GIB * CompilerBudget.MAX_RAM_FRACTION));
    }

    @Test
    void an_uncontained_worker_keeps_the_jvms_share_of_ram() {
        CompilerBudget.Heap heap = CompilerBudget.plan(16 * GIB, 24 * GIB, 0, false);
        assertThat(heap.xmxBytes()).as("MaxRAMPercentage, not an -Xmx").isNull();
        assertThat(heap.capBytes()).isEqualTo(-1L);
        assertThat(heap.ceilingBytes()).isEqualTo((long) (16 * GIB * CompilerBudget.MAX_RAM_FRACTION));
    }

    @Test
    void a_second_worker_gets_what_the_first_leaves_of_the_budget_and_never_less_than_the_least() {
        CompilerBudget.Heap second = CompilerBudget.plan(16 * GIB, 12 * GIB, 10 * GIB, false);
        assertThat(second.xmxBytes()).isEqualTo(2 * GIB);
        CompilerBudget.Heap starved = CompilerBudget.plan(16 * GIB, 12 * GIB, 12 * GIB, true);
        assertThat(starved.xmxBytes()).isEqualTo(CompilerBudget.LEAST_HEAP_BYTES);
        assertThat(starved.capBytes()).as("the cap is still the whole budget").isEqualTo(12 * GIB);
    }

    @Test
    void an_explicit_ceiling_replaces_the_ram_share_and_a_users_heap_flag_replaces_both() {
        List<String> capped = JvmOptions.compilerWorkerFlags(PluginTuning.NONE, 3 * GIB);
        assertThat(capped).contains("-Xms64m", "-Xmx3072m").noneMatch(f -> f.startsWith("-XX:MaxRAMPercentage"));
        assertThat(JvmOptions.compilerWorkerFlags(PluginTuning.NONE, null))
                .contains("-XX:MaxRAMPercentage=68")
                .noneMatch(f -> f.startsWith("-Xmx"));
        List<String> pinned =
                JvmOptions.compilerWorkerFlags(new PluginTuning(null, null, null, List.of("-Xmx2g")), 3 * GIB);
        assertThat(pinned).contains("-Xmx2g").doesNotContain("-Xmx3072m", "-Xms64m");
    }

    /** The heap follows the compiles the worker can run at once, not the host's RAM. */
    @Test
    void the_ceiling_follows_the_compiles_a_worker_runs() {
        CompilerBudget.Heap wide = CompilerBudget.plan(64 * GIB, 48 * GIB, 0, true, 24);
        assertThat(wide.ceilingBytes()).isEqualTo(12 * GIB);
        assertThat(wide.xmxBytes()).isEqualTo(12 * GIB);
        CompilerBudget.Heap few = CompilerBudget.plan(64 * GIB, 48 * GIB, 0, false, 2);
        assertThat(few.ceilingBytes()).as("never below the least working heap").isEqualTo(4 * GIB);
        assertThat(few.xmxBytes())
                .as("an explicit -Xmx below the JVM's own share")
                .isEqualTo(4 * GIB);
    }
}
