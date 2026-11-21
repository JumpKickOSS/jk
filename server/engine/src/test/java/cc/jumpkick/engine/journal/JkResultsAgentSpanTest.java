// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.TaskNames;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A workspace test headline names how many modules its count spans, and how many prerequisites
 * {@code --exclusive} left untested, as the terminal's line does.
 */
class JkResultsAgentSpanTest {

    private static final BuildRecord.Task RAN = new BuildRecord.Task(TaskNames.RUN_TESTS, "TEST", "SUCCESS", 400, 0);
    private static final BuildRecord.Task FAILED = new BuildRecord.Task(TaskNames.RUN_TESTS, "TEST", "FAIL", 400, 0);
    private static final BuildRecord.Task REPLAYED =
            new BuildRecord.Task(TaskNames.RUN_TESTS, "TEST", "SKIPPED", 4, 0, TaskNames.TESTS_UP_TO_DATE);
    private static final BuildRecord.Task NOT_SELECTED =
            new BuildRecord.Task(TaskNames.RUN_TESTS, "TEST", "SKIPPED", 1, 0, TaskNames.TESTS_NOT_SELECTED);
    private static final BuildRecord.Task NO_TESTS =
            new BuildRecord.Task(TaskNames.RUN_TESTS, "TEST", "SKIPPED", 1, 0, "no tests to run");

    @Test
    void a_count_over_several_modules_names_how_many_ran_or_replayed_a_suite() {
        BuildRecord r = workspace(true, new BuildRecord.Tests(3265, 3265, 0, 0), RAN, REPLAYED, RAN, NO_TESTS);
        assertThat(JkResultsAgent.render(r)).isEqualTo("OK test jk · 3265 tests in 3 modules · 500ms\n");
    }

    @Test
    void a_failed_count_over_several_modules_names_them_before_its_skips() {
        BuildRecord r = workspace(false, new BuildRecord.Tests(40, 37, 1, 2), FAILED, RAN);
        assertThat(JkResultsAgent.render(r)).startsWith("FAIL test m0 · 1 of 40 failed in 2 modules, 2 skipped");
    }

    @Test
    void one_module_s_count_names_no_span() {
        BuildRecord r = workspace(true, new BuildRecord.Tests(15, 15, 0, 0), RAN, NO_TESTS);
        assertThat(JkResultsAgent.render(r)).isEqualTo("OK test jk · 15 tests · 500ms\n");
    }

    @Test
    void an_exclusive_run_says_how_many_prerequisites_were_not_tested() {
        BuildRecord r =
                workspace(true, new BuildRecord.Tests(15, 15, 0, 0), RAN, NOT_SELECTED, NOT_SELECTED, NOT_SELECTED);
        assertThat(JkResultsAgent.render(r))
                .isEqualTo("OK test jk · 15 tests · 3 prerequisites not tested (--exclusive) · 500ms\n");
        assertThat(JkResultsAgent.upToDate(r))
                .isEqualTo("OK test jk · 15 tests · 3 prerequisites not tested (--exclusive) · 500ms · up to date\n");
    }

    @Test
    void one_prerequisite_not_tested_is_singular() {
        BuildRecord r = workspace(true, new BuildRecord.Tests(2, 2, 0, 0), RAN, NOT_SELECTED);
        assertThat(JkResultsAgent.render(r)).contains(" · 1 prerequisite not tested (--exclusive) · ");
    }

    /** A {@code jk test} over the workspace {@code jk}, one module per run-tests step. */
    private static BuildRecord workspace(boolean success, BuildRecord.Tests tests, BuildRecord.Task... runTests) {
        List<BuildRecord.Module> modules = new ArrayList<>();
        for (int i = 0; i < runTests.length; i++) {
            boolean ok = !"FAIL".equals(runTests[i].status());
            modules.add(
                    new BuildRecord.Module("cc.jumpkick:m" + i, "/ws/m" + i, ok, ok ? 0 : 4, 10, List.of(runTests[i])));
        }
        return new BuildRecord(
                "id",
                1,
                BuildRecord.SCHEMA,
                "test",
                "/ws",
                "cc.jumpkick:jk",
                "pid",
                1_000,
                1_500,
                500,
                success,
                false,
                success ? 0 : 4,
                "0.14.0",
                tests,
                modules,
                List.of(),
                List.of(),
                "cli",
                null,
                null,
                null,
                false,
                null,
                7,
                null,
                List.of());
    }
}
