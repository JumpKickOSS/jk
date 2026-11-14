// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.test.TestProgressListener;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A suite with settings of its own runs in its own launch, so one class can run in two launches:
 * its untagged methods in the default suite's, its tagged ones in the other's. The run-tests step
 * shares one ledger across the launches and stores it when a run is stopped part way. A launch
 * may leave a class out only when that class passed in that launch, never on another launch's word.
 */
class TestClassLedgerLaunchesTest {

    private static final String STAMP = "a".repeat(64);
    private static final String TASK = "run-tests:/ws/app/target/test-classes";
    private static final String UNIT = "test:test";
    private static final String INTEG = "test:integration";

    @TempDir
    Path tmp;

    private ActionCache cache;

    @BeforeEach
    void setUp() {
        cache = new ActionCache(new Cas(tmp.resolve("cas")), tmp.resolve("actions"));
    }

    private static String cls(String name) {
        return "[engine:junit-jupiter]/[class:" + name + "]";
    }

    /** One launch's share of a class: one method, then the class container. */
    private static void launch(TestProgressListener l, String name, String method, String status) {
        l.onTestFinished(cls(name) + "/[method:" + method + "()]", method, status, true, true, 5, 0);
        l.onTestFinished(cls(name), name, "SUCCESSFUL", false, true, 10, 0);
    }

    private TestClassLedger load() {
        return TestClassLedger.load(cache, STAMP, "g:app", "test");
    }

    @Test
    void a_class_whose_second_launch_never_finished_runs_again_in_that_launch() {
        TestClassLedger first = load();
        TestProgressListener l = first.recording(TestProgressListener.noop());
        first.enter(UNIT);
        launch(l, "demo.FooTest", "unit", "SUCCESSFUL");
        first.enter(INTEG);
        // The integration suite's launch is cancelled before FooTest#integ reports.
        first.store(cache, TASK, STAMP);

        TestClassLedger rerun = load();
        assertThat(rerun.enter(UNIT)).as("FooTest#unit passed in its launch").containsExactly("demo.FooTest");
        assertThat(rerun.enter(INTEG))
                .as("FooTest#integ never ran, so the re-run must still run FooTest in its launch")
                .isEmpty();
    }

    @Test
    void a_class_red_in_one_launch_and_green_in_the_next_is_not_settled_by_a_cut_short_run() {
        TestClassLedger first = load();
        TestProgressListener l = first.recording(TestProgressListener.noop());
        first.enter(UNIT);
        launch(l, "demo.FooTest", "unit", "FAILED");
        first.enter(INTEG);
        launch(l, "demo.FooTest", "integ", "SUCCESSFUL");
        // Cancelled later in the second launch: result stays null and the step stores the ledger as is.
        first.store(cache, TASK, STAMP);

        TestClassLedger rerun = load();
        assertThat(rerun.enter(UNIT))
                .as("FooTest#unit failed under this stamp; the re-run must run FooTest in its launch")
                .isEmpty();
        assertThat(rerun.enter(INTEG)).containsExactly("demo.FooTest");
    }

    @Test
    void the_left_out_classes_count_once_per_launch_with_each_launch_s_own_tests() {
        TestClassLedger first = load();
        TestProgressListener l = first.recording(TestProgressListener.noop());
        first.enter(UNIT);
        launch(l, "demo.FooTest", "unit", "SUCCESSFUL");
        first.enter(INTEG);
        launch(l, "demo.FooTest", "integ", "SUCCESSFUL");
        first.storeUnlessSuiteRed(cache, TASK, STAMP, new TestSummary(2, 2, 0, 0, List.of()));

        TestClassLedger rerun = load();
        rerun.enter(UNIT);
        rerun.enter(INTEG);
        TestSummary none = new TestSummary(0, 0, 0, 0, List.of());
        assertThat(rerun.withSettled(none).total())
                .as("both launches' FooTest methods count; the second launch no longer overwrites the first")
                .isEqualTo(2);
    }
}
