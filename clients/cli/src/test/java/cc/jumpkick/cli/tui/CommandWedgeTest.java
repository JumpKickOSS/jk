// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.theme.Theme;
import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.Test;

class CommandWedgeTest {

    @Test
    void plain_mode_chip_line_shape() {
        // When Theme is non-ANSI (CI / NO_COLOR), PipelineWedge uses ASCII prefixes.
        // Force the plain branch via the public chipLine path used by CommandWedge:
        String ok = PipelineWedge.chipLine(Glyphs.CHECK, "Build", false, "done");
        String fail = PipelineWedge.failureLineCustom("Build", false, "boom");
        // Under CI (this suite), isAnsi is typically false → plain
        if (ok.startsWith("+")) {
            assertThat(ok).isEqualTo("+ Build: done");
            assertThat(fail).startsWith("! Build:").contains("boom");
        } else {
            // ANSI-enabled developer machine: still must carry command + message
            assertThat(ok).contains("Build").contains("done");
            assertThat(fail).contains("Build").contains("boom");
        }
    }

    @Test
    void nerd_cap_only_when_nerdfont_flag() {
        // With ANSI on: nerdfont uses U+E0B0; plain uses a bg-colored trailing space (no PUA).
        // Skip assertion when suite runs without ANSI (plain mode has no colored cap either way).
        String nerd = PipelineWedge.chipLine(Glyphs.CHECK, "Clean", true, "ok");
        String plain = PipelineWedge.chipLine(Glyphs.CHECK, "Clean", false, "ok");
        if (!nerd.startsWith("+")) {
            assertThat(nerd).contains(Glyphs.SEGMENT_END_NERD);
            assertThat(plain).doesNotContain(Glyphs.SEGMENT_END_NERD);
            // Plain cap is a space on the chip background (pipeline green for success).
            String plainCap = Theme.colorize(
                    " ",
                    Theme.active()
                            .withBackground(
                                    AttributedStyle.DEFAULT, Theme.active().pipelineChipColor()));
            assertThat(plain).contains(plainCap);
        }
        assertThat(nerd).contains("Clean").contains("ok");
        assertThat(plain).contains("Clean").contains("ok");
    }

    @Test
    void command_wedge_delegates() {
        assertThat(CommandWedge.ok("X", "y", false)).contains("X").contains("y");
        assertThat(CommandWedge.fail("X", "y", false)).contains("X").contains("y");
        assertThat(CommandWedge.working("X", "y")).contains("X").contains("y");
    }

    @Test
    void cancelled_job_line_remote_vs_by_user() {
        String remote = PipelineWedge.cancelledJobLine("Build", false, false, "took 1.6s")
                .replaceAll("\u001B\\[[0-9;]*m", "");
        assertThat(remote).contains("Build job was cancelled");
        assertThat(remote).contains("took 1.6s");
        assertThat(remote).doesNotContain("by user");

        String local = PipelineWedge.cancelledJobLine("Build", false, true, "took 1.6s")
                .replaceAll("\u001B\\[[0-9;]*m", "");
        assertThat(local).contains("Build job was cancelled by user");
        assertThat(local).contains("took 1.6s");
    }
}
