// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.TestSelection;
import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.runtime.TestClassMatch;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code [test] serial-tags} splits one suite into a sharded pool and a trailing serial pool. The
 * launcher hands back their merge, so a {@code --class} that only matched serial classes is a
 * suite that ran, never an empty pool judged on its own.
 */
class JUnitLauncherSerialPartitionTest {

    @Test
    void a_pattern_matching_only_serial_classes_is_a_match() {
        TestSummary emptyShardedPool = new TestSummary(0, 0, 0, 0, List.of());
        TestSummary serialPool = new TestSummary(2, 2, 0, 0, 2, List.of(), Map.of("acme.SerialTest", 40L), 1);

        TestSummary suite = JUnitLauncher.merge(emptyShardedPool, serialPool);

        assertThat(suite.total()).isEqualTo(2);
        assertThat(suite.classes()).isEqualTo(2);
        assertThat(suite.classWallMs()).containsEntry("acme.SerialTest", 40L);
        TestSelection serialOnly = TestSelection.DEFAULT.withClasses(List.of("Serial*"));
        assertThat(TestClassMatch.nothingMatched(serialOnly, false, suite)).isFalse();
    }

    @Test
    void the_serial_pool_runs_after_a_shortfall_or_a_worker_death_but_not_after_a_broken_fork_or_a_cancel() {
        TestFailureInfo shortfall = requireNonNull(RunShortfall.row("g:app", false, true, 2));
        TestFailureInfo worker = new TestFailureInfo(
                "g:app", "", "acme.BigTest", "(worker 1)", "", "test worker exited 137 mid-run", "", 1);
        TestFailureInfo red = new TestFailureInfo("g:app", "", "acme.FooTest", "fails()", "", "boom", "", 0);
        TestFailureInfo broken = new TestFailureInfo(
                "g:app", "", "", "(test run)", "", "test protocol handler threw IllegalStateException", "", 1);
        assertThat(JUnitLauncher.serialPoolRuns(summary(shortfall, worker, red), false))
                .isTrue();
        assertThat(JUnitLauncher.serialPoolRuns(summary(), false)).isTrue();
        assertThat(JUnitLauncher.serialPoolRuns(summary(shortfall, broken), false))
                .isFalse();
        assertThat(JUnitLauncher.serialPoolRuns(summary(), true)).isFalse();
    }

    private static TestSummary summary(TestFailureInfo... failures) {
        return new TestSummary(failures.length, 0, failures.length, 0, List.of(failures));
    }

    @Test
    void the_merge_keeps_the_wider_pool_as_the_suite_concurrency() {
        TestSummary sharded = new TestSummary(4, 4, 0, 0, 4, List.of(), Map.of(), 3);
        TestSummary serial = new TestSummary(1, 1, 0, 0, 1, List.of(), Map.of(), 1);
        assertThat(JUnitLauncher.merge(sharded, serial).workers()).isEqualTo(3);
    }
}
