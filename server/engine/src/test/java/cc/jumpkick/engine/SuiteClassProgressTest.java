// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.TaskNames;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class SuiteClassProgressTest {

    @Test
    void a_suite_with_no_recorded_class_reports_no_progress_rather_than_done_after_its_first_class() {
        SuiteClassProgress progress = SuiteClassProgress.of(
                "/nowhere/never-built-" + System.nanoTime(), TaskNames.suiteStep("all_integration"));

        assertThat(progress.finished("a.FirstIT", 600)).isEqualTo(SuiteClassProgress.UNKNOWN);
        assertThat(progress.finished("a.SecondIT", 900)).isEqualTo(SuiteClassProgress.UNKNOWN);
    }

    /** Nine short classes side by side, then one as long as all of them alone: the curve shows it. */
    @Test
    void a_finished_suite_measures_how_its_clock_followed_its_class_share() {
        Map<String, Long> walls = new HashMap<>();
        for (int i = 0; i < 9; i++) walls.put("a.Short" + i, 1_000L);
        walls.put("a.Long", 1_000L);
        SuiteClassProgress progress = new SuiteClassProgress(walls);
        progress.started(10_000);
        for (int i = 0; i < 9; i++) progress.finished("a.Short" + i, 1_000, 11_000);
        progress.finished("a.Long", 10_000, 20_000);

        int[] curve = Objects.requireNonNull(progress.curve(20_000));
        assertThat(curve[8])
                .as("90% of the class walls in the first tenth of the wall")
                .isEqualTo(100);
        assertThat(new SuiteClassProgress(walls).curve(20_000))
                .as("never started")
                .isNull();
    }
}
