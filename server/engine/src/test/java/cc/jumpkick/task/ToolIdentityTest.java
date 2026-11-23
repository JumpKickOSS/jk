// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import cc.jumpkick.config.IoLedger;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.Request;
import cc.jumpkick.config.RequestScope;
import cc.jumpkick.config.RequestShell;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.SearchPath;
import cc.jumpkick.host.Settled;
import cc.jumpkick.run.StepScope;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.testing.FakeClock;
import cc.jumpkick.testing.FakePrograms;
import cc.jumpkick.testing.Symlinks;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

        ToolIdentity.versionTimeoutForTests(Duration.ofMillis(300));
        try {
            Request.of(1, new IoLedger()).run(() -> {
                try {
                    // On this thread: the request is bound here, not on a timeout helper's thread.
                    long start = System.nanoTime();
                    assertThat(ToolIdentity.of("node", path, tmp)).startsWith("no-version sha256:");
                    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(20));
                    assertThat(ToolIdentity.of("node", path, tmp))
                            .as("the hang is remembered for the request")
                            .startsWith("no-version sha256:");
                    assertThat(Files.readString(count).strip())
                            .as("asked twice, then remembered")
                            .isEqualTo("2");
                } finally {
                    RequestScope.release();
                }
            });
        } finally {
            ToolIdentity.versionTimeoutForTests(null);
        }
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

    /**
     * A binary of its own is asked its version once while its file holds still; a script, as an
     * asdf shim is, or a link to a binary named otherwise, as a mise or volta shim is, is asked per
     * request, since its answer follows the directory.
     */
    @Test
    void only_a_binary_of_its_own_is_asked_once_for_the_engines_life(@TempDir Path tmp) throws Exception {
        assumeFalse(Os.isWindows(), "POSIX links and scripts");
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path script = tool(bin, "node", "echo v24");
        Path binary = Files.write(bin.resolve("faketool"), new byte[] {0x7f, 'E', 'L', 'F', 0, 0});
        Path shim = Symlinks.create(bin.resolve("git"), binary);

        assertThat(ToolIdentity.ownBinary(binary)).isEqualTo(binary.toRealPath());
        assertThat(ToolIdentity.ownBinary(script)).as("a script").isNull();
        assertThat(ToolIdentity.ownBinary(shim))
                .as("a link to a binary named otherwise")
                .isNull();
    }

    /** {@code /bin/sh} and {@code sh}, {@code ps} and {@code sleep} key by name and OS family; bash and git do not. */
    @Test
    void a_posix_utility_is_one_tool_however_it_is_named() {
        assertThat(ToolIdentity.toolName("/bin/sh")).isEqualTo("sh");
        assertThat(ToolIdentity.stable("/bin/sh")).isTrue();
        assertThat(ToolIdentity.stable("sleep")).isTrue();
        assertThat(ToolIdentity.stable("ps")).isTrue();
        assertThat(ToolIdentity.stable("bash")).isFalse();
        assertThat(ToolIdentity.stable("git")).isFalse();
        assertThat(ToolIdentity.osFamily()).isEqualTo(Os.isWindows() ? "windows" : "posix");
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
                assertThat(ToolIdentity.of("node", path, tmp)).startsWith("no-version sha256:");
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

    /** A tool whose version line is translated reads alike under every caller locale. */
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

    /** A credential the request forwarded for the engine's resolvers never reaches a tool asked its version. */
    @Test
    void a_forwarded_credential_never_reaches_the_version_probe(@TempDir Path tmp) throws Exception {
        assumeFalse(Os.isWindows(), "a shell script tool is POSIX-only");
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        tool(bin, "sh", "echo \"tool 1.0 ${GITHUB_TOKEN:-none} ${AWS_SECRET_ACCESS_KEY:-none} ${GIT_TRACE:-none}\"");
        String path = bin.toString();
        String line = RequestShell.where(
                Map.of(
                        "PATH", path,
                        "GITHUB_TOKEN", "canary-forge",
                        "AWS_SECRET_ACCESS_KEY", "canary-cloud",
                        "GIT_TRACE", "0"),
                () -> ToolIdentity.of("sh", path, tmp));
        assertThat(line).isEqualTo("tool 1.0 none none 0");
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
        assertThat(Files.readString(count).strip())
                .as("asked twice, then remembered")
                .isEqualTo("2");

        FakePrograms.warmed(helper, "#!/bin/sh\n# upgraded\n" + body + "\n");
        assertThat(ToolIdentity.probeOnce(helper, rest, bin.toString(), 300).timedOut())
                .isTrue();
        assertThat(Files.readString(count).strip())
                .as("a changed file is asked again")
                .isEqualTo("4");
    }

    /** A fake tool, run once untimed so a first-exec scan never spends a test's timed window. */
    private static Path tool(Path bin, String name, String body) throws Exception {
        return FakePrograms.warmed(bin.resolve(name), "#!/bin/sh\n" + body + "\n");
    }

    /** A tool that counts its runs into {@code count} and hangs on the first {@code hangs} of them. */
    private static Path flaky(Path bin, Path count, int hangs) throws Exception {
        String file = "'" + count + "'";
        return tool(
                bin,
                "flaky",
                "n=0; [ -f " + file + " ] && read n < " + file + "; n=$((n + 1)); echo $n > " + file + "; [ $n -le "
                        + hangs + " ] && exec /bin/sleep 120; echo flaky 9.9");
    }

    private static int runs(Path count) throws Exception {
        return Integer.parseInt(Files.readString(count).strip());
    }

    @Test
    void a_probe_that_times_out_once_and_then_answers_records_the_version(@TempDir Path tmp) throws Exception {
        assumeFalse(Os.isWindows(), "a shell script tool is POSIX-only");
        ToolIdentity.forgetForTests();
        Path count = tmp.resolve("count");
        Path exe = flaky(Files.createDirectories(tmp.resolve("bin")), count, 1);
        ToolIdentity.Answer a = ToolIdentity.probeOnce(exe, tmp, null, 1000);
        assertThat(a.timedOut()).isFalse();
        assertThat(a.line()).isEqualTo("flaky 9.9");
        assertThat(ToolIdentity.probeOnce(exe, tmp, null, 1000).line())
                .as("not remembered as hung")
                .isEqualTo("flaky 9.9");
        assertThat(runs(count)).isEqualTo(3);
    }

    @Test
    void two_timeouts_flag_a_tool_hung_for_thirty_minutes_and_warn_once(@TempDir Path tmp) throws Exception {
        assumeFalse(Os.isWindows(), "a shell script tool is POSIX-only");
        ToolIdentity.forgetForTests();
        FakeClock clock = new FakeClock();
        ToolIdentity.clockForTests(clock);
        List<String> warnings = new ArrayList<>();
        try {
            Path count = tmp.resolve("count");
            Path exe = flaky(Files.createDirectories(tmp.resolve("bin")), count, 1000);
            StepScope.run(warnings(warnings), () -> {
                assertThat(ToolIdentity.probeOnce(exe, tmp, null, 300).timedOut())
                        .isTrue();
                assertThat(ToolIdentity.probeOnce(exe, tmp, null, 300).timedOut())
                        .isTrue();
            });
            assertThat(runs(count)).as("asked twice, then remembered").isEqualTo(2);
            assertThat(warnings)
                    .containsExactly(ToolIdentity.HUNG_CODE + " `flaky --version` did not respond in time. It has"
                            + " been flagged as hung.");

            clock.advance(ToolIdentity.HUNG_FOR.minusSeconds(1));
            assertThat(ToolIdentity.probeOnce(exe, tmp, null, 300).timedOut()).isTrue();
            assertThat(runs(count)).as("within thirty minutes").isEqualTo(2);
            clock.advance(Duration.ofSeconds(2));
            StepScope.run(warnings(warnings), () -> ToolIdentity.probeOnce(exe, tmp, null, 300));
            assertThat(runs(count)).as("asked again after thirty minutes").isEqualTo(4);
            assertThat(warnings).as("one warning per tool per engine life").hasSize(1);
        } finally {
            ToolIdentity.clockForTests(null);
        }
    }

    @Test
    void a_redo_or_a_force_asks_a_hung_tool_again(@TempDir Path tmp) throws Exception {
        assumeFalse(Os.isWindows(), "a shell script tool is POSIX-only");
        ToolIdentity.forgetForTests();
        Path count = tmp.resolve("count");
        Path exe = flaky(Files.createDirectories(tmp.resolve("bin")), count, 2);
        assertThat(ToolIdentity.probeOnce(exe, tmp, null, 300).timedOut()).isTrue();
        assertThat(ToolIdentity.probeOnce(exe, tmp, null, 300).timedOut()).isTrue();
        assertThat(runs(count)).isEqualTo(2);
        for (JkConfig config :
                List.of(JkConfig.empty().withRebuild(true), JkConfig.empty().withForce(true))) {
            Session plain = SessionContext.current();
            SessionContext.install(plain.withConfig(config));
            try {
                assertThat(ToolIdentity.probeOnce(exe, tmp, null, 1000).line()).isEqualTo("flaky 9.9");
            } finally {
                SessionContext.install(plain);
            }
        }
        assertThat(runs(count)).isEqualTo(4);
    }

    @Test
    void a_tool_with_no_version_line_keys_by_its_bytes(@TempDir Path tmp) throws Exception {
        assumeFalse(Os.isWindows(), "a shell script tool is POSIX-only");
        Path exe = tool(Files.createDirectories(tmp.resolve("bin")), "quiet", "exit 0");
        String first = ToolIdentity.keyed(ToolIdentity.NO_VERSION, exe);
        assertThat(first).startsWith("no-version sha256:").hasSize("no-version sha256:".length() + 16);
        assertThat(ToolIdentity.keyed("quiet 1.0", exe)).isEqualTo("quiet 1.0");
        Files.writeString(exe, "#!/bin/sh\n# another build\nexit 0\n");
        assertThat(ToolIdentity.keyed(ToolIdentity.NO_VERSION, exe))
                .as("new bytes, new key")
                .startsWith("no-version sha256:")
                .isNotEqualTo(first);
    }

    /** A step that records its warnings as {@code code message}. */
    private static TaskContext warnings(List<String> into) {
        return (TaskContext) Proxy.newProxyInstance(
                TaskContext.class.getClassLoader(), new Class<?>[] {TaskContext.class}, (proxy, m, args) -> {
                    if (m.getName().equals("warn") && args != null && args.length == 2)
                        into.add(args[0] + " " + args[1]);
                    if (m.getReturnType() == boolean.class) return false;
                    if (m.getReturnType() == Optional.class) return Optional.empty();
                    return null;
                });
    }
}
