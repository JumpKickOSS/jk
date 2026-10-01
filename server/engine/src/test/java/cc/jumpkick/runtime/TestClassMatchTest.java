// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.test.TestProgressListener;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** A {@code --class} that matched nothing: skipped per module, failed per run. */
class TestClassMatchTest {

    private static final TestSelection ORDERS = TestSelection.DEFAULT.withClasses(List.of("OrdersTest"));
    private static final TestSummary EMPTY = new TestSummary(0, 0, 0, 0, List.of());

    @Test
    void an_empty_summary_under_class_patterns_is_a_no_match() {
        assertThat(TestClassMatch.nothingMatched(ORDERS, false, EMPTY)).isTrue();
    }

    @Test
    void a_summary_with_tests_or_failures_is_not_a_no_match() {
        assertThat(TestClassMatch.nothingMatched(ORDERS, false, new TestSummary(3, 3, 0, 0, List.of())))
                .isFalse();
        var crashed = new TestSummary(
                1, 0, 1, 0, List.of(new TestFailureInfo("m", "", "", "(test run)", "", "runner exited 1", "")));
        assertThat(TestClassMatch.nothingMatched(ORDERS, false, crashed))
                .as("a suite that died before its first test is its own failure")
                .isFalse();
    }

    @Test
    void no_patterns_or_an_exact_class_list_never_judge() {
        assertThat(TestClassMatch.nothingMatched(TestSelection.DEFAULT, false, EMPTY))
                .isFalse();
        assertThat(TestClassMatch.nothingMatched(ORDERS, true, EMPTY))
                .as("--affected names exact classes; the patterns are not what selected them")
                .isFalse();
    }

    @Test
    void the_skip_label_and_the_failure_name_the_patterns() {
        assertThat(TestClassMatch.skipLabel(List.of("OrdersTest", "*IT"), null))
                .isEqualTo("no classes matched --class OrdersTest, *IT — skipped");
        TestSummary failure = TestClassMatch.asFailure("acme:orders", ORDERS, null, null);
        assertThat(failure.failed()).isEqualTo(1);
        assertThat(failure.failures()).singleElement().satisfies(f -> {
            assertThat(f.module()).isEqualTo("acme:orders");
            assertThat(f.message()).isEqualTo("no test classes matched --class OrdersTest");
        });
    }

    @Test
    void when_the_tag_filter_dropped_the_named_class_the_failure_is_the_runners_warning() {
        TestSelection excluded = TestSelection.of(List.of(), true, List.of(), List.of("bench"))
                .withClasses(List.of("FormatThreadsBenchTest"));
        assertThat(TestClassMatch.noMatchMessage(excluded, TAG_EXCLUDED, null)).isEqualTo(TAG_EXCLUDED);
        assertThat(TestClassMatch.asFailure("acme:fmt", excluded, TAG_EXCLUDED, null)
                        .failures())
                .singleElement()
                .satisfies(f -> assertThat(f.message()).isEqualTo(TAG_EXCLUDED));
        assertThat(TestClassMatch.noMatchMessage(excluded, null, null))
                .as("the runner dropped nothing: the patterns matched no class, and that is all it says")
                .isEqualTo("no test classes matched --class FormatThreadsBenchTest");
    }

    @Test
    void a_class_another_suite_owns_names_that_suite_first_and_keeps_the_tag_reason() {
        TestSelection fooIt = TestSelection.DEFAULT.withClasses(List.of("FooIT"));
        String hint = "FooIT is in the integration suite (jk test --suite integration --class FooIT)";
        assertThat(TestClassMatch.skipLabel(List.of("FooIT"), hint))
                .isEqualTo("no classes matched --class FooIT — skipped; " + hint);
        assertThat(TestClassMatch.noMatchMessage(fooIt, null, hint))
                .isEqualTo("no test classes matched --class FooIT — " + hint);
        assertThat(TestClassMatch.asFailure("acme:app", fooIt, TAG_EXCLUDED, hint)
                        .failures())
                .singleElement()
                .satisfies(f -> assertThat(f.message())
                        .isEqualTo("no test classes matched --class FooIT — " + hint + "; " + TAG_EXCLUDED));

        Session session = Session.defaults().withTestSelection(fooIt);
        BuildPlan hinted = BuildPlan.builder("module")
                .stateKeys(BuildPlanner.TEST_RESULT, BuildPlanner.CLASS_SUITE_HINT)
                .addTask(Task.builder("run-tests")
                        .ticks(1)
                        .execute(ctx -> ctx.put(BuildPlanner.CLASS_SUITE_HINT, hint))
                        .build())
                .build();
        hinted.run();
        assertThat(TestClassMatch.runWideVerdict(session, false, List.of(plan(null), hinted)))
                .isEqualTo("no test classes matched --class FooIT — " + hint);
    }

