// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cli.engine.JvmClient;
import cc.jumpkick.host.Os;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The JVM client's install shape: one jar under {@code lib/jk}, one launcher on the PATH, and a
 * launcher text whose JVM order and flags are the documented ones. The installers only place the
 * jar and call {@code jk self write-launcher}, so what this asserts is the whole of what a host
 * without a native client gets.
 */
class JvmClientInstallTest {

    @Test
    void the_posix_launcher_is_plain_sh_and_prefers_the_installed_jdk_over_java_home(@TempDir Path tmp) {
        Path jar = tmp.resolve("lib/jk/jk-1.2.3.jar");
        Path java = tmp.resolve("jdk-25/bin/java");
        String script = JvmClientInstall.renderPosix(jar, java);
        List<String> lines = script.lines().toList();
        assertThat(lines.get(0)).isEqualTo("#!/bin/sh");
        assertThat(script)
                .contains("JK_JAR='" + jar.toAbsolutePath() + "'")
                .contains("-D" + JvmClient.JAR_PROPERTY + "=$JK_JAR")
                .contains("-Djk.argv0=$0")
                .contains("--enable-native-access=ALL-UNNAMED")
                .contains("${JK_CLIENT_OPTS:-}")
                .endsWith("-jar \"$JK_JAR\" \"$@\"\n");
        // JK_JAVA_HOME, then the installed JDK, then JAVA_HOME, then the PATH — in that order.
        int explicit = script.indexOf("JK_JAVA_HOME");
        int installed = script.indexOf("-x '" + java + "'");
        int javaHome = script.indexOf("\"${JAVA_HOME:-}\"");
        int path = script.indexOf("else JAVA=java");
        assertThat(explicit).isPositive();
        assertThat(installed).isGreaterThan(explicit);
        assertThat(javaHome).isGreaterThan(installed);
        assertThat(path).isGreaterThan(javaHome);
    }

    @Test
    void the_windows_launcher_is_ascii_crlf_batch_with_the_same_order() {
        Path jar = Path.of("C:\\Users\\x\\.jk\\lib\\jk\\jk-1.2.3.jar");
        Path java = Path.of("C:\\jdk-25\\bin\\java.exe");
        String script = JvmClientInstall.renderWindows(jar, java);
        assertThat(script).startsWith("@echo off\r\n");
        assertThat(script.chars().allMatch(c -> c < 128)).as("ASCII only").isTrue();
        assertThat(script.split("\r\n").length).isGreaterThan(5);
        assertThat(script.replace("\r\n", "")).doesNotContain("\n");
        assertThat(script)
                .contains("-D" + JvmClient.JAR_PROPERTY + "=%JK_JAR%")
                .contains("-Djk.argv0=%~n0")
                .contains("%JK_CLIENT_OPTS%")
                .contains("-jar \"%JK_JAR%\" %* & goto :jk_exit\r\n:jk_exit\r\n")
                .endsWith("exit /b %ERRORLEVEL%\r\n");
        assertThat(script.indexOf("JK_JAVA_HOME")).isLessThan(script.indexOf("if exist \"" + java + "\""));
        assertThat(script.indexOf("if exist \"" + java + "\"")).isLessThan(script.indexOf("defined JAVA_HOME"));
    }

