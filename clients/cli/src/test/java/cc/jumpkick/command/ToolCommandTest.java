// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.util.JkOwnership;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class ToolCommandTest {

    @Test
    void dir_prints_tools_root(@TempDir Path tempDir) {
        Path tools = tempDir.resolve("tools");
        String stdout = Capture.stdout(() -> Jk.execute("tool", "dir", "--tools-dir", tools.toString()));
        assertThat(stdout.trim()).isEqualTo(tools.toString());
    }

    @Test
    void list_reports_empty_when_nothing_installed(@TempDir Path tempDir) {
        String stdout = Capture.stdout(() -> Jk.execute(
                "tool",
                "list",
                "--state-dir",
                tempDir.toString(),
                "--bin-dir",
                tempDir.resolve("bin").toString()));
        assertThat(stdout).contains("No tools installed");
    }

    @Test
    void list_reports_installed_tools(@TempDir Path tempDir) throws Exception {
        writeEnvJson(tempDir, "widget", "com.example:widget-cli:1.0.0");
        writeEnvJson(tempDir, "alpha", "org.foo:alpha:0.1.0");
        // Add a launcher for one but not the other.
        Path bin = tempDir.resolve("bin");
        Files.createDirectories(bin);
        Files.writeString(bin.resolve("widget"), "#!/bin/sh\n");

        String stdout = Capture.stdout(
                () -> Jk.execute("tool", "list", "--state-dir", tempDir.toString(), "--bin-dir", bin.toString()));
        // Sorted alphabetically: alpha first, then widget.
        assertThat(stdout)
                .containsSubsequence("alpha", "org.foo:alpha:0.1.0", "widget", "com.example:widget-cli:1.0.0");
        assertThat(stdout).contains(bin.resolve("widget").toString());
    }

    @Test
    void uninstall_removes_env_and_launcher(@TempDir Path tempDir) throws Exception {
        writeEnvJson(tempDir, "widget", "com.example:widget-cli:1.0.0");
        Path bin = tempDir.resolve("bin");
        Files.createDirectories(bin);
        Path launcher = bin.resolve("widget");
        Files.writeString(launcher, jkLauncher());

        int exit = Jk.execute(
                "tool", "uninstall", "widget", "--state-dir", tempDir.toString(), "--bin-dir", bin.toString());
        assertThat(exit).isEqualTo(0);
        assertThat(launcher).doesNotExist();
        assertThat(tempDir.resolve("tools/envs/widget")).doesNotExist();
    }

    @Test
    void uninstall_leaves_a_launcher_jk_did_not_write(@TempDir Path tempDir) throws Exception {
        // JK-2626. --bin-dir defaults to ~/.local/bin, which on a real machine holds the distro's
        // binaries, pip and npm shims and symlinks into other tools. Matching on the NAME deleted
        // the user's binary and printed "Removed <name>".
        Path bin = Files.createDirectories(tempDir.resolve("bin"));
        Path theirs = bin.resolve("ripgrep");
        Files.writeString(theirs, "#!/bin/sh\n# somebody else's script\nexec /usr/bin/rg \"$@\"\n");

        int exit = Jk.execute(
                "tool", "uninstall", "ripgrep", "--state-dir", tempDir.toString(), "--bin-dir", bin.toString());

        assertThat(exit).isNotZero();
        assertThat(theirs).as("their binary is left where it was").exists();
        assertThat(Files.readString(theirs)).contains("somebody else's script");
    }

    @Test
    void uninstall_removes_jks_env_but_keeps_a_foreign_launcher(@TempDir Path tempDir) throws Exception {
        // A jk env dir and a same-named binary jk did not write: the env goes, the binary stays,
        // and the mismatch is said out loud rather than leaving the tool looking half-removed.
        writeEnvJson(tempDir, "widget", "com.example:widget-cli:1.0.0");
        Path bin = Files.createDirectories(tempDir.resolve("bin"));
        Path theirs = bin.resolve("widget");
        Files.writeString(theirs, "#!/bin/sh\n# not ours\n");

        int exit = Jk.execute(
                "tool", "uninstall", "widget", "--state-dir", tempDir.toString(), "--bin-dir", bin.toString());

        assertThat(exit).isEqualTo(0);
        assertThat(tempDir.resolve("tools/envs/widget")).doesNotExist();
        assertThat(theirs).exists();
    }

    /** What every launcher jk writes looks like: attribution on line two. */
    private static String jkLauncher() {
        return "#!/usr/bin/env bash\n# " + JkOwnership.GENERATED_BY + " install — do not edit.\nexec /bin/true \"$@\"\n";
    }

    @Test
    void uninstall_unknown_tool_is_a_noop(@TempDir Path tempDir) {
        String stdout = Capture.stdout(() -> Jk.execute(
                "tool",
                "uninstall",
                "ghost",
                "--state-dir",
                tempDir.toString(),
                "--bin-dir",
                tempDir.resolve("bin").toString()));
        assertThat(stdout).contains("not installed");
    }

    @Test
    void exec_is_a_hidden_alias_of_run() {
        // `jk tool exec --help` renders `jk tool run`'s help (dotnet muscle memory).
        String stdout = Capture.stdout(() -> Jk.execute("tool", "exec", "--help"));
        assertThat(stdout).contains("jk tool run");
        // Hidden per the hidden-surface policy: the parent's help lists only `run`.
        String toolHelp = Capture.stdout(() -> Jk.execute("tool", "--help"));
        assertThat(toolHelp).doesNotContain("exec");
    }

    private static void writeEnvJson(Path home, String bin, String coord) throws Exception {
        Path envDir = home.resolve("tools/envs/").resolve(bin);
        Files.createDirectories(envDir);
        Files.writeString(
                envDir.resolve("env.json"),
                "{\n  \"binName\": \"" + bin + "\",\n  \"primary\": \"" + coord + "\"\n}\n");
    }
}
