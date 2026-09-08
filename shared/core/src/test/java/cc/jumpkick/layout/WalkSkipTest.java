// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.layout;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WalkSkipTest {

    @Test
    void workspace_key_does_not_skip_out(@TempDir Path dir) {
        assertThat(WalkSkip.workspaceKey(dir.resolve("out"))).isFalse();
        assertThat(WalkSkip.workspaceKey(dir.resolve("src"))).isFalse();
        assertThat(WalkSkip.workspaceKey(dir.resolve("target"))).isTrue();
        assertThat(WalkSkip.workspaceKey(dir.resolve(".gradle"))).isTrue();
        assertThat(WalkSkip.workspaceKey(dir.resolve(".idea"))).isTrue();
    }

    @Test
    void path_source_skips_out(@TempDir Path dir) {
        assertThat(WalkSkip.pathSource(dir.resolve("out"))).isTrue();
        assertThat(WalkSkip.pathSource(dir.resolve("target"))).isTrue();
        assertThat(WalkSkip.pathSource(dir.resolve("src"))).isFalse();
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

    @Test
    void gradles_build_is_skipped_by_position_and_a_package_named_build_is_not(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("build.gradle.kts"), "plugins { java }");
        Path gradleBuild = Files.createDirectories(dir.resolve("build"));
        Path pkg = Files.createDirectories(dir.resolve("src/main/java/cc/jumpkick/plugin/build"));

        assertThat(WalkSkip.workspaceKey(gradleBuild)).isTrue();
        assertThat(WalkSkip.pathSource(gradleBuild)).isTrue();
        assertThat(WalkSkip.formatSkip(gradleBuild)).isTrue();

        assertThat(WalkSkip.workspaceKey(pkg)).isFalse();
        assertThat(WalkSkip.pathSource(pkg)).isFalse();
        assertThat(WalkSkip.formatSkip(pkg)).isFalse();
        assertThat(WalkSkip.formatSegment("build"))
                .as("a name alone cannot decide")
                .isFalse();
    }
}
