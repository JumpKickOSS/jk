// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static cc.jumpkick.cli.tui.JkManagerTestSupport.stream;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.config.NerdFontCaps;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;

/** The countdown shows the estimate's likely range in place of a point when it is wide (JK-4TEZ7). */
class JkManagerRangeTest {

    @Test
    void a_wide_range_replaces_the_point_and_a_narrow_one_leaves_the_countdown() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Build", false);
        cm.nerdFont = NerdFontCaps.NONE;
        cm.progress(50, 100);
        cm.setEtaEstimate(100_000);
        cm.setBarResidualRemaining(90_000);

        cm.setEtaRange(5_000, 5_000);
        String narrow = TestAnsi.strip(
                cm.renderBuildPlanLines(120, cm.workElapsedMillis()).get(0));
        assertThat(narrow).containsPattern("ETA ~1m [23]\\ds");

        cm.setEtaRange(30_000, 30_000);
        String wide = TestAnsi.strip(
                cm.renderBuildPlanLines(120, cm.workElapsedMillis()).get(0));
        assertThat(wide).containsPattern("ETA (59s|1m 00s)–(1m 59s|2m 00s)").doesNotContain("ETA ~");
    }
}
