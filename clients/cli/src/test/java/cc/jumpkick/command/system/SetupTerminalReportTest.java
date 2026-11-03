// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.NerdFontCaps;
import cc.jumpkick.config.NerdFontDetect.Confidence;
import cc.jumpkick.config.NerdFontDetect.Result;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The report must stay inside the 78-column budget once the chip and indent are added. */
class SetupTerminalReportTest {

    private static final int CHIP = " ✓ Self   ".length();
    private static final int BUDGET = 78;

    @Test
    void no_line_exceeds_the_budget_whatever_the_reason_or_cache_state() {
        String longReason = "a very long explanation of why this terminal was classified as it was, "
                + "with several clauses and a path /Users/someone/.config/terminal/profile.toml";
        String manyChanged =
                "stale, terminal version, terminal font, terminal config file, installed fonts changed (ssh-10_0_0_7)";
        for (Confidence c : Confidence.values()) {
            for (String source : List.of("apple-terminal", "bundled-terminal", "confirmed", "remote-session")) {
                var r = new Result(NerdFontCaps.ALL, source, longReason, c, manyChanged, null);
                var lines = SetupTerminalReport.explain(r);
                String body = SetupTerminalReport.body(lines);
                String[] rendered = body.split("\n");
                for (int i = 0; i < rendered.length; i++) {
                    int width = (i == 0 ? CHIP : 0) + rendered[i].length();
                    assertThat(width)
                            .as("%s/%s line %d: %s", c, source, i, rendered[i])
                            .isLessThanOrEqualTo(BUDGET);
                }
            }
        }
    }

    @Test
    void the_report_names_the_caps_confidence_source_and_cache_state() {
        var r = new Result(
                NerdFontCaps.WEDGE_ONLY,
                "installed-fonts",
                "Konsole falls back",
                Confidence.INFERRED,
                "no entry",
                null);
        assertThat(SetupTerminalReport.explain(r))
                .contains("detected wedge (inferred)", "source: installed-fonts", "confirmation: no entry");
    }

    @Test
    void only_uncertain_results_suggest_confirming() {
        assertThat(SetupTerminalReport.explain(result(Confidence.UNKNOWN, "unknown-terminal")))
                .anyMatch(l -> l.contains("jk self setup-terminal"));
        assertThat(SetupTerminalReport.explain(result(Confidence.KNOWN, "apple-terminal")))
                .anyMatch(l -> l.contains("jk self setup-terminal"));
        assertThat(SetupTerminalReport.explain(result(Confidence.KNOWN, "bundled-terminal")))
                .noneMatch(l -> l.contains("jk self setup-terminal"));
        assertThat(SetupTerminalReport.explain(result(Confidence.CONFIRMED, "confirmed")))
                .noneMatch(l -> l.contains("jk self setup-terminal"));
    }

    private static Result result(Confidence c, String source) {
        return new Result(NerdFontCaps.NONE, source, "reason", c, "valid", null);
    }
}
