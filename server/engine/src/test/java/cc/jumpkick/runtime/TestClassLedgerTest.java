// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TestClassResult;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.TestStamp;
import cc.jumpkick.test.SuiteFilter;
import cc.jumpkick.test.TestProgressListener;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Which classes a re-run under the same stamp need not run, as the run-tests step reads and writes them. */
class TestClassLedgerTest {

    private static final String STAMP = "a".repeat(64);
    private static final String TASK = "run-tests@" + "c".repeat(12); // the shape ActionKey.qualifiedTaskId gives
    private static final String ONE = SuiteResources.ONE_LAUNCH;

    @TempDir
    Path tmp;

    private ActionCache cache;

    @BeforeEach
    void setUp() {
        cache = new ActionCache(new Cas(tmp.resolve("cas")), tmp.resolve("actions"));
    }

    private static String cls(String name) {
        int dollar = name.indexOf('$');
        if (dollar < 0) return "[engine:junit-jupiter]/[class:" + name + "]";
        return "[engine:junit-jupiter]/[class:" + name.substring(0, dollar) + "]/[nested-class:"
                + name.substring(dollar + 1) + "]";
    }

    private static String test(String name, String method) {
        return cls(name) + "/[method:" + method + "()]";
    }

    /** One class's run as the runner reports it: each test, then the class container. */
    private static void run(TestProgressListener l, String name, String... statuses) {
        for (int i = 0; i < statuses.length; i++) {
            if ("SKIPPED".equals(statuses[i])) l.onTestSkipped(test(name, "t" + i), "t" + i, "off", true, true, 0);
            else l.onTestFinished(test(name, "t" + i), "t" + i, statuses[i], true, true, 5, 0);
        }
        l.onTestFinished(cls(name), name, "SUCCESSFUL", false, true, 10, 0);
    }

    private TestClassLedger load() {
        return TestClassLedger.load(cache, STAMP, "g:app", "integration");
    }

    @Test
    void a_red_run_leaves_its_green_classes_settled_for_the_next_run_under_the_same_stamp() {
        TestClassLedger first = load();
        assertThat(first.settledClasses(ONE)).as("nothing recorded yet").isEmpty();
        List<String> seen = new ArrayList<>();
        TestProgressListener l = first.recording(new TestProgressListener() {
            @Override
            public void onTestFinished(
                    String id, String display, String status, boolean isTest, boolean wasStatic, long ms, int w) {
                seen.add(id);
            }
        });
        run(l, "demo.OkTest", "SUCCESSFUL", "SUCCESSFUL", "SKIPPED");
        run(l, "demo.RedTest", "SUCCESSFUL", "FAILED");
        first.store(cache, TASK, STAMP);
        assertThat(seen).as("every event still reaches the step's own listener").hasSize(6);

        TestClassLedger next = load();
        assertThat(next.settledClasses(ONE)).containsExactly("demo.OkTest");
        assertThat(TestClassLedger.load(cache, "b".repeat(64), "g:app", "integration")
                        .settledClasses(ONE))
                .as("another stamp vouches for nothing")
                .isEmpty();
    }

    @Test
    void a_class_is_settled_only_when_it_and_every_nested_class_passed() {
        TestClassLedger first = load();
        TestProgressListener l = first.recording(TestProgressListener.noop());
        run(l, "demo.OuterTest$Inner", "FAILED");
        run(l, "demo.OuterTest", "SUCCESSFUL");
        run(l, "demo.OtherTest$Inner", "SUCCESSFUL");
        run(l, "demo.OtherTest", "SUCCESSFUL");
        run(l, "demo.OnlyNestedTest$Inner", "SUCCESSFUL");
        assertThat(first.outcomes())
                .as("a nested class's failure is its outer class's")
                .containsEntry("demo.OuterTest", TestFlakes.Outcome.FAILED)
                .containsEntry("demo.OtherTest", TestFlakes.Outcome.PASSED);
        first.store(cache, TASK, STAMP);

        assertThat(load().settledClasses(ONE))
                .as("a failing nested class unsettles its outer class; a nested class alone settles nothing")
                .containsExactly("demo.OtherTest");
    }

