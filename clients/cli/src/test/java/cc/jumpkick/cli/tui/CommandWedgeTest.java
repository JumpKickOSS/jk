// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

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
        // With ANSI on, nerdfont true injects the powerline terminator; false does not.
        // Skip assertion when suite runs without ANSI (plain mode has no cap either way).
        String nerd = PipelineWedge.chipLine(Glyphs.CHECK, "Clean", true, "ok");
        String plain = PipelineWedge.chipLine(Glyphs.CHECK, "Clean", false, "ok");
        if (!nerd.startsWith("+")) {
            assertThat(nerd).contains(Glyphs.SEGMENT_END_NERD);
            assertThat(plain).doesNotContain(Glyphs.SEGMENT_END_NERD);
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
}
