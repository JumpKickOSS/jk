// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.task.ClasspathFingerprint;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code [test] network = "allowed"} decides whether a suite that reaches past the host may replay.
 * A green marker stored while it was allowed must not replay once it is not, so the setting is a
 * run-tests stamp input. A member without its own setting takes its workspace root's.
 */
class TestStampNetworkTest {

    @Test
    void dropping_network_allowed_moves_the_stamp(@TempDir Path tmp) throws Exception {
        List<String> allowed = extras(tmp, "[test]\nnetwork = \"allowed\"\n");
        List<String> notAllowed = extras(tmp, "");
        assertThat(allowed)
                .as("a suite replayed under network = allowed must run again once the setting is gone")
                .isNotEqualTo(notAllowed);
        assertThat(extras(tmp, "")).as("an unchanged manifest replays").isEqualTo(notAllowed);
    }

    @Test
    void a_member_takes_the_workspace_roots_setting(@TempDir Path tmp) throws Exception {
        Path member = Files.createDirectories(tmp.resolve("m"));
        workspaceRoot(tmp, "[test]\nnetwork = \"allowed\"\n");
        List<String> inherited = extras(member, "");
        JkBuild project = JkBuildParser.parse(member.resolve("jk.toml"));
        assertThat(TestStampInputs.testNetworkAllowed(project, member))
                .as("the live run and the forecast read the root's setting for the member")
                .isTrue();
        assertThat(inherited).contains("network:allowed");

        workspaceRoot(tmp, "");
        assertThat(TestStampInputs.testNetworkAllowed(project, member)).isFalse();
        assertThat(extras(member, ""))
                .as("dropping the root's line runs the member's suite again")
                .doesNotContain("network:allowed")
                .isNotEqualTo(inherited);
    }

    private static void workspaceRoot(Path root, String extra) throws Exception {
        Files.writeString(root.resolve("jk.toml"), """
                group   = "com.example"
                name    = "ws"
                version = "1.0.0"

                [workspace]
                modules = ["m"]

                """ + extra);
    }

    private static List<String> extras(Path dir, String extra) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group   = "com.example"
                name    = "m"
                version = "1.0.0"
                """ + extra);
        JkBuild project = JkBuildParser.parse(dir.resolve("jk.toml"));
        return TestStampInputs.of(TestStampInputs.Facts.of(dir, project), ClasspathFingerprint.ON_DISK)
                .extras();
    }
}