    /**
     * {@code cmd} resumes a batch file at a byte offset after each line, so a launcher replaced
     * while its java line runs must not have its tail read out of the new file's bytes.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void a_launcher_replaced_while_it_runs_still_exits_with_the_client_s_code(@TempDir Path dir) throws Exception {
        Path launcher = dir.resolve("run.bat");
        Path replacement = dir.resolve("next.bat");
        Path swap = dir.resolve("swap.cmd");
        Files.writeString(
                swap,
                "@copy /y \"" + replacement + "\" \"" + launcher + ".new\" >nul\r\n"
                        + "@move /y \"" + launcher + ".new\" \"" + launcher + "\" >nul\r\n"
                        + "@exit /b 7\r\n");
        String original = JvmClientInstall.renderWindows(dir.resolve("a.jar"), dir.resolve("java.exe"))
                .replaceFirst(
                        "(?m)^\"%JAVA%\".*? & goto :jk_exit",
                        "call \"" + swap.toString().replace("\\", "\\\\") + "\" & goto :jk_exit");
        String longer = original.replace("setlocal\r\n", "setlocal\r\nrem " + "x".repeat(400) + "\r\necho TAIL\r\n");
        Files.writeString(launcher, original);
        Files.writeString(replacement, longer);
        Process p = new ProcessBuilder("cmd.exe", "/c", launcher.toString())
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor()).as(out).isEqualTo(7);
        assertThat(out).doesNotContain("TAIL");
    }

    @Test
    void a_quote_in_a_path_survives_the_posix_launcher() {
        Path jar = Path.of("/tmp/it's/jk-1.jar");
        String script = JvmClientInstall.renderPosix(jar, Path.of("/usr/bin/java"));
        assertThat(script).contains("it'\\''s");
    }

    @Test
    void install_jar_places_one_jar_and_retires_the_previous_version(@TempDir Path tmp) throws Exception {
        Path lib = tmp.resolve("lib/jk");
        Files.createDirectories(lib);
        Files.write(lib.resolve("jk-1.0.0.jar"), new byte[] {1});
        Files.write(lib.resolve("jk-engine-unrelated.txt"), new byte[] {3});

        Path placed = JvmClientInstall.installJar(new byte[] {9, 9}, lib, "1.1.0");

        assertThat(placed).isEqualTo(lib.resolve("jk-1.1.0.jar")).hasBinaryContent(new byte[] {9, 9});
        assertThat(lib.resolve("jk-1.0.0.jar")).doesNotExist();
        assertThat(lib.resolve("jk-engine-unrelated.txt"))
                .as("only the client's files are retired")
                .exists();
        assertThat(lib.resolve(".jk-1.1.0.jar-new")).as("the temp file is gone").doesNotExist();
    }

    @Test
    void write_launcher_parks_the_native_client_and_points_jkx_at_the_script(@TempDir Path tmp) throws Exception {
        Path bin = tmp.resolve("bin");
        Files.createDirectories(bin);
        Files.write(bin.resolve("jk"), new byte[] {0x7f, 'E', 'L', 'F'});
        Path jar = tmp.resolve("lib/jk/jk-1.2.3.jar");
        Files.createDirectories(jar.getParent());
        Files.write(jar, new byte[] {1});

        Path launcher = JvmClientInstall.writeLauncher(bin, jar, Path.of("/opt/jdk/bin/java"), false);

        assertThat(launcher).isEqualTo(bin.resolve("jk"));
        assertThat(Files.readString(launcher)).startsWith("#!/bin/sh\n");
        assertThat(Files.isExecutable(launcher)).isTrue();
        assertThat(bin.resolve("jk.old")).hasBinaryContent(new byte[] {0x7f, 'E', 'L', 'F'});
        assertThat(bin.resolve(".jk-new")).as("the staged sibling is gone").doesNotExist();
        Path jkx = Os.isWindows() ? bin.resolve("jkx.cmd") : bin.resolve("jkx");
        assertThat(jkx).exists();
        // A hardlink to the script (argv0 rides -Djk.argv0=$0), or a shim that execs it.
        boolean linked = Files.isSameFile(jkx, launcher);
        assertThat(linked || Files.readString(jkx).contains("\"" + launcher + "\" tool run"))
                .isTrue();
    }

    /**
     * The launcher is staged before anything live moves, so a staging path the text cannot be
     * written to (here a directory squatting on it, which a read-only bin would not tell apart
     * from a failed park) leaves the client the bin had: nothing is parked.
     */
    @Test
    void a_launcher_that_cannot_be_staged_keeps_the_previous_client_on_path(@TempDir Path tmp) throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Files.write(bin.resolve("jk"), new byte[] {0x7f, 'E', 'L', 'F'});
        Path jar = tmp.resolve("lib/jk/jk-1.2.3.jar");
        Files.createDirectories(jar.getParent());
        Files.write(jar, new byte[] {1});
        Path squatter = Files.createDirectories(bin.resolve(".jk-new"));
        Files.write(squatter.resolve("occupied"), new byte[] {1});

