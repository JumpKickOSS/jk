// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.run.TestSummary;
import java.util.List;
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
                .isEqualTo("skipped: no classes match --class OrdersTest, *IT");
        assertThat(TestClassMatch.noMatchMessage(ORDERS, null, null))
                .isEqualTo("no test classes matched --class OrdersTest");
    }

    @Test
    void when_the_tag_filter_dropped_the_named_class_the_failure_is_the_runners_warning() {
        TestSelection excluded = TestSelection.of(List.of(), true, List.of(), List.of("bench"))
                .withClasses(List.of("FormatThreadsBenchTest"));
        assertThat(TestClassMatch.noMatchMessage(excluded, TAG_EXCLUDED, null)).isEqualTo(TAG_EXCLUDED);
        assertThat(TestClassMatch.noMatchMessage(excluded, null, null))
                .as("the runner dropped nothing: the patterns matched no class, and that is all it says")
                .isEqualTo("no test classes matched --class FormatThreadsBenchTest");
    }

    @Test
    void a_class_another_suite_owns_names_that_suite_first_and_keeps_the_tag_reason() {
        TestSelection fooIt = TestSelection.DEFAULT.withClasses(List.of("FooIT"));
        String hint = "FooIT is in the integration suite (jk test -s integration -S FooIT)";
        assertThat(TestClassMatch.skipLabel(List.of("FooIT"), hint))
                .isEqualTo("skipped: no classes match --class FooIT; " + hint);
        assertThat(TestClassMatch.noMatchMessage(fooIt, null, hint))
                .isEqualTo("no test classes matched --class FooIT: " + hint);
        assertThat(TestClassMatch.noMatchMessage(fooIt, TAG_EXCLUDED, hint))
                .isEqualTo("no test classes matched --class FooIT: " + hint + "; " + TAG_EXCLUDED);

        Session session = Session.defaults().withTestSelection(fooIt);
        TestRun hinted = new TestRun(null, null, hint);
        assertThat(TestClassMatch.runWideVerdict(session, false, List.of(hinted)))
                .isEqualTo("no test classes matched --class FooIT: " + hint);
    }

    @Test
    void a_module_whose_tag_filter_dropped_the_named_class_is_the_run_wide_verdict() {
        Session session = Session.defaults().withTestSelection(ORDERS);
        TestRun dropped = new TestRun(null, TAG_EXCLUDED, null);
        assertThat(TestClassMatch.runWideVerdict(session, false, List.of(dropped)))
                .isEqualTo(TAG_EXCLUDED);
    }

    @Test
    void only_patterns_without_a_wildcard_name_their_classes() {
        assertThat(TestClassMatch.namesClasses(ORDERS)).isTrue();
        assertThat(TestClassMatch.namesClasses(TestSelection.DEFAULT.withClasses(List.of("a.OrdersIT#refund*"))))
                .as("a wildcard in the method half still names the class")
                .isTrue();
        assertThat(TestClassMatch.namesClasses(TestSelection.DEFAULT.withClasses(List.of("OrdersTest", "*IT"))))
                .isFalse();
        assertThat(TestClassMatch.namesClasses(TestSelection.DEFAULT)).isFalse();
    }

    private static final String TAG_EXCLUDED = "--class named 1 class the tag filter excluded:"
            + " a.FormatThreadsBenchTest [bench]; drop --exclude-tags bench"
            + " to run it";

    @Test
    void the_run_fails_only_when_no_module_matched() {
        Session session = Session.defaults().withTestSelection(ORDERS);
        TestRun matched = TestRun.of(new TestSummary(2, 2, 0, 0, List.of()));

        assertThat(TestClassMatch.runWideVerdict(session, false, List.of()))
                .as("modules that skipped leave no run")
                .isEqualTo("no test classes matched --class OrdersTest");
        assertThat(TestClassMatch.runWideVerdict(session, false, List.of(matched)))
                .as("one module matched; the others skipping is the point")
                .isNull();
    }

    @Test
    void a_replayed_green_stamp_counts_as_a_match() {
        Session session = Session.defaults().withTestSelection(ORDERS);
        // The stamp carries the class filter, so a green marker for these patterns once matched.
        TestRun replayed = TestRun.of(new TestSummary(2, 2, 0, 0, List.of()));
        assertThat(TestClassMatch.runWideVerdict(session, false, List.of(replayed)))
                .isNull();
    }

    @Test
    void runs_that_select_no_classes_or_run_no_suites_have_no_verdict() {
        assertThat(TestClassMatch.runWideVerdict(Session.defaults(), false, List.of()))
                .isNull();
        Session orders = Session.defaults().withTestSelection(ORDERS);
        assertThat(TestClassMatch.runWideVerdict(orders, true, List.of()))
                .as("--skip-tests ran no suite to match against")
                .isNull();
        assertThat(TestClassMatch.runWideVerdict(orders.withAffected(true), false, List.of()))
                .as("--affected chose the classes, not the patterns")
                .isNull();
        Session scripts = Session.defaults()
                .withTestSelection(TestSelection.of(
                        List.of(), false, List.of(), List.of(), true, true, false, List.of("OrdersTest")));
        assertThat(TestClassMatch.runWideVerdict(scripts, false, List.of()))
                .as("--scripts-only runs guard scripts, no JUnit")
                .isNull();
    }

    @Test
    void a_workspace_module_the_patterns_missed_skips_and_a_standalone_project_fails() {
        TestClassMatch.Verdict member = TestClassMatch.judge(ORDERS, false, null, null, true, EMPTY);
        assertThat(member.skipped()).isTrue();
        assertThat(member.noMatch()).isNull();

        TestClassMatch.Verdict alone = TestClassMatch.judge(ORDERS, false, null, null, false, EMPTY);
        assertThat(alone.skipped()).isFalse();
        assertThat(alone.result()).isNull();
        assertThat(alone.noMatch()).isEqualTo("no test classes matched --class OrdersTest");
    }

    @Test
    void a_run_that_matched_keeps_its_summary_and_warns_of_another_suites_class() {
        TestSummary ran = new TestSummary(3, 3, 0, 0, List.of());
        TestClassMatch.Verdict verdict =
                TestClassMatch.judge(ORDERS, false, null, "suite e2e owns OrdersTest", false, ran);
        assertThat(verdict.result()).isSameAs(ran);
        assertThat(verdict.noMatch()).isNull();
        assertThat(verdict.otherSuite()).isEqualTo("suite e2e owns OrdersTest");
    }

    @Test
    void a_named_class_the_tag_filter_dropped_fails_the_module_whatever_else_ran() {
        TestSelection named = TestSelection.of(List.of(), true, List.of(), List.of("bench"))
                .withClasses(List.of("FormatThreadsBenchTest"));
        TestSummary ran = new TestSummary(2, 2, 0, 0, List.of());

        TestClassMatch.Verdict some = TestClassMatch.judge(named, false, TAG_EXCLUDED, null, true, ran);
        assertThat(some.result()).isSameAs(ran);
        assertThat(some.noMatch()).isEqualTo(TAG_EXCLUDED);
        assertThat(some.skipped())
                .as("a dropped named class is judged here, not run-wide")
                .isFalse();

        TestClassMatch.Verdict none = TestClassMatch.judge(named, false, TAG_EXCLUDED, null, true, EMPTY);
        assertThat(none.result()).isNull();
        assertThat(none.noMatch()).isEqualTo(TAG_EXCLUDED);
    }
}
