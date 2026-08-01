// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.theme.Theme;
import org.junit.jupiter.api.Test;

/** Pure unit coverage for {@link ExplainCommand#buildTimeEstimate}. */
class ExplainCommandEstimateTest {

    @Test
    void fully_cached_build_time_is_under_one_second_not_unknown() {
        String label = TestAnsi.strip(ExplainCommand.buildTimeEstimate(0, true, Theme.active()));
        assertThat(label).isEqualTo("Build time estimate <1s");
        assertThat(label).doesNotContain("unknown");
    }

    @Test
    void dirty_with_no_eta_is_not_yet_measured() {
        String label = TestAnsi.strip(ExplainCommand.buildTimeEstimate(0, false, Theme.active()));
        assertThat(label).isEqualTo("Build time not yet measured");
        assertThat(label).doesNotContain("unknown");
    }

    @Test
    void sub_second_eta_formats_as_under_one_second() {
        String label = TestAnsi.strip(ExplainCommand.buildTimeEstimate(400, false, Theme.active()));
        assertThat(label).isEqualTo("Build time estimate <1s");
    }

    @Test
    void multi_second_eta_uses_tilde_estimate() {
        String label = TestAnsi.strip(ExplainCommand.buildTimeEstimate(8_000, false, Theme.active()));
        assertThat(label).isEqualTo("Build time estimate ~8s");
    }

    @Test
    void formatStepName_pads_shorter_names_with_dots_to_align_with_longest() {
        // package-assembly (16) + 2-dot gap → width 18, matching the explain tree.
        int width = "package-assembly".length() + 2;
        assertThat(ExplainCommand.formatStepName("compile-main", width, Theme.active(), false))
                .isEqualTo("compile-main......");
        assertThat(ExplainCommand.formatStepName("run-tests", width, Theme.active(), false))
                .isEqualTo("run-tests.........");
        assertThat(ExplainCommand.formatStepName("package-assembly", width, Theme.active(), false))
                .isEqualTo("package-assembly..");
    }

    @Test
    void formatStepName_ansi_keeps_visible_width_and_name_prefix() {
        int width = "package-assembly".length() + 2;
        String styled = ExplainCommand.formatStepName("compile-main", width, Theme.active(), true);
        assertThat(TestAnsi.strip(styled)).isEqualTo("compile-main......");
    }
}
