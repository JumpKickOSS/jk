// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import cc.jumpkick.config.Request;
import cc.jumpkick.config.RequestScope;
import cc.jumpkick.config.RequestShell;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.SearchPath;
import cc.jumpkick.host.Settled;
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
    void a_tool_that_hangs_gives_up_at_the_timeout_once_per_request(@TempDir Path tmp) throws Exception {
        assumeFalse(Os.isWindows(), "a shell script tool is POSIX-only");
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path count = tmp.resolve("count");
        String file = "'" + count + "'";
        // Counts its runs, then prints nothing and never exits. Shell builtins only, but sleep,
        // which it runs by absolute path.
        Path node = tool(
                bin,
                "node",
                "n=0; [ -f " + file + " ] && read n < " + file + "; echo $((n + 1)) > " + file
                        + "; exec /bin/sleep 120");
        String path = bin.toString();

        Request.of(1, new IoLedger()).run(() -> {
            try {
                // On this thread: the request is bound here, not on a timeout helper's thread.
                long start = System.nanoTime();
                assertThat(ToolIdentity.of("node", path, tmp)).isEqualTo("no-version");
                assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(20));
                assertThat(ToolIdentity.of("node", path, tmp))
                        .as("the hang is remembered for the request")
                        .isEqualTo("no-version");
                assertThat(Files.readString(count).strip()).isEqualTo("1");
            } finally {
                RequestScope.release();
            }
        });
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

        assertThat(first).isEqualTo("v22 in a");
        assertThat(second).isEqualTo("v24 in b");
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
                assertThat(ToolIdentity.of("node", path, tmp)).isEqualTo("no-version");
                assertThat(ToolIdentity.of("node", path, tmp)).as("asked again").isEqualTo("v2");
                assertThat(ToolIdentity.of("node", path, tmp)).as("remembered").isEqualTo("v2");
            } finally {
                RequestScope.release();
            }
        });
        assertThat(ToolIdentity.of("node", path, tmp))
                .as("a new request asks again")
                .isEqualTo("v3");
    }

    /** A tool whose version line is translated reads alike under every caller locale (JK-7AA0Z). */
    @Test
    void the_version_is_asked_in_the_c_locale(@TempDir Path tmp) throws Exception {
        assumeFalse(Os.isWindows(), "a shell script tool is POSIX-only");
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        // bash's own habit: "Version" when the locale is German, "version" otherwise.
        tool(
                bin,
                "sh",
                "case \"${LC_ALL:-${LC_MESSAGES:-$LANG}}\" in de*) echo 'GNU bash, Version 5.3';; *) echo 'GNU bash, version 5.3';; esac");
        String path = bin.toString();
        String german = RequestShell.where(
                Map.of("PATH", path, "LANG", "de_DE.UTF-8", "LC_ALL", "de_DE.UTF-8", "LC_MESSAGES", "de_DE.UTF-8"),
                () -> ToolIdentity.of("sh", path, tmp));
        String english =
                RequestShell.where(Map.of("PATH", path, "LANG", "en_US.UTF-8"), () -> ToolIdentity.of("sh", path, tmp));
        assertThat(german).isEqualTo(english).isEqualTo("GNU bash, version 5.3");
    }

    @Test
    void a_version_line_that_names_its_own_path_or_home_is_normalized() {
        Path exe = Path.of(System.getProperty("user.home"), "tools/bin/tool");
        assertThat(ToolIdentity.normalized("tool 1.2  at " + exe + "  (" + System.getProperty("user.home") + ")", exe))
                .isEqualTo("tool 1.2 at tool (~)");
    }

    /**
     * A hang is the binary's: once a tool hung on {@code --version}, no directory and no later request
     * asks it again while its file is unchanged. Remembered per request and per directory, the
     * timeout was paid by every module whose suite starts the tool on every build (5.1 s in each of
     * hotel-reservations' two Testcontainers modules on every replayed {@code jk test -g} on WSL).
     * A changed file is asked again.
     */
    @Test
    void a_tool_that_hung_is_not_asked_again_until_its_file_changes(@TempDir Path tmp) throws Exception {
        assumeFalse(Os.isWindows(), "a shell script tool is POSIX-only");
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path count = tmp.resolve("count");
        String file = "'" + count + "'";
        String body = "n=0; [ -f " + file + " ] && read n < " + file + "; echo $((n + 1)) > " + file
                + "; exec /bin/sleep 120";
        Path helper = tool(bin, "docker-credential-probe", body);
        // A file written this instant vouches for nothing until it settles: judge as if it had.
        Settled.aheadForTests(Duration.ofSeconds(10));
        try {
            hangsOnce(helper, bin, tmp, count, body);
        } finally {
            Settled.aheadForTests(null);
        }
    }

    private static void hangsOnce(Path helper, Path bin, Path tmp, Path count, String body) throws Exception {
        Path service = Files.createDirectories(tmp.resolve("service"));
        Path rest = Files.createDirectories(tmp.resolve("rest"));

        assertThat(ToolIdentity.probeOnce(helper, service, bin.toString(), 300).timedOut())
                .isTrue();
        long start = System.nanoTime();
        assertThat(ToolIdentity.probeOnce(helper, rest, bin.toString(), 300).timedOut())
                .as("another directory, another build: the hang stands")
                .isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(250));
        assertThat(Files.readString(count).strip()).isEqualTo("1");

        Files.writeString(helper, "#!/bin/sh\n# upgraded\n" + body + "\n");
        assertThat(ToolIdentity.probeOnce(helper, rest, bin.toString(), 300).timedOut())
                .isTrue();
        assertThat(Files.readString(count).strip())
                .as("a changed file is asked again")
                .isEqualTo("2");
    }

    private static Path tool(Path bin, String name, String body) throws Exception {
        Path exe = bin.resolve(name);
        Files.writeString(exe, "#!/bin/sh\n" + body + "\n");
        assertThat(exe.toFile().setExecutable(true)).isTrue();
        return exe;
    }
}
