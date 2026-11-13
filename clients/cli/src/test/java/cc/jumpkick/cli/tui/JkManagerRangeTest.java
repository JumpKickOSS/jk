// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static cc.jumpkick.cli.tui.JkManagerTestSupport.stream;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.config.NerdFontCaps;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;

/**
 * The countdown is plain when the estimate's likely range is narrow, the range with a {@code ~} when
 * it is wide, and a rough figure past about ±50% (JK-4TEZ7, JK-R0N5S).
 */
class JkManagerRangeTest {

    @Test
    void a_narrow_range_is_a_plain_countdown_a_wide_one_the_range_and_a_very_wide_one_rough() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdFont = NerdFontCaps.NONE;
        cm.progress(50, 100);
        cm.setEtaEstimate(100_000);
        cm.setBarResidualRemaining(90_000);

        cm.setEtaRange(5_000, 5_000);
        String narrow = TestAnsi.strip(
                cm.renderBuildPlanLines(120, cm.workElapsedMillis()).get(0));
        assertThat(narrow).containsPattern("ETA 1m [23]\\ds").doesNotContain("ETA ~");

        cm.setEtaRange(30_000, 30_000);
        String wide = TestAnsi.strip(
                cm.renderBuildPlanLines(120, cm.workElapsedMillis()).get(0));
        assertThat(wide).containsPattern("ETA ~(59s|1m 00s)–(1m 59s|2m 00s)");

        cm.setEtaRange(50_000, 60_000);
        String rough = TestAnsi.strip(
                cm.renderBuildPlanLines(120, cm.workElapsedMillis()).get(0));
        assertThat(rough).containsPattern("ETA ~1m [23]\\ds \\(rough\\)");
    }
}