        assertThatThrownBy(() -> JvmClientInstall.writeLauncher(bin, jar, Path.of("/opt/jdk/bin/java"), false))
                .isInstanceOf(IOException.class);

        assertThat(bin.resolve("jk")).hasBinaryContent(new byte[] {0x7f, 'E', 'L', 'F'});
        try (var entries = Files.list(bin)) {
            assertThat(entries.map(p -> p.getFileName().toString()))
                    .as("nothing parked")
                    .containsExactlyInAnyOrder("jk", ".jk-new");
        }
    }

    @Test
    void the_windows_launcher_parks_a_leftover_exe_that_pathext_would_prefer(@TempDir Path tmp) throws Exception {
        Path bin = tmp.resolve("bin");
        Files.createDirectories(bin);
        Files.write(bin.resolve("jk.exe"), new byte[] {'M', 'Z'});
        Path jar = tmp.resolve("lib/jk/jk-1.2.3.jar");
        Files.createDirectories(jar.getParent());
        Files.write(jar, new byte[] {1});

        Path launcher = JvmClientInstall.writeLauncher(bin, jar, Path.of("C:\\jdk\\bin\\java.exe"), true);

        assertThat(launcher).isEqualTo(bin.resolve("jk.bat"));
        assertThat(bin.resolve("jk.exe")).doesNotExist();
        assertThat(bin.resolve("jk.exe.old")).exists();
        assertThat(bin.resolve(".jk.bat-new")).as("the staged sibling is gone").doesNotExist();
        assertThat(Files.readString(launcher)).startsWith("@echo off\r\n");
    }

    /**
     * Once a native client is installed over the JVM one, nothing of the JVM client is left for
     * {@code jk} to resolve to: on Windows the launcher and the jkx shim are parked beside the new
     * {@code jk.exe}; on POSIX the native {@code jk} is left alone. The jars go either way.
     */
    @Test
    void retire_leaves_only_the_native_client(@TempDir Path tmp) throws Exception {
        for (boolean windows : new boolean[] {true, false}) {
            Path home = tmp.resolve(windows ? "windows" : "posix");
            Path bin = Files.createDirectories(home.resolve("bin"));
            Path lib = Files.createDirectories(home.resolve("lib/jk"));
            Files.write(lib.resolve("jk-1.2.3.jar"), new byte[] {1});
            Files.writeString(bin.resolve("jk.bat"), "@echo off\r\n");
            Files.writeString(bin.resolve("jkx.cmd"), "@echo off\r\n");
            Path nativeClient = bin.resolve(windows ? "jk.exe" : "jk");
            Files.write(nativeClient, new byte[] {'M', 'Z'});

            JvmClientInstall.retire(bin, lib, windows);

            assertThat(nativeClient).hasBinaryContent(new byte[] {'M', 'Z'});
            assertThat(lib.resolve("jk-1.2.3.jar")).doesNotExist();
            if (windows) {
                assertThat(bin.resolve("jk.bat")).doesNotExist();
                assertThat(bin.resolve("jkx.cmd")).doesNotExist();
            }
        }
    }

    @Test
    void the_jar_and_launcher_names_follow_the_version_and_platform() {
        assertThat(JvmClientInstall.jarName("0.13.3")).isEqualTo("jk-0.13.3.jar");
        assertThat(JvmClientInstall.launcher(Path.of("/b"), false)).isEqualTo(Path.of("/b/jk"));
        assertThat(JvmClientInstall.launcher(Path.of("/b"), true)).isEqualTo(Path.of("/b/jk.bat"));
    }
}