    @Test
    void the_newer_outcome_wins_and_a_red_class_that_passes_unchanged_is_reported() {
        TestClassLedger first = load();
        TestProgressListener l1 = first.recording(TestProgressListener.noop());
        run(l1, "demo.OkTest", "SUCCESSFUL");
        run(l1, "demo.RedTest", "FAILED");
        first.store(cache, TASK, STAMP);

        TestClassLedger second = load();
        TestProgressListener l2 = second.recording(TestProgressListener.noop());
        run(l2, "demo.RedTest", "SUCCESSFUL");
        assertThat(second.passedOnRerun()).containsExactly("demo.RedTest");
        assertThat(first.outcomes())
                .containsExactly(
                        Map.entry("demo.OkTest", TestFlakes.Outcome.PASSED),
                        Map.entry("demo.RedTest", TestFlakes.Outcome.FAILED));
        assertThat(second.outcomes()).containsExactly(Map.entry("demo.RedTest", TestFlakes.Outcome.PASSED_ON_RERUN));
        second.store(cache, TASK, STAMP);

        assertThat(load().settledClasses(ONE)).containsExactly("demo.OkTest", "demo.RedTest");
    }

    @Test
    void a_class_red_under_the_stamp_is_sent_as_passed_on_a_rerun_when_it_passes() {
        TestClassLedger first = load();
        TestProgressListener l1 = first.recording(TestProgressListener.noop());
        run(l1, "demo.RedTest", "FAILED");
        run(l1, "demo.RedTest$Inner", "FAILED");
        run(l1, "demo.StillRedTest", "FAILED");
        first.store(cache, TASK, STAMP);

        TestClassLedger second = load();
        List<TestClassResult> sent = new ArrayList<>();
        TestProgressListener l2 = second.recording(TestSupport.bridgeListener(
                sending(sent), 1, false, "g:app", null, null, SuiteFilter.NONE, TestTier.UNKNOWN, second::marked));
        run(l2, "demo.RedTest", "SUCCESSFUL");
        run(l2, "demo.RedTest$Inner", "SUCCESSFUL");
        run(l2, "demo.StillRedTest", "FAILED");
        run(l2, "demo.NewTest", "SUCCESSFUL");

        assertThat(sent)
                .extracting(TestClassResult::className, TestClassResult::passedOnRerun)
                .containsExactly(
                        tuple("demo.RedTest", true),
                        tuple("demo.RedTest$Inner", true),
                        tuple("demo.StillRedTest", false),
                        tuple("demo.NewTest", false));
    }

    @Test
    void the_settled_classes_count_toward_the_suite_as_passed() {
        TestClassLedger first = load();
        TestProgressListener l = first.recording(TestProgressListener.noop());
        run(l, "demo.OkTest", "SUCCESSFUL", "SUCCESSFUL", "SKIPPED");
        run(l, "demo.OkTest$Inner", "SUCCESSFUL");
        run(l, "demo.RedTest", "FAILED");
        first.store(cache, TASK, STAMP);

        TestClassLedger next = load();
        TestSummary rerun = new TestSummary(1, 1, 0, 0, 1, List.of(), Map.of(), 1);
        assertThat(next.withSettled(rerun))
                .as("no launch has left anything out yet")
                .isSameAs(rerun);
        assertThat(next.enter(ONE)).containsExactly("demo.OkTest");
        TestSummary whole = next.withSettled(rerun);
        assertThat(whole.total()).isEqualTo(5);
        assertThat(whole.succeeded()).isEqualTo(4);
        assertThat(whole.skipped()).isEqualTo(1);
        assertThat(whole.failed()).isZero();
        assertThat(whole.classes()).isEqualTo(2);
    }

    @Test
    void nothing_is_stored_without_a_stamp_or_without_a_finished_class() {
        TestClassLedger ledger = load();
        ledger.store(cache, TASK, STAMP);
        assertThat(load().settledClasses(ONE)).isEmpty();

        TestProgressListener l = ledger.recording(TestProgressListener.noop());
        run(l, "demo.OkTest", "SUCCESSFUL");
        ledger.store(cache, TASK, null);
        assertThat(load().settledClasses(ONE))
                .as("a stamp that failed open keys nothing")
                .isEmpty();
    }

    @Test
    void a_cancelled_run_keeps_its_classes_even_on_an_interrupted_thread() {
        TestClassLedger ledger = load();
        run(ledger.recording(TestProgressListener.noop()), "demo.OkTest", "SUCCESSFUL");
        Thread.currentThread().interrupt();
        try {
            ledger.store(cache, TASK, STAMP);
            assertThat(Thread.currentThread().isInterrupted())
                    .as("the interrupt is handed back")
                    .isTrue();
        } finally {
            Thread.interrupted();
        }
        assertThat(load().settledClasses(ONE)).containsExactly("demo.OkTest");
    }

