// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.engine.EngineClient;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Pure unit coverage for {@link StatusCommand} formatting helpers. */
class StatusCommandTest {

    @Test
    void formatDuration_covers_ms_s_m_h_d() {
        assertThat(StatusCommand.formatDuration(38)).isEqualTo("38ms");
        assertThat(StatusCommand.formatDuration(22_000)).isEqualTo("22s");
        assertThat(StatusCommand.formatDuration(62_000)).isEqualTo("1m 2s");
        assertThat(StatusCommand.formatDuration(192_000)).isEqualTo("3m 12s");
        assertThat(StatusCommand.formatDuration(86_400_000L + 4L * 3_600_000L + 12_000L))
                .isEqualTo("1d 4h 12s");
    }

    @Test
    void padLeft_right_aligns_labels() {
        assertThat(StatusCommand.padLeft("JDK:", 18)).isEqualTo(" ".repeat(14) + "JDK:");
        assertThat(StatusCommand.padLeft("Total Build Count:", 18)).isEqualTo("Total Build Count:");
        assertThat(StatusCommand.padLeft("Artifacts Cached:", 18)).isEqualTo(" Artifacts Cached:");
    }

    @Test
    void sameBaseDir_matches_dirty_count_shapes() {
        assertThat(StatusCommand.sameBaseDir("/proj", "/proj")).isTrue();
        assertThat(StatusCommand.sameBaseDir("/proj", "/proj#d14")).isTrue();
        assertThat(StatusCommand.sameBaseDir("/proj", "/proj#d0")).isTrue();
        assertThat(StatusCommand.sameBaseDir("/proj", "/other#d1")).isFalse();
        assertThat(StatusCommand.sameBaseDir("/proj", "/proj-extra")).isFalse();
    }

    @Test
    void baseDir_strips_shape_suffix() {
        assertThat(StatusCommand.baseDir("/home/me/app#d27")).isEqualTo("/home/me/app");
        assertThat(StatusCommand.baseDir("/home/me/app")).isEqualTo("/home/me/app");
    }

    @Test
    void engineStatusMessage_shapes_running_and_down() {
        String down = stripAnsi(StatusCommand.engineStatusMessage(Optional.empty()));
        assertThat(down).isEqualTo("JumpKick Engine v" + Jk.VERSION + " is not running");

        EngineClient.Status s = new EngineClient.Status(
                Jk.VERSION, 403279L, 0L, 0, 0, false, 0L, 0L, 0L, 0L, 0L, null, null, null);
        String up = stripAnsi(StatusCommand.engineStatusMessage(Optional.of(s)));
        assertThat(up).isEqualTo("JumpKick Engine v" + Jk.VERSION + " is running (pid 403279)");
    }

    private static String stripAnsi(String s) {
        return s.replaceAll("\u001B\\[[0-9;]*m", "");
    }
}
