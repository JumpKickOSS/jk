// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.runtime.BuildGraph;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two members whose tests read each other ({@code [test-dependencies]} both ways) are a shape the
 * graph accepts: {@link BuildGraph.Result#readOrder} keeps one test edge and drops the one that
 * closes the loop. An edit to either member must reach the other, whose tests read it: in the
 * partial memo's cone, which follows every edge the graph reads.
 */
class MutualTestEdgeConeTest {

    @Test
    void an_edit_to_either_member_puts_the_other_in_the_cone(@TempDir Path ws) throws Exception {
        BuildGraph.Result graph = mutual(ws);
        Path infra = BuildGraph.canonicalPath(ws.resolve("infra"));
        Path arch = BuildGraph.canonicalPath(ws.resolve("arch"));
        assertThat(graph.errors()).isEmpty();
        assertThat(graph.reads()).containsEntry(infra, Set.of(arch)).containsEntry(arch, Set.of(infra));

        assertThat(BuildForecasting.downstreamCone(graph, Set.of(infra)))
                .as("arch's tests read infra: an edit to infra must reach arch")
                .contains(arch);
        assertThat(BuildForecasting.downstreamCone(graph, Set.of(arch)))
                .as("infra's tests read arch: an edit to arch must reach infra")
                .contains(infra);
    }

    /** A workspace of two members, infra and arch, whose tests read each other. */
    static BuildGraph.Result mutual(Path ws) throws Exception {
        Files.writeString(ws.resolve("jk.toml"), """
                group = "com.example"
                name  = "root"
                version = "1.0.0"
                java = 25

                [workspace]
                modules = ["infra", "arch"]
                """);
        member(ws, "infra", """
                [test-dependencies]
                arch = { workspace = true }
                """);
        member(ws, "arch", """
                [test-dependencies]
                infra = { workspace = true }
                """);
        return BuildGraph.resolve(ws, JkBuildParser.parse(ws.resolve("jk.toml")));
    }

    private static void member(Path ws, String name, String extra) throws Exception {
        Files.createDirectories(ws.resolve(name + "/src/main/java"));
        Files.writeString(ws.resolve(name + "/jk.toml"), """
                group = "com.example"
                name  = "%s"
                version = "1.0.0"
                java = 25

                """.formatted(name) + extra);
    }
}
