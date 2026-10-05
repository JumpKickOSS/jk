// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.BuildTool;
import cc.jumpkick.compat.ToolRegistry;
import cc.jumpkick.node.NodeDiscovery;
import cc.jumpkick.node.NodeInstalls;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The {@code node} MCP tool's answers that read no network: the project's Node.js and the confirm gate. */
class NodeToolTest {

    @Test
    void which_names_what_the_project_wants_and_whether_it_is_installed(@TempDir Path project) throws Exception {
        Files.writeString(
                project.resolve("jk.toml"), "name = \"web\"\ngroup = \"g\"\nversion = \"1.0\"\nnode = \"=24.98.7\"\n");
        Map<String, Object> data = NodeTool.which(project);
        assertThat(data).containsEntry("wanted", "=24.98.7").containsEntry("installed", false);
    }

    @Test
    void which_without_a_declared_node_is_an_error(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("jk.toml"), "name = \"web\"\ngroup = \"g\"\nversion = \"1.0\"\n");
        assertThat(NodeTool.which(project)).containsKey("error");
    }

    @Test
    void uninstall_without_confirm_previews_and_with_it_removes(@TempDir Path tools) throws Exception {
        ToolRegistry registry = new ToolRegistry(tools);
        Path home = Files.createDirectories(
                registry.installDir(BuildTool.NODE, "24.98.7").resolve("bin"));
        Files.writeString(home.resolve("node"), "#!/bin/sh\n");
        NodeInstalls installs = new NodeInstalls(registry, new NodeDiscovery());

        Map<String, Object> preview = NodeTool.uninstall(installs, "24.98.7", false);
        assertThat(preview).containsEntry("preview", true).containsEntry("version", "24.98.7");
        assertThat(home.resolve("node")).as("a preview removes nothing").exists();

        assertThat(NodeTool.uninstall(installs, "v24.98.7", true)).containsEntry("removed", "24.98.7");
        assertThat(registry.installDir(BuildTool.NODE, "24.98.7")).doesNotExist();
    }

    @Test
    void uninstall_of_what_jk_never_installed_is_an_error_and_never_deletes() throws Exception {
        assertThat(NodeTool.uninstall("24.98.7", true)).containsKey("error");
        assertThat(NodeTool.uninstall(null, true)).containsKey("error");
    }
}
