// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import cc.jumpkick.config.Request;
import cc.jumpkick.config.RequestScope;
import cc.jumpkick.config.RequestShell;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.SearchPath;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code [test] tools} identity: PATH lookup that a corrupt entry cannot fail, and a version asked
 * where and as the suite would ask it, remembered for one request and never remembered as absent.
 */
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
        assertThat(ToolIdentity.of("node", corrupt, tmp)).isEqualTo(ToolIdentity.MISSING);
    }

    @Test
    void a_tool_that_prints_nothing_and_never_exits_gives_up_at_the_version_timeout(@TempDir Path tmp)
            throws Exception {
        assumeFalse(Os.isWindows(), "a shell script tool is POSIX-only");
        Path tool = tmp.resolve("hangs");
        Files.writeString(tool, "#!/bin/sh\nexec sleep 120\n");
        assertThat(tool.toFile().setExecutable(true)).isTrue();

        String version = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> ToolIdentity.probe(tool, tmp, null));

        assertThat(version).isEqualTo("no-version");
    }

    @Test
    void a_shim_s_version_follows_the_suite_directory_and_the_shell_not_the_binary(@TempDir Path tmp) throws Exception {
        assumeFalse(Os.isWindows(), "a shell script tool is POSIX-only");
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        // A toolchain manager's shim: one binary, the version the directory and the shell select.
        Path node = tool(bin, "node", "echo \"v$SHIM_NODE in ${PWD##*/}\"");
        Path a = Files.createDirectories(tmp.resolve("a"));
        Path b = Files.createDirectories(tmp.resolve("b"));
        String path = bin.toString();

        String first =
                RequestShell.where(Map.of("PATH", path, "SHIM_NODE", "22"), () -> ToolIdentity.of("node", path, a));
        String second =
                RequestShell.where(Map.of("PATH", path, "SHIM_NODE", "24"), () -> ToolIdentity.of("node", path, b));

        assertThat(first).isEqualTo(node + "=v22 in a");
        assertThat(second).isEqualTo(node + "=v24 in b");
    }

    @Test
    void a_request_remembers_a_version_line_but_never_its_absence(@TempDir Path tmp) throws Exception {
        assumeFalse(Os.isWindows(), "a shell script tool is POSIX-only");
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path count = tmp.resolve("count");
        // Silent on its first run, then prints how many times it has run. Shell builtins only: the
        // probe's PATH is the one given, which holds nothing else.
        String file = "'" + count + "'";
        Path node = tool(
                bin,
                "node",
                "n=0; [ -f " + file + " ] && read n < " + file + "; n=$((n + 1)); echo $n > " + file + ";"
                        + " [ $n -gt 1 ] && echo v$n; exit 0");
        String path = bin.toString();

        Request.of(1, new IoLedger()).run(() -> {
            try {
                assertThat(ToolIdentity.of("node", path, tmp)).isEqualTo(node + "=no-version");
                assertThat(ToolIdentity.of("node", path, tmp)).as("asked again").isEqualTo(node + "=v2");
                assertThat(ToolIdentity.of("node", path, tmp)).as("remembered").isEqualTo(node + "=v2");
            } finally {
                RequestScope.release();
            }
        });
        assertThat(ToolIdentity.of("node", path, tmp))
                .as("a new request asks again")
                .isEqualTo(node + "=v3");
    }

    private static Path tool(Path bin, String name, String body) throws Exception {
        Path exe = bin.resolve(name);
        Files.writeString(exe, "#!/bin/sh\n" + body + "\n");
        assertThat(exe.toFile().setExecutable(true)).isTrue();
        return exe;
    }
}
