// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Os;
import cc.jumpkick.host.SearchPath;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