    @Test
    void a_run_red_for_a_reason_no_class_owns_settles_nothing() {
        TestClassLedger first = load();
        TestProgressListener l = first.recording(TestProgressListener.noop());
        run(l, "demo.OkTest", "SUCCESSFUL", "SUCCESSFUL");
        run(l, "demo.OtherTest", "SUCCESSFUL");
        // Every class finished green, but a worker exited non-zero after DONE: one failure, no class.
        first.storeUnlessSuiteRed(cache, TASK, STAMP, new TestSummary(4, 3, 1, 0, List.of()));

        assertThat(load().settledClasses(ONE))
                .as("the next run under this stamp runs every class again")
                .isEmpty();
    }

    @Test
    void a_run_whose_failures_all_belong_to_red_classes_is_stored() {
        TestClassLedger first = load();
        TestProgressListener l = first.recording(TestProgressListener.noop());
        run(l, "demo.OkTest", "SUCCESSFUL");
        run(l, "demo.RedTest", "FAILED", "FAILED");
        first.storeUnlessSuiteRed(cache, TASK, STAMP, new TestSummary(3, 1, 2, 0, List.of()));
        assertThat(load().settledClasses(ONE)).containsExactly("demo.OkTest");

        // A failure beyond what the red class owns, such as a lost-tests shortfall row, settles nothing.
        TestClassLedger second = load();
        run(second.recording(TestProgressListener.noop()), "demo.RedTest", "SUCCESSFUL", "SUCCESSFUL");
        second.storeUnlessSuiteRed(cache, TASK, STAMP, new TestSummary(3, 2, 1, 0, List.of()));
        assertThat(load().settledClasses(ONE))
                .as("RedTest stays red until a run vouches for it, and the next run runs every class")
                .isEmpty();
    }

    @Test
    void a_run_red_beyond_its_classes_clears_what_an_earlier_green_run_settled() {
        TestClassLedger green = load();
        TestProgressListener l = green.recording(TestProgressListener.noop());
        run(l, "demo.OkTest", "SUCCESSFUL");
        run(l, "demo.DiesTest", "SUCCESSFUL");
        green.storeUnlessSuiteRed(cache, TASK, STAMP, new TestSummary(2, 2, 0, 0, List.of()));
        assertThat(load().settledClasses(ONE)).containsExactly("demo.DiesTest", "demo.OkTest");

        // A forced re-run under the same stamp: OkTest passes, the JVM dies inside DiesTest, whose
        // class never finishes, so the one failure belongs to no class.
        TestClassLedger forced = load();
        run(forced.recording(TestProgressListener.noop()), "demo.OkTest", "SUCCESSFUL");
        forced.storeUnlessSuiteRed(cache, TASK, STAMP, new TestSummary(2, 1, 1, 0, List.of()));

        assertThat(load().settledClasses(ONE))
                .as("the class the dead JVM was running is not settled by the green run before it")
                .isEmpty();
    }

    @Test
    void a_red_run_settled_under_another_key_clears_the_ledger_of_the_key_it_started_with() throws Exception {
        String started = STAMP;
        String settled = "b".repeat(64);
        TestClassLedger green = load();
        run(green.recording(TestProgressListener.noop()), "demo.FlakyTest", "SUCCESSFUL");
        green.storeUnlessSuiteRed(cache, TASK, started, new TestSummary(1, 1, 0, 0, List.of()));

        TestClassLedger red = load();
        run(red.recording(TestProgressListener.noop()), "demo.FlakyTest", "FAILED");
        red.finish(quiet(), cache, TASK, started, settled, new TestSummary(1, 0, 1, 0, List.of()));
        assertThat(load().settledClasses(ONE))
                .as("the key the red run started with settles nothing it once did")
                .isEmpty();
        assertThat(cache.lookup(started).map(TestStamp::green))
                .as("and holds a red marker")
                .contains(false);

        TestClassLedger unsettled = load();
        run(unsettled.recording(TestProgressListener.noop()), "demo.FlakyTest", "FAILED");
        unsettled.finish(quiet(), cache, TASK, started, null, new TestSummary(1, 0, 1, 0, List.of()));
        assertThat(load().settledClasses(ONE))
                .as("nor when its key moved and nothing was settled")
                .isEmpty();
    }

    /** A step context that keeps nothing. */
    private static TaskContext quiet() {
        return sending(new ArrayList<>());
    }

    /** A step context that keeps each finished class the step sends in {@code into}, and nothing else. */
    private static TaskContext sending(List<TestClassResult> into) {
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
            public void warn(String code, String message) {}

            @Override
            public void error(String code, String message) {}

            @Override
            public void testClass(TestClassResult result) {
                into.add(result);
            }

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
