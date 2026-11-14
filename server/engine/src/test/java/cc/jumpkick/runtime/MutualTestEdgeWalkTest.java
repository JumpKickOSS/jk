// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The forecast walk follows {@link BuildGraph.Result#readOrder}, which keeps one of two test edges
 * between members whose tests read each other. The member it dropped an edge for may be priced
 * before its sibling turns dirty, so the walk prices it again once the sibling's verdict is in.
 */
class MutualTestEdgeWalkTest {

    @Test
    void the_walk_prices_again_the_member_whose_test_edge_it_dropped(@TempDir Path ws) throws Exception {
        BuildGraph.Result graph = mutual(ws);
        Path infra = BuildGraph.canonicalPath(ws.resolve("infra"));
        Path arch = BuildGraph.canonicalPath(ws.resolve("arch"));
        Map<Path, Set<Path>> reads = graph.reads();
        Map<Path, Set<Path>> order = graph.readOrder();
        // Whichever edge the order kept, the member it dropped one for is priced again once the
        // other is dirty, and the member it kept the edge for never needs to be.
        Path dropped = order.getOrDefault(infra, Set.of()).contains(arch) ? arch : infra;
        Path kept = dropped.equals(arch) ? infra : arch;
        assertThat(TaskForecaster.pricedBeforeATestPrereq(reads, order, dropped, Set.of(kept)))
                .as("its tests read the dirty sibling over the edge the walk dropped")
                .isTrue();
        assertThat(TaskForecaster.pricedBeforeATestPrereq(reads, order, kept, Set.of(dropped)))
                .as("the walk ordered it after its dirty sibling already")
                .isFalse();
        assertThat(TaskForecaster.pricedBeforeATestPrereq(reads, order, dropped, Set.of()))
                .as("nothing dirty, nothing to price again")
                .isFalse();
    }

    private static BuildGraph.Result mutual(Path ws) throws Exception {
        Files.writeString(ws.resolve("jk.toml"), """
                group = "com.example"
                name  = "root"
                version = "1.0.0"
                java = 25

                [workspace]
                modules = ["infra", "arch"]
                """);
        member(ws, "infra", "arch");
        member(ws, "arch", "infra");
        return BuildGraph.resolve(ws, JkBuildParser.parse(ws.resolve("jk.toml")));
    }

    private static void member(Path ws, String name, String testReads) throws Exception {
        Files.createDirectories(ws.resolve(name + "/src/main/java"));
        Files.writeString(ws.resolve(name + "/jk.toml"), """
                group = "com.example"
                name  = "%s"
                version = "1.0.0"
                java = 25

                [test-dependencies]
                %s = { workspace = true }
                """.formatted(name, testReads));
    }
}
