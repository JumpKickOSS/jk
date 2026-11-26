// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CentralBudgetTest {

    @Test
    void the_summary_names_the_file_count_and_size() {
        assertThat(CentralBudget.summary(16, 1_234_567L)).isEqualTo("Central bundle: 16 files, 1.2 MB");
        assertThat(CentralBudget.summary(1, 2_500L)).isEqualTo("Central bundle: 1 file, 2.5 KB");
    }

    @Test
    void a_release_within_the_percentile_draws_no_warning() {
        assertThat(CentralBudget.warnings(CentralBudget.P90_FILES, 78_000_000L)).isEmpty();
    }

    @Test
    void passing_either_limit_warns_in_two_short_lines() {
        assertThat(CentralBudget.warnings(CentralBudget.P90_FILES + 1, 1_000L)).hasSize(2);
        assertThat(CentralBudget.warnings(16, 78_000_001L))
                .containsExactly(
                        "this release alone passes Central's 90th percentile (1,167 files, 78 MB)",
                        "check this month's usage at https://central.sonatype.com/publishing/usage")
                .allSatisfy(line -> assertThat(line.length() + 4).isLessThanOrEqualTo(78));
    }
}
