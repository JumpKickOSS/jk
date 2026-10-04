// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

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
    void uninstall_of_what_jk_never_installed_is_an_error_and_never_deletes() throws Exception {
        assertThat(NodeTool.uninstall("24.98.7", true)).containsKey("error");
        assertThat(NodeTool.uninstall(null, true)).containsKey("error");
    }
}
