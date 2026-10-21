// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.codegraph.CodeGraph;
import cc.jumpkick.codegraph.GraphEdge;
import cc.jumpkick.codegraph.GraphJson;
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

    private static boolean edge(CodeGraph g, String from, String to, String relation) {
        return g.edges().stream()
                .anyMatch(e -> e.source().endsWith(from)
                        && e.target().endsWith(to)
                        && e.relation().equals(relation));
    }

    @Test
    void compiledCallsResolveThroughTheHierarchyAndLambdas(@TempDir Path dir) throws IOException {
        GraphBuild.Result r = GraphBuild.refresh(workspace(dir), GraphBuild.Progress.NONE);
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
        GraphBuild.Result first = GraphBuild.refresh(root, GraphBuild.Progress.NONE, LATER);
        assertThat(first.extracted()).isEqualTo(first.files());
        GraphBuild.Result second = GraphBuild.refresh(root, GraphBuild.Progress.NONE, LATER);
        assertThat(second.extracted()).isZero();
        Files.writeString(root.resolve("README.md"), "# Shop\n\nSee `PriceCalculator`.\n");
        GraphBuild.Result third = GraphBuild.refresh(root, GraphBuild.Progress.NONE, LATER);
        assertThat(third.extracted()).isEqualTo(1);
        assertThat(edge(third.graph(), "readme_shop", "pricecalculator_pricecalculator", "references"))
                .isTrue();
    }
}
