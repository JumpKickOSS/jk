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
 * run-tests stamp input.
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
