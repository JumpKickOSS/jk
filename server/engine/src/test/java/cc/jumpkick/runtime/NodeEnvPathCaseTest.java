// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.node.NodeHome;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A node step's environment on Windows, where the engine's search path is spelled {@code Path}: the
 * provisioned Node goes in front of that search path rather than replacing it.
 */
class NodeEnvPathCaseTest {

    @Test
    void node_home_is_prepended_to_a_windows_path_spelled_Path(@TempDir Path tmp) {
        // What WorkerEnv.environment() hands a node step on Windows: case-insensitive, spelled Path.
        Map<String, String> worker = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        worker.put("Path", "C:\\Windows\\System32");
        worker.put("SystemRoot", "C:\\Windows");
        NodeHome home = new NodeHome(tmp.resolve("node"), "24.1.0", "jk", null);

        Map<String, String> env = NodeEnv.withNode(worker, home, true, true);

        assertThat(env.keySet().stream().filter(k -> k.equalsIgnoreCase("PATH")))
                .as("one search-path entry, as Windows holds one")
                .containsExactly("Path");
        assertThat(env.get("Path"))
                .as("Node first, the system search path kept")
                .startsWith(home.pathPrefix().getFirst().toString())
                .endsWith("C:\\Windows\\System32");
        assertThat(env).containsEntry("NODE_ENV", "production");
    }

    @Test
    void a_posix_path_gets_node_in_front(@TempDir Path tmp) {
        NodeHome home = new NodeHome(tmp.resolve("node"), "24.1.0", "jk", null);

        Map<String, String> env = NodeEnv.withNode(Map.of("PATH", "/usr/bin"), home, false, false);

        assertThat(env.get("PATH"))
                .startsWith(home.pathPrefix().getFirst().toString())
                .endsWith("/usr/bin");
        assertThat(env).doesNotContainKey("NODE_ENV");
    }
}
