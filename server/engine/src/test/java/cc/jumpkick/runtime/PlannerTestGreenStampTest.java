// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.TestStamp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a finished run leaves behind. A green marker is what lets every later build skip the suite,
 * so it is stored only for evidence that tests passed. A run that found nothing to run gets a
 * marker of its own, so a module of helpers or tag-excluded tests does not fork a discovery JVM on
 * every build; a crashed discovery earns nothing.
 */
class PlannerTestGreenStampTest {

    @Test
    void a_run_that_discovered_no_test_in_a_module_with_test_sources_earns_a_no_tests_stamp() {
        assertThat(PlannerTest.stampFor(new TestSummary(0, 0, 0, 0, List.of()), true))
                .isEqualTo(PlannerTest.Stamp.NO_TESTS);
    }

    @Test
    void a_module_without_test_sources_stamps_its_empty_run_green() {
        assertThat(PlannerTest.stampFor(new TestSummary(0, 0, 0, 0, List.of()), false))
                .isEqualTo(PlannerTest.Stamp.GREEN);
    }

    @Test
    void a_passing_run_with_tests_is_stamped_green_and_a_failing_one_not_at_all() {
        assertThat(PlannerTest.stampFor(new TestSummary(3, 2, 0, 1, List.of()), true))
                .isEqualTo(PlannerTest.Stamp.GREEN);
        var failure = new TestFailureInfo("ex:m", "", "C", "m()", "AssertionError", "nope", "");
        assertThat(PlannerTest.stampFor(new TestSummary(1, 0, 1, 0, List.of(failure)), true))
                .isEqualTo(PlannerTest.Stamp.NONE);
    }

    /** A discovery JVM that died before naming a class reports a failure with no test behind it. */
    @Test
    void a_discovery_crash_earns_no_stamp_of_any_kind() {
        var crash = new TestFailureInfo("(test run)", "", "", "(test run)", "", "exit 1", "");
        assertThat(PlannerTest.stampFor(new TestSummary(0, 0, 1, 0, List.of(crash)), true))
                .isEqualTo(PlannerTest.Stamp.NONE);
    }

    /** The no-tests marker skips like a green one but says what it is. */
    @Test
    void the_no_tests_marker_is_a_skip_that_reads_as_no_tests() {
        ActionCache.ActionRecord noTests = record(TestStamp.noTestsOutcome());
        ActionCache.ActionRecord green = record(TestStamp.outcome(3, 3, 0, 0));
        ActionCache.ActionRecord red = record(TestStamp.outcome(3, 2, 0, 1));

        assertThat(TestStamp.green(noTests))
                .as("nothing failed: the next build skips the fork")
                .isTrue();
        assertThat(TestStamp.noTests(noTests)).isTrue();
        assertThat(TestStamp.noTests(green)).isFalse();
        assertThat(TestStamp.noTests(red)).isFalse();
        assertThat(TestStamp.green(red)).isFalse();
        assertThat(Objects.requireNonNull(PlannerTest.stampedSummary(noTests)).total())
                .isZero();
    }

    /** The marker is keyed by the test sources, so the test a later edit adds runs. */
    @Test
    void a_new_test_source_busts_the_no_tests_stamp(@TempDir Path dir) throws IOException {
        Path helper = Files.writeString(dir.resolve("Fixtures.java"), "class Fixtures {}");
        Path mainClasses = Files.createDirectories(dir.resolve("classes/main"));
        Path lock = Files.writeString(dir.resolve("jk-lock.toml"), "v=1");

        String helpersOnly = TestStamp.computeKey(List.of(helper), mainClasses, List.of(), lock, List.of(), List.of());
        Path added = Files.writeString(dir.resolve("FooTest.java"), "class FooTest { void t() {} }");
        String withTest =
                TestStamp.computeKey(List.of(helper, added), mainClasses, List.of(), lock, List.of(), List.of());

        assertThat(helpersOnly).isNotNull().isNotEqualTo(withTest);
    }

    private static ActionCache.ActionRecord record(Map<String, String> outputs) {
        return new ActionCache.ActionRecord("run-tests", "key", Map.of(), outputs, Map.of(), Set.of());
    }
}
