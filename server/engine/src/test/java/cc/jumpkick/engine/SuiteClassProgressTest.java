// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.TaskNames;
import org.junit.jupiter.api.Test;

class SuiteClassProgressTest {

    @Test
    void a_suite_with_no_recorded_class_reports_no_progress_rather_than_done_after_its_first_class() {
        SuiteClassProgress progress = SuiteClassProgress.of(
                "/nowhere/never-built-" + System.nanoTime(), TaskNames.suiteStep("all_integration"));

        assertThat(progress.finished("a.FirstIT", 600)).isEqualTo(SuiteClassProgress.UNKNOWN);
        assertThat(progress.finished("a.SecondIT", 900)).isEqualTo(SuiteClassProgress.UNKNOWN);
    }
}
