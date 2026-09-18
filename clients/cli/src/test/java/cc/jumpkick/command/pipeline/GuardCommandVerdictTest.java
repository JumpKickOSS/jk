// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code jk guard} prints the guards' verdict on the terminal, read off the {@code ## Guards}
 * section the engine writes into {@code target/jk-results.md}, beside the build's own wedge.
 */
class GuardCommandVerdictTest {

    @Test
    void a_clean_run_names_the_lanes_checked() {
        List<String> results = List.of(
                "# jk-guard",
                "",
                "## Guards",
                "",
                "Guards: clean · 4 lanes (1 cached)",
                "",
                "## Steps",
                "| step | status |");
        assertThat(GuardCommand.verdict(results).map(TestAnsi::strip)).hasValue("Guards: clean · 4 lanes (1 cached)");
    }

    @Test
    void a_red_run_names_the_rules_and_sites_broken() {
        List<String> results = List.of(
                "## Guards",
                "",
                "**2 rules broken** (5 sites)",
                "",
                "### one-digest-surface — one digest surface  (1 site)",
                "- `a/Foo.java:42`");
        assertThat(GuardCommand.verdict(results).map(TestAnsi::strip)).hasValue("Guards: 2 rules broken (5 sites)");
    }

    @Test
    void a_record_without_a_guard_section_yields_no_line() {
        assertThat(GuardCommand.verdict(List.of("# jk-guard", "", "## Steps", "| step |")))
                .isEmpty();
        assertThat(GuardCommand.verdict(List.of("## Guards", "", "## Steps"))).isEmpty();
    }
}
