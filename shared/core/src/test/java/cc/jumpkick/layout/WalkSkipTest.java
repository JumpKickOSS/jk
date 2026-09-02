// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.layout;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class WalkSkipTest {

    @Test
    void workspace_key_does_not_skip_out() {
        assertThat(WalkSkip.workspaceKey(Path.of("out"))).isFalse();
        assertThat(WalkSkip.workspaceKey(Path.of("src"))).isFalse();
        assertThat(WalkSkip.workspaceKey(Path.of("target"))).isTrue();
        assertThat(WalkSkip.workspaceKey(Path.of(".gradle"))).isTrue();
        assertThat(WalkSkip.workspaceKey(Path.of(".idea"))).isTrue();
    }

    @Test
    void path_source_skips_out() {
        assertThat(WalkSkip.pathSource(Path.of("out"))).isTrue();
        assertThat(WalkSkip.pathSource(Path.of("target"))).isTrue();
        assertThat(WalkSkip.pathSource(Path.of("src"))).isFalse();
    }

    @Test
    void format_does_not_skip_gradle_or_out() {
        assertThat(WalkSkip.formatSegment(".gradle")).isFalse();
        assertThat(WalkSkip.formatSegment(".idea")).isFalse();
        assertThat(WalkSkip.formatSegment("out")).isFalse();
        assertThat(WalkSkip.formatSegment("target")).isTrue();
        assertThat(WalkSkip.formatSegment(".git")).isTrue();
        assertThat(WalkSkip.formatSegment("foo.g8")).isTrue();
        assertThat(WalkSkip.formatSegment("$pkg$")).isTrue();
    }
}
