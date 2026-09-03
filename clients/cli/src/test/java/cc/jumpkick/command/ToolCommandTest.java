// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.util.JkOwnership;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

    /** A leaf name under jk's private bin directory is owned regardless of launcher contents. */
    @Test
    void uninstall_removes_a_launcher_without_the_attribution_header(@TempDir Path tempDir) throws Exception {
        writeEnvJson(tempDir, "widget", "com.example:widget-cli:1.0.0");
        Path bin = Files.createDirectories(tempDir.resolve("bin"));
        Path launcher = bin.resolve("widget");
        Files.writeString(launcher, "#!/bin/sh\n# an older jk wrote this without a header\n");

        int exit = Jk.execute(
                "tool", "uninstall", "widget", "--state-dir", tempDir.toString(), "--bin-dir", bin.toString());

        assertThat(exit).isEqualTo(0);
        assertThat(tempDir.resolve("tools/envs/widget")).doesNotExist();
        assertThat(launcher).doesNotExist();
    }

    /** Nothing installed under that name, and nothing in the bin dir: say so and touch nothing. */
    @Test
    void uninstall_of_a_name_jk_never_installed_is_a_noop(@TempDir Path tempDir) throws Exception {
        Path bin = Files.createDirectories(tempDir.resolve("bin"));
        Path sibling = bin.resolve("ripgrep");
        Files.writeString(sibling, "#!/bin/sh\nexec /usr/bin/rg \"$@\"\n");

        int exit = Jk.execute(
                "tool", "uninstall", "widget", "--state-dir", tempDir.toString(), "--bin-dir", bin.toString());

        assertThat(exit).isEqualTo(0);
        assertThat(sibling).as("a different name is never touched").exists();
    }

    @Test
    void uninstall_rejects_path_shaped_names_without_deleting_outside_roots(@TempDir Path tempDir) throws Exception {
        Path state = Files.createDirectories(tempDir.resolve("state/tools/envs"));
        Path bin = Files.createDirectories(tempDir.resolve("bin"));
        Path sentinel = tempDir.resolve("sentinel");
        Files.writeString(sentinel, "keep");

        for (String malicious : List.of("../sentinel", sentinel.toAbsolutePath().toString(), "nested\\tool")) {
            int exit = Jk.execute(
                    "tool",
                    "uninstall",
                    malicious,
                    "--state-dir",
                    tempDir.resolve("state").toString(),
                    "--bin-dir",
                    bin.toString());
            assertThat(exit).isEqualTo(Exit.USAGE);
            assertThat(sentinel).hasContent("keep");
            assertThat(state).isDirectory();
        }
    }

    /** The product's own entries in bin/ are never a tool's to remove, whatever sits there. */
    @Test
    void uninstall_refuses_jk_own_names_and_leaves_the_client_intact(@TempDir Path tempDir) throws Exception {
        Path bin = Files.createDirectories(tempDir.resolve("bin"));
        Path jk = bin.resolve("jk");
        Path jkx = bin.resolve("jkx");
        Files.writeString(jk, "native-binary-bytes");
        Files.writeString(jkx, "native-binary-bytes");
        Files.writeString(bin.resolve("VERSION"), "0.12.0\n");

        for (String own : List.of("jk", "jkx", "VERSION", "jk.old")) {
            int exit = Jk.execute(
                    "tool", "uninstall", own, "--state-dir", tempDir.toString(), "--bin-dir", bin.toString());
            assertThat(exit).as(own).isEqualTo(Exit.USAGE);
        }
        assertThat(jk).hasContent("native-binary-bytes");
        assertThat(jkx).hasContent("native-binary-bytes");
        assertThat(bin.resolve("VERSION")).hasContent("0.12.0\n");
    }

    /** What every launcher jk writes looks like: attribution on line two. */
    private static String jkLauncher() {
        return "#!/usr/bin/env bash\n# " + JkOwnership.GENERATED_BY
                + " install — do not edit.\nexec /bin/true \"$@\"\n";
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
    void install_rejects_a_path_shaped_launcher_name_before_writing(@TempDir Path tempDir) throws Exception {
        Path source = tempDir.resolve("tool.jar");
        Files.writeString(source, "not reached");
        Path state = tempDir.resolve("state");
        Path bin = tempDir.resolve("bin");

        int exit = Jk.execute(
                "tool",
                "install",
                "--m2-dir",
                tempDir.resolve("m2").toString(),
                source.toString(),
                "--bin",
                "../outside",
                "--state-dir",
                state.toString(),
                "--bin-dir",
                bin.toString(),
                "--cache-dir",
                tempDir.resolve("cache").toString());

        assertThat(exit).isEqualTo(Exit.USAGE);
        assertThat(state).doesNotExist();
        assertThat(bin).doesNotExist();
        assertThat(tempDir.resolve("outside")).doesNotExist();
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
