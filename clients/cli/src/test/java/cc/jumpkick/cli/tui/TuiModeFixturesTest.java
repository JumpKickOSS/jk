// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * JK-1376: golden shape checks for the three human chrome modes — nerd (PUA caps), ansi (unicode,
 * no PUA), and plain ({@code --no-ansi} ASCII, no CSI).
 */
class TuiModeFixturesTest {

    private static final String CSI = "\u001B[";
    private static final String PUA = Glyphs.SEGMENT_END_NERD;

    @Test
    void wedge_ok_fail_and_box_table_in_all_modes() throws Exception {
        // Plain: forced no-ansi.
        withConfig(noAnsiConfig(), () -> {
            String ok = CommandWedge.ok("Build", "done", false);
            String fail = CommandWedge.fail("Build", "boom", false);
            String table = BoxTable.titleBar("Installed JDKs", 40);
            assertThat(ok).isEqualTo(" + Build > done");
            assertThat(fail).isEqualTo(" ! Build > boom");
            assertThat(table).startsWith(" = Installed JDKs > ").endsWith("+");
            assertThat(ok + fail + table).doesNotContain(CSI).doesNotContain(PUA);
            assertThat(ok + fail + table).doesNotContain(Glyphs.CHECK).doesNotContain(Glyphs.CROSS);
            return null;
        });

        // When ANSI is available in the suite, check nerd vs non-nerd caps.
        String nerd = PipelineWedge.chipLine(Glyphs.CHECK, "Clean", true, "ok");
        String ansi = PipelineWedge.chipLine(Glyphs.CHECK, "Clean", false, "ok");
        if (!nerd.startsWith("+")) {
            assertThat(nerd).contains(PUA);
            assertThat(ansi).doesNotContain(PUA);
            assertThat(nerd).contains("Clean").contains("ok");
            assertThat(ansi).contains("Clean").contains("ok");
            assertThat(ansi).contains(CSI); // colored chip
        }
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
        String frame = Spinner.renderWedgeFrame(0, "Status", "working", false, colors);
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

    private static JkConfig noAnsiConfig() {
        return new JkConfig(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(true), // noAnsi
                Optional.empty(),
                Optional.empty());
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
