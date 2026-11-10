// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.test.TestProgressListener;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Which classes a re-run under the same stamp need not run, as the run-tests step reads and writes them. */
class TestClassLedgerTest {

    private static final String STAMP = "a".repeat(64);
    private static final String TASK = "run-tests:/ws/app/target/test-classes";

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
        assertThat(first.settledClasses()).as("nothing recorded yet").isEmpty();
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
        assertThat(next.settledClasses()).containsExactly("demo.OkTest");
        assertThat(TestClassLedger.load(cache, "b".repeat(64), "g:app", "integration")
                        .settledClasses())
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
        first.store(cache, TASK, STAMP);

        assertThat(load().settledClasses())
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
        second.store(cache, TASK, STAMP);

        assertThat(load().settledClasses()).containsExactly("demo.OkTest", "demo.RedTest");
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
        List<String> settled = next.settledClasses();
        TestSummary rerun = new TestSummary(1, 1, 0, 0, 1, List.of(), Map.of(), 1);
        TestSummary whole = next.withSettled(rerun, settled);
        assertThat(whole.total()).isEqualTo(5);
        assertThat(whole.succeeded()).isEqualTo(4);
        assertThat(whole.skipped()).isEqualTo(1);
        assertThat(whole.failed()).isZero();
        assertThat(whole.classes()).isEqualTo(2);
        assertThat(next.withSettled(rerun, List.of())).isSameAs(rerun);
    }

    @Test
    void nothing_is_stored_without_a_stamp_or_without_a_finished_class() {
        TestClassLedger ledger = load();
        ledger.store(cache, TASK, STAMP);
        assertThat(load().settledClasses()).isEmpty();

        TestProgressListener l = ledger.recording(TestProgressListener.noop());
        run(l, "demo.OkTest", "SUCCESSFUL");
        ledger.store(cache, TASK, null);
        assertThat(load().settledClasses())
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
        assertThat(load().settledClasses()).containsExactly("demo.OkTest");
    }
}
