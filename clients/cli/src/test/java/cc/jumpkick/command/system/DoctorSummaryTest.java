// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.command.system.DoctorCommand.Check;
import cc.jumpkick.command.system.DoctorCommand.Status;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The line under doctor's rows counts the rows: with no tool installs it said {@code 0 healthy}
 * under a column of ok rows, since it counted the tool installs only.
 */
class DoctorSummaryTest {

    private static final List<Check> ROWS = List.of(
            new Check(Status.OK, "config", "parses"),
            new Check(Status.OK, "engine", "answering"),
            new Check(Status.WARN, "lock", "no jk-lock.toml"),
            new Check(Status.OK, "jdk", "temurin-25"));

    @Test
    void with_no_tool_installs_the_summary_counts_the_checks_and_says_nothing_of_tools() {
        String summary = DoctorSummary.line(ROWS, DoctorCommand.Tally.of(List.of()), n -> n);

        assertThat(summary).isEqualTo("3 ok, 1 warning");
    }

    @Test
    void tool_installs_follow_the_checks_under_their_own_name() {
        List<Check> rows = List.of(
                new Check(Status.OK, "config", "parses"),
                new Check(Status.FAIL, "engine", "not answering"),
                new Check(Status.WARN, "lock", "stale"),
                new Check(Status.WARN, "m2", "drifted"));
        DoctorCommand.Tally tools = new DoctorCommand.Tally(3, 1, 0, 0, 2, 0);

        assertThat(DoctorSummary.line(rows, tools, n -> "<" + n + ">"))
                .isEqualTo("<1> ok, <2> warnings, <1> failed · tools: <3> healthy, <1> pruned, <2> recorded");
    }
}
