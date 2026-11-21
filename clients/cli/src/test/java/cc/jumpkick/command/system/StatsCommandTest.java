// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class StatsCommandTest {

    private static final String ALL = "{\"type\":\"stats\",\"scope\":\"all\",\"runs\":12,\"savedMillis\":125000,"
            + "\"estimatedUncachedMillis\":300000,\"coveredSkips\":3,\"estimatedSkips\":4,\"stepsSkipped\":40,"
            + "\"suitesSkipped\":2,\"testClassesNotRerun\":6,\"graphCalls\":9,\"refactorings\":1,\"edits\":3,"
            + "\"guardViolations\":2,\"guardStops\":1}";

    @Test
    void since_takes_a_duration_back_from_now_a_date_or_all() {
        long now = 10 * 86_400_000L;
        ZoneId utc = ZoneOffset.UTC;
        assertThat(StatsCommand.period(StatsCommand.DEFAULT_SINCE, now, utc))
                .isEqualTo(new StatsCommand.Period(3 * 86_400_000L, "in the last 7 days"));
        assertThat(StatsCommand.period(" 1d ", now, utc))
                .isEqualTo(new StatsCommand.Period(9 * 86_400_000L, "in the last day"));
        assertThat(StatsCommand.period("12h", now, utc))
                .isEqualTo(new StatsCommand.Period(now - 12 * 3_600_000L, "in the last 12 hours"));
        assertThat(StatsCommand.period("2w", now, utc))
                .as("a period older than every run starts at 0")
                .isEqualTo(new StatsCommand.Period(0, "in the last 2 weeks"));
        assertThat(StatsCommand.period("1970-01-03", now, utc))
                .isEqualTo(new StatsCommand.Period(2 * 86_400_000L, "since 1970-01-03"));
        assertThat(StatsCommand.period("1970-01-03", now, ZoneOffset.ofHours(1)))
                .as("a date starts at midnight where the user is")
                .isEqualTo(new StatsCommand.Period(2 * 86_400_000L - 3_600_000L, "since 1970-01-03"));
        assertThat(StatsCommand.period("all", now, utc))
                .isEqualTo(new StatsCommand.Period(0, "in all the history kept"));
        assertThat(StatsCommand.period("7", now, utc))
                .as("a bare number has no unit")
                .isNull();
        assertThat(StatsCommand.period("0d", now, utc)).isNull();
        assertThat(StatsCommand.period("-1d", now, utc)).isNull();
        assertThat(StatsCommand.period("3m", now, utc)).isNull();
        assertThat(StatsCommand.period("2026-13-01", now, utc)).isNull();
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
                        "Refactorings applied: 1",
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
