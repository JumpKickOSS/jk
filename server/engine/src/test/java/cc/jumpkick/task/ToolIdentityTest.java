// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import cc.jumpkick.host.Os;
import cc.jumpkick.host.SearchPath;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** PATH lookup for {@code [test] tools}: a corrupt entry must not fail the suite. */
class ToolIdentityTest {

    @Test
    void a_path_entry_that_is_not_a_path_is_skipped(@TempDir Path tmp) throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path node = bin.resolve(Os.isWindows() ? "node.exe" : "node");
        Files.writeString(node, "ok");
        if (!Os.isWindows()) assertThat(node.toFile().setExecutable(true)).isTrue();
        String corrupt = "C:\\a\\bin C:\\b\\bin";
        String path = corrupt + SearchPath.SEPARATOR + bin;
        assertThat(ToolIdentity.resolve("node", path)).isEqualTo(node);
        assertThat(ToolIdentity.of("node", corrupt)).isEqualTo(ToolIdentity.MISSING);
    }

    @Test
    void a_tool_that_prints_nothing_and_never_exits_gives_up_at_the_version_timeout(@TempDir Path tmp)
            throws Exception {
        assumeFalse(Os.isWindows(), "a shell script tool is POSIX-only");
        Path tool = tmp.resolve("hangs");
        Files.writeString(tool, "#!/bin/sh\nexec sleep 120\n");
        assertThat(tool.toFile().setExecutable(true)).isTrue();

        String version = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> ToolIdentity.probe(tool));

        assertThat(version).isEqualTo("no-version");
    }
}
