// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.theme.Theme;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CommandWedgeTest {

    @Test
    void plain_mode_chip_line_shape() {
        // When Theme is non-ANSI (CI / NO_COLOR), BuildPlanWedge uses ASCII prefixes.
        // Force the plain branch via the public chipLine path used by CommandWedge:
        String ok = JkWedge.chipLine(Glyphs.CHECK, "Build", false, "done");
        String fail = JkWedge.failureLineCustom("Build", false, "boom");
        // Under CI (this suite), isAnsi is typically false → plain
        if (ok.startsWith(" +") || ok.startsWith("+")) {
            assertThat(ok).isEqualTo(" + Build > done");
            assertThat(fail).isEqualTo(" ! Build > boom");
        } else {
            // ANSI-enabled developer machine: still must carry command + message
            assertThat(ok).contains("Build").contains("done");
            assertThat(fail).contains("Build").contains("boom");
        }
    }

    @Test
    void nerd_cap_only_when_nerdfont_flag() {
        // With ANSI on: nerdfont uses U+E0B0; ansi-no-nerd uses two trailing chip spaces (no PUA).
        String nerd = JkWedge.chipLine(Glyphs.CHECK, "Clean", true, "ok");
        String ansi = JkWedge.chipLine(Glyphs.CHECK, "Clean", false, "ok");
        if (!nerd.contains(" > ")) {
            assertThat(nerd).contains(Glyphs.SEGMENT_END_NERD);
            assertThat(ansi).doesNotContain(Glyphs.SEGMENT_END_NERD);
            // Non-nerd chip ends with two spaces on the success chip bg (before the message).
            String body = Theme.colorize(
                    " " + Glyphs.CHECK + " Clean  ", Theme.active().planSuccessChip());
            assertThat(ansi).contains(body);
        }
        assertThat(nerd).contains("Clean").contains("ok");
        assertThat(ansi).contains("Clean").contains("ok");
    }

    @Test
    void command_wedge_delegates() {
        assertThat(CommandWedge.ok("X", "y", false)).contains("X").contains("y");
        assertThat(CommandWedge.fail("X", "y", false)).contains("X").contains("y");
        assertThat(CommandWedge.working("X", "y")).contains("X").contains("y");
    }

    @Test
    void envelope_start_is_idempotent_until_reset() {
        CommandWedge.resetEnvelope();
        assertThat(CommandWedge.envelopeStarted()).isFalse();
        CommandWedge.envelopeStart();
        assertThat(CommandWedge.envelopeStarted()).isTrue();
        CommandWedge.envelopeStart(); // no second blank side effect on flag
        assertThat(CommandWedge.envelopeStarted()).isTrue();
        CommandWedge.resetEnvelope();
        assertThat(CommandWedge.envelopeStarted()).isFalse();
    }

    @Test
    void analyzing_returns_live_wedge_spinner() {
        var buf = new ByteArrayOutputStream();
        try (Spinner s = CommandWedge.analyzing(
                new PrintStream(buf, true, StandardCharsets.UTF_8), "Status", "Analyzing status...")) {
            assertThat(s).isNotNull();
        }
        // Closed without throwing; silent under --no-progress is fine.
    }

    @Test
    void cancelled_job_line_remote_vs_by_user() {
        String remote =
                JkWedge.cancelledJobLine("Build", false, false, "took 1.6s").replaceAll("\u001B\\[[0-9;]*m", "");
        // The chip names the plan; the body must not repeat it ("Build Build job…",.
        assertThat(remote).contains("Build").contains("job was cancelled");
        assertThat(remote).containsOnlyOnce("Build");
        assertThat(remote).contains("took 1.6s");
        assertThat(remote).doesNotContain("by user");

        String local =
                JkWedge.cancelledJobLine("Build", false, true, "took 1.6s").replaceAll("\u001B\\[[0-9;]*m", "");
        assertThat(local).contains("job was cancelled by user");
        assertThat(local).containsOnlyOnce("Build");
        assertThat(local).contains("took 1.6s");
    }
}
