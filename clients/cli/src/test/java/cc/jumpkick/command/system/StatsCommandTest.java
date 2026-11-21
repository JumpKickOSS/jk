// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class StatsCommandTest {

    private static final String ALL = "{\"type\":\"stats\",\"scope\":\"all\",\"runs\":12,\"savedMillis\":125000,"
            + "\"estimatedUncachedMillis\":300000,\"coveredSkips\":3,\"estimatedSkips\":4,\"stepsSkipped\":40,"
            + "\"suitesSkipped\":2,\"testClassesNotRerun\":6,\"graphCalls\":9,\"renames\":1,\"edits\":3,"
            + "\"guardViolations\":2,\"guardStops\":1}";

    @Test
    void days_are_a_whole_number_of_zero_or_more_and_zero_reads_all_the_history_kept() {
        assertThat(StatsCommand.parseDays(" 30 ")).isEqualTo(30);
        assertThat(StatsCommand.parseDays("0")).isZero();
        assertThat(StatsCommand.parseDays("-1")).isNull();
        assertThat(StatsCommand.parseDays("7d")).isNull();
        assertThat(StatsCommand.since(0, 10 * 86_400_000L)).isZero();
        assertThat(StatsCommand.since(7, 10 * 86_400_000L)).isEqualTo(3 * 86_400_000L);
        assertThat(StatsCommand.period(1)).isEqualTo("in the last day");
        assertThat(StatsCommand.period(7)).isEqualTo("in the last 7 days");
        assertThat(StatsCommand.period(0)).isEqualTo("in all the history kept");
    }

    @Test
    void the_time_saved_says_it_is_an_estimate_and_how_much_was_measured() {
        assertThat(StatsCommand.saved(ALL)).isEqualTo("~2m5s (estimate, 75% measured)");
        assertThat(StatsCommand.saved("{\"savedMillis\":900}")).isEqualTo("~900ms (estimate)");
    }

    @Test
    void the_total_prints_every_figure_the_ledger_counts() {
        assertThat(StatsCommand.totalRows(ALL))
                .extracting(r -> r.get(0) + ": " + r.get(1))
                .containsExactly(
                        "Build and test time avoided: ~2m5s (estimate, 75% measured)",
                        "Steps served from the cache: 40",
                        "Test suites not re-run: 2",
                        "Test classes not re-run: 6",
                        "Code-graph questions answered: 9",
                        "Renames applied: 1",
                        "Dependency edits made: 3",
                        "Guard violations stopped: 2 in 1 run",
                        "Runs: 12");
    }

    @Test
    void a_project_row_is_named_by_its_coordinate_else_its_directory() {
        List<List<String>> rows = StatsCommand.projectRows(List.of(
                "{\"scope\":\"project\",\"coord\":\"g:app\",\"dir\":\"/ws/app\",\"savedMillis\":1500,\"stepsSkipped\":4}",
                "{\"scope\":\"project\",\"dir\":\"/tmp/loose\",\"graphCalls\":2}"));
        assertThat(rows)
                .containsExactly(
                        List.of("g:app", "1.5s", "4", "0", "0", "0", "0"),
                        List.of("loose", "0ms", "0", "2", "0", "0", "0"));
    }
}
