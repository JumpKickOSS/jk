// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.NodeTable;
import cc.jumpkick.node.NodeHome;
import cc.jumpkick.node.PackageManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** The argv of each node command, by package manager. */
@DisabledOnOs(OS.WINDOWS)
class NodeCommandsTest {

    @TempDir
    Path tmp;

    @Test
    void each_manager_installs_frozen() throws IOException {
        assertThat(tail(NodeCommands.install(npm(), null, ""), 4))
                .containsExactly("ci", "--no-audit", "--no-fund", "--prefer-offline");
        assertThat(tail(NodeCommands.install(managed(PackageManager.PNPM), null, ""), 2))
                .containsExactly("install", "--frozen-lockfile");
        assertThat(tail(NodeCommands.install(managed(PackageManager.YARN), null, ""), 2))
                .containsExactly("install", "--immutable");
        assertThat(tail(NodeCommands.install(managed(PackageManager.BUN), null, ""), 2))
                .containsExactly("install", "--frozen-lockfile");
    }

    @Test
    void an_install_override_runs_through_the_project_s_manager() throws IOException {
        NodeHome pnpm = managed(PackageManager.PNPM);
        List<String> argv = NodeCommands.install(pnpm, "pnpm install --frozen-lockfile --ignore-scripts", "");
        assertThat(argv.subList(0, pnpm.managerCommand().size())).isEqualTo(pnpm.managerCommand());
        assertThat(tail(argv, 3)).containsExactly("install", "--frozen-lockfile", "--ignore-scripts");
    }

    @Test
    void a_script_takes_extra_arguments_after_npm_s_separator_and_directly_elsewhere() throws IOException {
        NodeTable.Command test = NodeTable.Command.run("test");
        assertThat(tail(NodeCommands.command(npm(), test, List.of("--reporter=junit"), ""), 4))
                .containsExactly("run", "test", "--", "--reporter=junit");
        assertThat(tail(NodeCommands.command(managed(PackageManager.PNPM), test, List.of("--reporter=junit"), ""), 3))
                .containsExactly("run", "test", "--reporter=junit");
    }

    @Test
    void npx_resolves_from_node_modules_and_never_fetches() throws IOException {
        NodeTable.Command ng = NodeTable.Command.npx("ng build --configuration production");
        assertThat(tail(NodeCommands.command(npm(), ng, List.of(), ""), 7))
                .containsExactly("exec", "--no", "--", "ng", "build", "--configuration", "production");
        assertThat(tail(NodeCommands.command(managed(PackageManager.PNPM), ng, List.of(), ""), 5))
                .containsExactly("exec", "ng", "build", "--configuration", "production");
        assertThat(tail(NodeCommands.command(managed(PackageManager.YARN), ng, List.of(), ""), 5))
                .containsExactly("run", "ng", "build", "--configuration", "production");
    }

    @Test
    void exec_resolves_its_program_through_the_home_then_the_path() throws IOException {
        NodeHome home = npm();
        assertThat(NodeCommands.command(
                        home, new NodeTable.Command(NodeTable.Command.Kind.EXEC, "node tools/gen.js"), List.of(), ""))
                .containsExactly(home.node().toString(), "tools/gen.js");
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path tool = Files.writeString(bin.resolve("protoc"), "#!/bin/sh\n");
        tool.toFile().setExecutable(true);
        assertThat(NodeCommands.command(
                        home,
                        new NodeTable.Command(NodeTable.Command.Kind.EXEC, "protoc --version"),
                        List.of(),
                        bin.toString()))
                .containsExactly(tool.toString(), "--version");
    }

    @Test
    void a_command_line_splits_as_a_shell_splits_a_simple_command() {
        assertThat(NodeCommands.split("ng build  --base-href '/my app/' \"a \\\"b\\\"\" c\\ d"))
                .containsExactly("ng", "build", "--base-href", "/my app/", "a \"b\"", "c d");
        assertThat(NodeCommands.split("  ")).isEmpty();
    }

    private NodeHome npm() {
        return new NodeHome(tmp.resolve("node"), "24.1.0", "jk", null);
    }

    private NodeHome managed(PackageManager manager) throws IOException {
        Path home = Files.createDirectories(tmp.resolve(manager.id()));
        String slug = manager.id();
        Files.writeString(home.resolve("package.json"), "{\"bin\":{\"" + slug + "\":\"bin/" + slug + ".cjs\"}}");
        return npm().withManager(new NodeHome.ManagerHome(manager, "1.0.0", home));
    }

    private static List<String> tail(List<String> argv, int n) {
        return argv.subList(argv.size() - n, argv.size());
    }
}
