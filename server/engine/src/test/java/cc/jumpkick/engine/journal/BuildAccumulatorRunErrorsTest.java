// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A workspace run that failed on a run-level verdict records that verdict as an error row, so both
 * reports name it instead of a bare exit code.
 */
class BuildAccumulatorRunErrorsTest {

    private static final String WHY = "--class named 1 class the tag filter excluded: a.BenchTest [bench];"
            + " pass --include-tags bench (or a --profile that includes it) to run it";

    @Test
    void a_run_level_verdict_is_an_error_row_both_reports_name() {
        BuildAccumulator a = new BuildAccumulator("test", "/ws", "g:ws", "cli");
        a.addTask("/ws/fmt", "run-tests", "test", "SUCCESS", 900, 0);
        a.addRunErrors(List.of(WHY));

        BuildRecord r = a.toRecord(2_000, false, 9_500, "9.9", null);

        assertThat(r.success()).isFalse();
        assertThat(r.diagnostics()).singleElement().satisfies(d -> {
            assertThat(d.severity()).isEqualTo("error");
            assertThat(d.code()).isEqualTo("run-error");
            assertThat(d.message()).isEqualTo(WHY);
        });
        assertThat(JkResultsAgent.render(r)).startsWith("FAIL test ws · 9.5s — " + WHY + "\n");
        assertThat(JkResultsMarkdown.render(r)).contains("- " + WHY + "\n").doesNotContain("failed (exit");
    }

    @Test
    void an_error_the_run_already_recorded_is_not_repeated() {
        BuildAccumulator a = new BuildAccumulator("build", "/ws", "g:ws", "cli");
        a.addPreflightFailure("lock", 40, "no version of a:b satisfies 2.0");
        a.addRunErrors(List.of("no version of a:b satisfies 2.0", " "));

        BuildRecord r = a.toRecord(2_000, false, 100, "9.9", null);

        assertThat(r.diagnostics()).singleElement().satisfies(d -> assertThat(d.step())
                .isEqualTo("lock"));
    }
}
