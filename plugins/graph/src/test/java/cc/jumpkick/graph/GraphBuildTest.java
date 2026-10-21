// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.codegraph.CodeGraph;
import cc.jumpkick.codegraph.GraphEdge;
import cc.jumpkick.codegraph.GraphJson;
import cc.jumpkick.guard.extract.FactsIndexing;
import cc.jumpkick.host.time.Clock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GraphBuildTest {

    private static final Map<String, String> SOURCES = Map.of(
            "Auditable.java", """
                    package com.acme.shop;

                    public interface Auditable {
                        void audit(String what);
                    }
                    """,
            "BaseService.java", """
                    package com.acme.shop;

                    public abstract class BaseService {
                        protected void log(String message) {
                            System.out.println(message);
                        }
                    }
                    """,
            "PriceCalculator.java", """
                    package com.acme.shop;

                    import java.util.List;

                    public class PriceCalculator {
                        public long sum(List<Long> cents) {
                            return cents.stream().mapToLong(Long::longValue).sum();
                        }
                    }
                    """,
            "OrderService.java", """
                    package com.acme.shop;

                    import java.util.List;

                    /** Places orders. */
                    public class OrderService extends BaseService implements Auditable {
                        private final PriceCalculator calculator = new PriceCalculator();

                        public long total(List<Long> cents) {
                            audit("total");
                            return calculator.sum(cents);
                        }

                        public long total(long one) {
                            return total(List.of(one));
                        }

                        @Override
                        public void audit(String what) {
                            Runnable r = () -> log(what);
                            r.run();
                        }
                    }
                    """);

    private static final ScriptReader NO_NODE = new ScriptReader(null, null);

    /** A clock far past every file's mtime, so no file counts as just written. */
    private static final Clock LATER = new Clock() {
        @Override
        public long millis() {
            return Long.MAX_VALUE / 2;
        }

        @Override
        public long nanos() {
            return 0;
        }
    };

    private static Path workspace(Path dir) throws IOException {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.acme"
                name = "shop"
                version = "1.0.0"
                java = 25
                """);
        Path pkg = Files.createDirectories(dir.resolve("src/main/java/com/acme/shop"));
        List<String> paths = new ArrayList<>();
        for (Map.Entry<String, String> e : SOURCES.entrySet()) {
            Path f = pkg.resolve(e.getKey());
            Files.writeString(f, e.getValue());
            paths.add(f.toString());
        }
        Files.writeString(dir.resolve("README.md"), "# Shop\n\nTotals come from `OrderService`.\n");
        Path classes = Files.createDirectories(dir.resolve("target/classes"));
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        List<String> args = new ArrayList<>(List.of("-g", "-d", classes.toString()));
        args.addAll(paths);
        assertThat(javac.run(null, null, null, args.toArray(String[]::new))).isZero();
        return dir;
    }

    /** The engine's half: the root module's facts index, brought current from target/classes. */
    private static List<GraphBuild.ClassIndex> indexes(Path root) throws IOException {
        Path classes = root.resolve("target/classes");
        FactsIndexing.Ensured e =
                FactsIndexing.ensure(classes, FactsIndexing.indexPath(root.resolve("target"), "main"));
        return List.of(new GraphBuild.ClassIndex(e.index(), "", e.bodyDigest(), e.classes()));
    }

    private static GraphBuild.Result refresh(Path root, boolean rebuild) throws IOException {
        return GraphBuild.refresh(
                root, root.resolve("target/graph"), indexes(root), NO_NODE, rebuild, GraphBuild.Progress.NONE, LATER);
    }

    private static boolean edge(CodeGraph g, String from, String to, String relation) {
        return g.edges().stream()
                .anyMatch(e -> e.source().endsWith(from)
                        && e.target().endsWith(to)
                        && e.relation().equals(relation));
    }

    @Test
    void compiledCallsResolveThroughTheHierarchyAndLambdas(@TempDir Path dir) throws IOException {
        GraphBuild.Result r = refresh(workspace(dir), false);
        CodeGraph g = r.graph();
        assertThat(r.classes()).isGreaterThanOrEqualTo(4);
        assertThat(edge(g, "orderservice_total", "orderservice_audit", "calls")).isTrue();
        assertThat(edge(g, "orderservice_total", "pricecalculator_sum", "calls"))
                .isTrue();
        assertThat(edge(g, "orderservice_audit", "baseservice_log", "calls")).isTrue();
        assertThat(edge(g, "orderservice_orderservice", "baseservice_baseservice", "inherits"))
                .isTrue();
        assertThat(edge(g, "orderservice_orderservice", "pricecalculator_pricecalculator", "references"))
                .isTrue();
        assertThat(edge(g, "readme_shop", "orderservice_orderservice", "references"))
                .isTrue();
        assertThat(g.edges())
                .filteredOn(e -> e.relation().equals("calls"))
                .extracting(GraphEdge::confidence)
                .allMatch(c -> c.name().equals("EXTRACTED"));
        assertThat(g.node("src_main_java_com_acme_shop_orderservice_orderservice_total"))
                .satisfies(n -> assertThat(n.sourceLocation()).isEqualTo("L9"));
        String json = Files.readString(r.dir().resolve("graph.json"));
        assertThat(GraphJson.read(json).size()).isEqualTo(g.size());
        assertThat(r.dir().resolve("GRAPH_REPORT.md")).exists();
    }

    @Test
    void anUnchangedTreeExtractsNothingAndAnEditExtractsOneFile(@TempDir Path dir) throws IOException {
        Path root = workspace(dir);
        GraphBuild.Result first = refresh(root, false);
        assertThat(first.extracted()).isEqualTo(first.files());
        GraphBuild.Result second = refresh(root, false);
        assertThat(second.extracted()).isZero();
        assertThat(second.changed()).isFalse();
        assertThat(second.graph().size()).isEqualTo(first.graph().size());
        assertThat(second.graph().edges()).hasSameSizeAs(first.graph().edges());
        Files.writeString(root.resolve("README.md"), "# Shop\n\nSee `PriceCalculator`.\n");
        GraphBuild.Result third = refresh(root, false);
        assertThat(third.extracted()).isEqualTo(1);
        assertThat(third.changed()).isTrue();
        assertThat(edge(third.graph(), "readme_shop", "pricecalculator_pricecalculator", "references"))
                .isTrue();
    }

    @Test
    void theServiceAnswersEveryOperationFromOneRefresh(@TempDir Path dir) throws IOException {
        Path root = workspace(dir);
        assertThat(run(root, request("query", "how is the order total computed")))
                .contains("NODE ")
                .contains(".total()");
        assertThat(run(root, request("path", "OrderService", "BaseService"))).startsWith("Shortest path (1 hops):");
        assertThat(run(root, request("explain", "PriceCalculator"))).contains("Node: PriceCalculator");
        assertThat(run(root, request("stats"))).startsWith("Nodes: ");
        assertThat(run(root, request("god-nodes"))).startsWith("God nodes (most connected):");
        GraphService.Request stored = new GraphService.Request(
                "stats", List.of(), "bfs", 3, 2000, List.of(), false, false, 8, 10, null, false, true);
        assertThat(run(root, stored)).startsWith("Nodes: ");
    }

    private static String run(Path root, GraphService.Request r) throws IOException {
        return GraphService.run(root, root.resolve("target/graph"), indexes(root), NO_NODE, r, GraphBuild.Progress.NONE)
                .text();
    }

    private static GraphService.Request request(String op, String... args) {
        return new GraphService.Request(
                op, List.of(args), "bfs", 3, 2000, List.of(), false, false, 8, 10, null, false, false);
    }
}