    @Test
    void a_module_whose_tag_filter_dropped_the_named_class_is_the_run_wide_verdict() {
        Session session = Session.defaults().withTestSelection(ORDERS);
        BuildPlan dropped = BuildPlan.builder("module")
                .stateKeys(BuildPlanner.TEST_RESULT, BuildPlanner.TAG_EXCLUDED)
                .addTask(Task.builder("run-tests")
                        .ticks(1)
                        .execute(ctx -> ctx.put(BuildPlanner.TAG_EXCLUDED, TAG_EXCLUDED))
                        .build())
                .build();
        dropped.run();
        assertThat(TestClassMatch.runWideVerdict(session, false, List.of(plan(null), dropped)))
                .isEqualTo(TAG_EXCLUDED);
    }

    @Test
    void the_capture_keeps_the_runners_tag_excluded_warning_and_forwards_every_warning() {
        List<String> forwarded = new ArrayList<>();
        var capture = new TagExcludedCapture(new TestProgressListener() {
            @Override
            public void onWarning(String code, String message) {
                forwarded.add(code);
            }
        });
        capture.onWarning("no-test-classes", "nothing here");
        assertThat(capture.message()).isNull();
        capture.onWarning("tag-excluded", TAG_EXCLUDED);
        assertThat(capture.message()).isEqualTo(TAG_EXCLUDED);
        assertThat(forwarded).containsExactly("no-test-classes", "tag-excluded");
    }

    private static final String TAG_EXCLUDED = "--class named 1 class the tag filter excluded:"
            + " a.FormatThreadsBenchTest [bench]; pass --include-tags bench (or a --profile that includes it)"
            + " to run it";

    @Test
    void the_run_fails_only_when_no_module_matched() {
        Session session = Session.defaults().withTestSelection(ORDERS);
        BuildPlan skipped = plan(null);
        BuildPlan matched = plan(new TestSummary(2, 2, 0, 0, List.of()));

        assertThat(TestClassMatch.runWideVerdict(session, false, List.of(skipped, skipped)))
                .isEqualTo("no test classes matched --class OrdersTest");
        assertThat(TestClassMatch.runWideVerdict(session, false, List.of(skipped, matched)))
                .as("one module matched; the others skipping is the point")
                .isNull();
    }

    @Test
    void a_replayed_green_stamp_counts_as_a_match() {
        Session session = Session.defaults().withTestSelection(ORDERS);
        // The stamp carries the class filter, so a green marker for these patterns once matched.
        BuildPlan replayed = plan(new TestSummary(2, 2, 0, 0, List.of()));
        assertThat(TestClassMatch.runWideVerdict(session, false, List.of(replayed)))
                .isNull();
    }

    @Test
    void runs_that_select_no_classes_or_run_no_suites_have_no_verdict() {
        assertThat(TestClassMatch.runWideVerdict(Session.defaults(), false, List.of(plan(null))))
                .isNull();
        Session orders = Session.defaults().withTestSelection(ORDERS);
        assertThat(TestClassMatch.runWideVerdict(orders, true, List.of(plan(null))))
                .as("--skip-tests ran no suite to match against")
                .isNull();
        assertThat(TestClassMatch.runWideVerdict(orders.withAffected(true), false, List.of(plan(null))))
                .as("--affected chose the classes, not the patterns")
                .isNull();
        Session scripts = Session.defaults()
                .withTestSelection(TestSelection.of(
                        List.of(), false, List.of(), List.of(), false, true, true, false, List.of("OrdersTest")));
        assertThat(TestClassMatch.runWideVerdict(scripts, false, List.of(plan(null))))
                .as("--scripts-only runs guard scripts, no JUnit")
                .isNull();
    }

    /** A finished module plan whose run-tests step left {@code result} behind, or nothing when skipped. */
    private static BuildPlan plan(@Nullable TestSummary result) {
        BuildPlan plan = BuildPlan.builder("module")
                .stateKeys(BuildPlanner.TEST_RESULT)
                .addTask(Task.builder("run-tests")
                        .ticks(1)
                        .execute(ctx -> {
                            if (result != null) ctx.put(BuildPlanner.TEST_RESULT, result);
                        })
                        .build())
                .build();
        plan.run();
        return plan;
    }
}
