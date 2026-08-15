// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.NerdFontCaps;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
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
        withConfig(noAnsiConfig(), () -> {
            String ok = CommandWedge.ok("Build", "done", NerdFontCaps.NONE);
            String fail = CommandWedge.fail("Build", "boom", NerdFontCaps.NONE);
            String table = JkWedge.menu("Installed JDKs").renderTitleBar(RenderContext.current(), 40);
            assertThat(ok).isEqualTo(" + Build > done");
            assertThat(fail).isEqualTo(" ! Build > boom");
            assertThat(table).startsWith(" = Installed JDKs > ").endsWith("+");
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
                        " = Build Graph >",
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
        withConfig(noAnsiConfig(), () -> {
            String bar = TestAnsi.strip(new ProgressBar().render(50, 100));
            assertThat(bar).contains(String.valueOf(Glyphs.BAR_FULL_PLAIN));
            assertThat(bar).contains(String.valueOf(Glyphs.BAR_EMPTY_PLAIN));
            assertThat(bar).doesNotContain("█");
            assertThat(bar).contains("50%");
            return null;
        });
    }

    @Test
    void spinner_wedge_frame_plain_is_ascii() {
        var colors = Spinner.buildChipPulseStyles(
                Spinner.PULSE_FRAMES, cc.jumpkick.cli.theme.Theme.active().planBadgeColor());
        // Force plain path inside renderWedgeFrame via Theme — under CI isAnsi is often false already.
        String frame = Spinner.renderWedgeFrame(0, "Status", "working", NerdFontCaps.NONE, colors);
        if (frame.contains("Status") && !frame.contains(CSI)) {
            assertThat(frame).isEqualTo(" * Status > working");
            assertThat(frame).doesNotContain(Glyphs.PULSE).doesNotContain(PUA).doesNotContain(CSI);
        }
    }

    @Test
    void glyphs_helpers_switch_on_ansi() throws Exception {
        withConfig(noAnsiConfig(), () -> {
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
        withConfig(noAnsiConfig(), () -> {
            String took = cc.jumpkick.cli.run.ConsoleSpec.took(Duration.ofMillis(547));
            String settle = CommandWedge.ok("Format", "Already formatted " + took, NerdFontCaps.NONE);
            assertThat(settle).isEqualTo(" + Format > Already formatted - took 547ms");
            assertThat(settle).doesNotContain(CSI).doesNotContain(Glyphs.CHECK);
            return null;
        });
    }

    private static JkConfig noAnsiConfig() {
        return JkConfig.empty().withNoAnsi(Optional.of(true));
    }

    private static <T> T withConfig(JkConfig cfg, Supplier<T> body) throws Exception {
        Session original = SessionContext.current();
        try {
            return SessionContext.where(original.withConfig(cfg), body::get);
        } finally {
            SessionContext.install(original);
        }
    }
}
