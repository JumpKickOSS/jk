// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.graph;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.BuildLogicToml;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.engine.plugin.WarmWorkers;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.ModuleInputProvenance;
import cc.jumpkick.wire.protocol.GraphAck;
import cc.jumpkick.wire.protocol.GraphProgress;
import cc.jumpkick.wire.protocol.GraphRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The engine half and the forked graph worker together: facts indexes in, graph and answers out. */
@Tag("integration")
class GraphRunnerTest {

    @AfterAll
    static void stopWorkers() {
        WarmWorkers.shutdown();
    }

    private static Path workspace(Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.acme"
                name = "shop"
                version = "1.0.0"
                java = 25
                """);
        Path pkg = Files.createDirectories(dir.resolve("src/main/java/com/acme/shop"));
        Files.writeString(pkg.resolve("PriceCalculator.java"), """
                package com.acme.shop;

                public class PriceCalculator {
                    public long sum(long a, long b) {
                        return a + b;
                    }
                }
                """);
        Files.writeString(pkg.resolve("OrderService.java"), """
                package com.acme.shop;

                public class OrderService {
                    private final PriceCalculator calculator = new PriceCalculator();

                    public long total(long a, long b) {
                        return calculator.sum(a, b);
                    }
                }
                """);
        Files.writeString(dir.resolve("README.md"), "# Shop\n\nSee `OrderService`.\n");
        Path classes = Files.createDirectories(dir.resolve("target/classes"));
        int exit = ToolProvider.getSystemJavaCompiler()
                .run(
                        null,
                        null,
                        null,
                        "-g",
                        "-d",
                        classes.toString(),
                        pkg.resolve("PriceCalculator.java").toString(),
                        pkg.resolve("OrderService.java").toString());
        assertThat(exit).isZero();
        // What a successful build leaves beside its classes: the record the graph reads them by.
        BuildGraph.Result graph =
                BuildGraph.resolve(dir, JkBuildParser.parse(Files.readString(dir.resolve("jk.toml"))));
        ModuleInputProvenance.recordClasses(
                dir, graph, Set.of(dir.toAbsolutePath().normalize()));
        return dir;
    }

    private static GraphRequest request(Path dir, String op, List<String> args) {
        return new GraphRequest(
                dir.toString(),
                op,
                args,
                "bfs",
                3,
                2000,
                List.of(),
                true,
                8,
                null,
                false,
                false,
                null,
                null,
                null,
                List.of());
    }

    /**
     * A build keeps an existing graph current and makes none: the first graph question makes one,
     * and deleting .jk/graph/ is how a project stops.
     */
    @Test
    void aBuildRefreshesOnlyAGraphTheProjectHas(@TempDir Path tmp) throws Exception {
        Path root = workspace(tmp);
        GraphRunner.refreshExisting(root);
        assertThat(GraphRunner.hasGraph(root)).isFalse();

        assertThat(GraphRunner.run(request(root, "build", List.of()), p -> {}).error())
                .isNull();
        assertThat(GraphRunner.hasGraph(root)).isTrue();
        // Beside the build scripts, never one of them: only .jk/scripts/ is build-logic input.
        assertThat(BuildLogicToml.dirs(root)).isEmpty();
        Path store = root.resolve(".jk/graph/" + GraphRunner.STORE);
        Files.writeString(
                root.resolve("src/main/java/com/acme/shop/PriceCalculator.java"),
                Files.readString(root.resolve("src/main/java/com/acme/shop/PriceCalculator.java")) + "// edited\n");
        String before = Files.readString(store);
        GraphRunner.refreshExisting(root);
        assertThat(Files.readString(store)).isNotEqualTo(before);

        // After a build the refresh runs behind the verdict, and still lands.
        Files.writeString(
                root.resolve("src/main/java/com/acme/shop/PriceCalculator.java"),
                Files.readString(root.resolve("src/main/java/com/acme/shop/PriceCalculator.java")) + "// again\n");
        String refreshed = Files.readString(store);
        GraphRunner.refreshAfterBuild(root);
        long deadline = System.currentTimeMillis() + 60_000;
        while (Files.readString(store).equals(refreshed) && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(Files.readString(store)).isNotEqualTo(refreshed);

        PathUtil.deleteRecursively(root.resolve(".jk/graph"));
        GraphRunner.refreshExisting(root);
        assertThat(GraphRunner.hasGraph(root)).isFalse();
    }

    @Test
    void buildsInTheWorkerAndAnswersFromTheStoredGraphWhenNothingChanged(@TempDir Path tmp) throws Exception {
        Path root = workspace(tmp);
        List<GraphProgress> progress = new ArrayList<>();
        GraphAck built = GraphRunner.run(request(root, "build", List.of()), progress::add);
        assertThat(built.error()).isNull();
        assertThat(built.files()).isEqualTo(4);
        assertThat(built.classes()).isEqualTo(2);
        assertThat(built.extracted()).isEqualTo(4);
        assertThat(progress).isNotEmpty();
        assertThat(root.resolve(".jk/graph/" + GraphRunner.STORE)).isRegularFile();
        assertThat(GraphRunner.hasGraph(root)).isTrue();

        GraphAck query = GraphRunner.run(request(root, "query", List.of("order service total")), p -> {});
        assertThat(query.extracted()).isZero();
        assertThat(query.text()).contains("\nOrderService  ").contains(" -calls-> PriceCalculator.sum()  ");

        GraphAck path = GraphRunner.run(request(root, "path", List.of("README.md", "PriceCalculator")), p -> {});
        assertThat(path.text()).startsWith("Shortest path");

        GraphAck rebuilt = GraphRunner.run(
                new GraphRequest(
                        root.toString(),
                        "build",
                        List.of(),
                        null,
                        3,
                        2000,
                        List.of(),
                        false,
                        8,
                        null,
                        true,
                        false,
                        null,
                        null,
                        null,
                        List.of()),
                p -> {});
        assertThat(rebuilt.extracted()).isEqualTo(4);
    }
}
