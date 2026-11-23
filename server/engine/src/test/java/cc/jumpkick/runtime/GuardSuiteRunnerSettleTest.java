// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.eval.GuardSuites;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A suite JVM that runs out of heap leaves the lines of the guards it finished in the side file,
 * and the launcher's heap retry runs the class again into the same file: the report the lane reads
 * holds each guard once, with the retry's verdict, and counts guards, not lines.
 */
class GuardSuiteRunnerSettleTest {

    private static String line(String id, String outcome) {
        return "{\"id\":\"" + id + "\",\"source\":\"fx.Suite#" + id + "\",\"outcome\":\"" + outcome
                + "\",\"violations\":[]}";
    }

    @Test
    void a_heap_retry_replaces_the_lines_of_the_fork_that_died(@TempDir Path dir) throws Exception {
        Path report = GuardSuites.report(dir.resolve("target"));
        Files.createDirectories(report.getParent());
        Path part = report.resolveSibling(report.getFileName() + ".part");
        List<String> lines = new ArrayList<>();
        // the first fork finished two of three guards, the second threw on the dying heap
        lines.add(line("a", "ok"));
        lines.add(line("b", "threw"));
        // the retry ran all three
        lines.add(line("a", "ok"));
        lines.add(line("b", "ok"));
        lines.add(line("c", "ok"));
        Files.write(part, lines);

        long reported = GuardSuiteRunner.settle(part, report);

        assertThat(reported).as("guards reported, for junit.log").isEqualTo(3);
        assertThat(Files.readAllLines(report))
                .as("one line per guard, in the order the guards first reported")
                .containsExactly(line("a", "ok"), line("b", "ok"), line("c", "ok"));
        assertThat(part).doesNotExist();
    }

    @Test
    void a_run_without_a_side_file_leaves_no_report(@TempDir Path dir) throws Exception {
        Path report = Files.createDirectories(dir.resolve("guard")).resolve("report.jsonl");
        Files.writeString(report, line("stale", "ok") + "\n");

        assertThat(GuardSuiteRunner.settle(report.resolveSibling("report.jsonl.part"), report))
                .isZero();
        assertThat(report).doesNotExist();
    }

    @Test
    void a_line_naming_no_guard_is_kept_for_the_evaluator_to_report() {
        assertThat(GuardSuiteRunner.lastLinePerGuard(List.of(line("a", "ok"), "{\"id\":\"b\",\"outc", line("a", "ok"))))
                .containsExactly(line("a", "ok"), "{\"id\":\"b\",\"outc");
    }
}
