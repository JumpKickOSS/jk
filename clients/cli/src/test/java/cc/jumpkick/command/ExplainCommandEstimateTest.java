// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.theme.Theme;
import org.junit.jupiter.api.Test;

/** Pure unit coverage for {@link ExplainCommand#buildTimeEstimate} (JK-1298). */
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
}
