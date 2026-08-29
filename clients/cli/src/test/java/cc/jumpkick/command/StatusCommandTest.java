// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.TestAnsi;
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
    void dottedLabel_left_aligns_with_dot_leaders() {
        assertThat(StatusCommand.dottedLabel("JDK", 18)).isEqualTo("JDK..............:");
        assertThat(StatusCommand.dottedLabel("Total Build Count", 18)).isEqualTo("Total Build Count:");
        assertThat(StatusCommand.dottedLabel("Artifacts Cached", 18)).isEqualTo("Artifacts Cached.:");
        assertThat(StatusCommand.dottedLabel("Language", 18)).isEqualTo("Language.........:");
    }

    @Test
    void sameBaseDir_matches_dirty_count_shapes() {
        assertThat(StatusCommand.sameBaseDir("/home/u/src/proj", "/home/u/src/proj"))
                .isTrue();
        assertThat(StatusCommand.sameBaseDir("/home/u/src/proj", "/home/u/src/proj#d14"))
                .isTrue();
        assertThat(StatusCommand.sameBaseDir("/home/u/src/proj", "/home/u/src/proj#d0"))
                .isTrue();
        assertThat(StatusCommand.sameBaseDir("/home/u/src/proj", "/home/u/src/other#d1"))
                .isFalse();
        assertThat(StatusCommand.sameBaseDir("/home/u/src/proj", "/home/u/src/proj-extra"))
                .isFalse();
    }

    @Test
    void baseDir_strips_shape_suffix() {
        assertThat(StatusCommand.baseDir("/home/me/app#d27")).isEqualTo("/home/me/app");
        assertThat(StatusCommand.baseDir("/home/me/app")).isEqualTo("/home/me/app");
    }

    @Test
    void engineStatusMessage_shapes_running_and_down() {
        String down = TestAnsi.strip(StatusCommand.engineStatusMessage(Optional.empty()));
        assertThat(down).isEqualTo("JumpKick Engine v" + Jk.VERSION + " is not running");

        EngineClient.Status s =
                new EngineClient.Status(Jk.VERSION, 403279L, 0L, 0, 0, false, 0L, 0L, 0L, 0L, 0L, null, null, null);
        String up = TestAnsi.strip(StatusCommand.engineStatusMessage(Optional.of(s)));
        assertThat(up).isEqualTo("JumpKick Engine v" + Jk.VERSION + " is running (pid 403279)");
    }
}
