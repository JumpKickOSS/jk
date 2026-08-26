// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.testing.NoAnsi;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.NerdFontCaps;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Golden shape checks for the three human chrome modes — nerd (PUA caps), ansi (unicode,
 * no PUA), and plain ({@code --no-ansi} ASCII, no CSI).
 */
class TuiModeFixturesTest {

    private static final String CSI = "\u001B[";
    private static final String PUA = Glyphs.SEGMENT_END_NERD;

    @Test
    void wedge_ok_fail_and_box_table_in_all_modes() throws Exception {
        // Plain: forced no-ansi.
        NoAnsi.withConfig(noAnsiConfig(), () -> {
            String ok = CommandWedge.ok("Build", "done", NerdFontCaps.NONE);
            String fail = CommandWedge.fail("Build", "boom", NerdFontCaps.NONE);
            String table = JkWedge.menu("Installed JDKs").renderTitleBar(RenderContext.current(), 40);
            assertThat(ok).isEqualTo("jk: + Build > done");
            assertThat(fail).isEqualTo("jk: ! Build > boom");
            assertThat(table).startsWith("jk: = Installed JDKs > ").endsWith("+");
            assertThat(ok + fail + table).doesNotContain(CSI).doesNotContain(PUA);
            assertThat(ok + fail + table).doesNotContain(Glyphs.CHECK).doesNotContain(Glyphs.CROSS);
            return null;
        });

        // When ANSI is available in the suite, check nerd vs non-nerd caps.
        String nerd = JkWedge.chipLine(Glyphs.CHECK, "Clean", NerdFontCaps.ALL, "ok");
        String ansi = JkWedge.chipLine(Glyphs.CHECK, "Clean", NerdFontCaps.NONE, "ok");
        if (!nerd.startsWith("+")) {
            assertThat(nerd).contains(PUA);
            assertThat(ansi).doesNotContain(PUA);
            assertThat(nerd).contains("Clean").contains("ok");
            assertThat(ansi).contains("Clean").contains("ok");
            assertThat(ansi).contains(CSI); // colored chip
        }
    }

    @Test
    void tree_plain_is_ascii_connectors_and_bracket_pills() {
        List<String> lines = new Tree("Build Graph")
                .root(Tree.node(Icon.pulse(), "g:a")
                        .child(Tree.node(Pill.of("Rebuild"), "1 module is dirty")
                                .child(Tree.node(Pill.branded("core")).body(RichText.plain("[ ] Compile")))))
                .render(RenderContext.current().withAnsi(false));
        String joined = String.join("\n", lines);
        assertThat(lines)
                .containsExactly(
                        "jk: = Build Graph >",
                        " * g:a",
                        " |",
                        " `-[Rebuild] 1 module is dirty",
                        "    |",
                        "    `-[core]",
                        "       `- [ ] Compile");
        assertThat(joined)
                .doesNotContain(CSI)
                .doesNotContain(PUA)
                .doesNotContain("├")
                .doesNotContain("╰");
    }

    @Test
    void progress_bar_plain_is_ascii_hashes() throws Exception {
        NoAnsi.withConfig(noAnsiConfig(), () -> {
            String bar = TestAnsi.strip(new ProgressBar().render(50, 100));
            assertThat(bar).contains(String.valueOf(Glyphs.BAR_FULL_PLAIN));
            assertThat(bar).contains(String.valueOf(Glyphs.BAR_EMPTY_PLAIN));
            assertThat(bar).doesNotContain("█");
            assertThat(bar).contains("50%");
            return null;
        });
    }

    @Test
    void spinner_wedge_frame_plain_is_ascii() throws Exception {
        var colors = Spinner.buildChipPulseStyles(
                Spinner.PULSE_FRAMES, Theme.active().planBadgeColor());
        NoAnsi.withConfig(noAnsiConfig(), () -> {
            // Every animator frame renders the same still line: --no-ansi has no animation.
            for (int i : new int[] {0, 1, Spinner.PULSE_FRAMES - 1}) {
                String frame = Spinner.renderWedgeFrame(i, "Status", "working", NerdFontCaps.NONE, colors);
                assertThat(frame).isEqualTo("jk: * Status > working");
            }
            // Nerd caps must not smuggle PUA glyphs past the plain switch either.
            String nerd = Spinner.renderWedgeFrame(0, "Status", "working", NerdFontCaps.ALL, colors);
            assertThat(nerd).isEqualTo("jk: * Status > working");
            return null;
        });
    }

    @Test
    void glyphs_helpers_switch_on_ansi() throws Exception {
        NoAnsi.withConfig(noAnsiConfig(), () -> {
            assertThat(Glyphs.check()).isEqualTo(Glyphs.CHECK_PLAIN);
            assertThat(Glyphs.cross()).isEqualTo(Glyphs.CROSS_PLAIN);
            assertThat(Glyphs.pulse()).isEqualTo(Glyphs.PULSE_PLAIN);
            return null;
        });
    }

    @Test
    void envelope_helpers_exist_for_one_shot_settles() {
        // Smoke: print helpers compose without throwing (stdout may be captured by the runner).
        assertThat(CommandWedge.ok("Add", "x")).isNotBlank();
        assertThat(CommandWedge.fail("Add", "y")).isNotBlank();
    }

    @Test
    void format_settle_plain_shape_matches_wedge_and_took() throws Exception {
        NoAnsi.withConfig(noAnsiConfig(), () -> {
            String took = ConsoleSpec.took(Duration.ofMillis(547));
            String settle = CommandWedge.ok("Format", "Already formatted " + took, NerdFontCaps.NONE);
            assertThat(settle).isEqualTo("jk: + Format > Already formatted - took 547ms");
            assertThat(settle).doesNotContain(CSI).doesNotContain(Glyphs.CHECK);
            return null;
        });
    }

    private static JkConfig noAnsiConfig() {
        return JkConfig.empty().withNoAnsi(true);
    }
}
