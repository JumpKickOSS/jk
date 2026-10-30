// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.codegraph.CodeGraph;
import cc.jumpkick.codegraph.Decl;
import cc.jumpkick.codegraph.GraphEdge;
import cc.jumpkick.codegraph.GraphNode;
import cc.jumpkick.codegraph.Modules;
import cc.jumpkick.codegraph.StaleRef;
import cc.jumpkick.guard.extract.FactsIndexing;
import cc.jumpkick.host.time.Clock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
        return workspace(dir, SOURCES);
    }

    private static Path workspace(Path dir, Map<String, String> sources) throws IOException {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.acme"
                name = "shop"
                version = "1.0.0"
                java = 25
                """);
        Path pkg = Files.createDirectories(dir.resolve("src/main/java/com/acme/shop"));
        List<String> paths = new ArrayList<>();
        for (Map.Entry<String, String> e : sources.entrySet()) {
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
    private static List<GraphBuild.ClassIndex> indexes(Path root, Set<String> stale) throws IOException {
        Path classes = root.resolve("target/classes");
        FactsIndexing.Ensured e =
                FactsIndexing.ensure(classes, FactsIndexing.indexPath(root.resolve("target"), "main"));
        return List.of(new GraphBuild.ClassIndex(e.index(), "", e.bodyDigest(), e.classes(), stale));
    }

    private static GraphBuild.Result refresh(Path root, boolean rebuild) throws IOException {
        return refresh(root, rebuild, Set.of());
    }

    private static GraphBuild.Result refresh(Path root, boolean rebuild, Set<String> stale) throws IOException {
        return GraphBuild.refresh(
                root,
                root.resolve("target/graph"),
                indexes(root, stale),
                Modules.NONE,
                NO_NODE,
                rebuild,
                GraphBuild.Progress.NONE,
                LATER);
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
        assertThat(r.dir().resolve(GraphOutputs.SNAPSHOT)).exists();
    }

    @Test
    void anImplementationOverridesItsInterfaceMethodSoTheInterfacesCallersReachIt(@TempDir Path dir)
            throws IOException {
        CodeGraph g = refresh(workspace(dir), false).graph();
        assertThat(edge(g, "orderservice_audit", "auditable_audit", "overrides"))
                .isTrue();
        assertThat(g.edges())
                .filteredOn(e -> e.relation().equals("overrides"))
                .as("a private, static or constructor member overrides nothing")
                .hasSize(1);
    }

    @Test
    void callsAndOverridesNameTheOverloadsTheyJoinAndAGenericOverrideIsFoundThroughItsBridge(@TempDir Path dir)
            throws IOException {
        CodeGraph g = refresh(
                        workspace(
                                dir,
                                Map.of(
                                        "Exec.java", """
                                        package com.acme.shop;

                                        public interface Exec<T> {
                                            void run(T t);
                                            String home();
                                            String home(int n);
                                        }
                                        """,
                                        "Spec.java", """
                                        package com.acme.shop;

                                        public class Spec implements Exec<String> {
                                            public void run(String s) {}
                                            public String home() { return "a"; }
                                            public String home(int n) { return "b"; }
                                        }
                                        """,
                                        "Step.java", """
                                        package com.acme.shop;

                                        public class Step {
                                            String one(Exec<String> e) { return e.home(); }
                                            String two(Exec<String> e) { return e.home(2); }
                                            void three(Exec<String> e) { Runnable r = () -> e.run("x"); r.run(); }
                                        }
                                        """)),
                        false)
                .graph();
        assertThat(overloads(g, "spec_run", "exec_run", "overrides"))
                .as("run(String) overrides run(T) as the bridge run(Object) says")
                .containsExactly("(Ljava/lang/String;)V>(Ljava/lang/Object;)V");
        assertThat(overloads(g, "spec_home", "exec_home", "overrides"))
                .containsExactly(
                        "()Ljava/lang/String;>()Ljava/lang/String;", "(I)Ljava/lang/String;>(I)Ljava/lang/String;");
        assertThat(overloads(g, "step_one", "exec_home", "calls"))
                .containsExactly("(Lcom/acme/shop/Exec;)Ljava/lang/String;>()Ljava/lang/String;");
        assertThat(overloads(g, "step_two", "exec_home", "calls"))
                .containsExactly("(Lcom/acme/shop/Exec;)Ljava/lang/String;>(I)Ljava/lang/String;");
        assertThat(overloads(g, "step_three", "exec_run", "calls"))
                .as("a lambda's call is made by the one method of its name")
                .containsExactly("(Lcom/acme/shop/Exec;)V>(Ljava/lang/Object;)V");
    }

    private static List<String> overloads(CodeGraph g, String from, String to, String relation) {
        return g.edges().stream()
                .filter(e -> e.source().endsWith(from)
                        && e.target().endsWith(to)
                        && e.relation().equals(relation))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + relation + " edge " + from + " -> " + to))
                .overloads();
    }

    @Test
    void aStaleSourceLosesOnlyItsOwnCompiledFacts(@TempDir Path dir) throws IOException {
        Path root = workspace(dir);
        CodeGraph calc = refresh(root, false, Set.of("src/main/java/com/acme/shop/PriceCalculator.java"))
                .graph();
        assertThat(edge(calc, "orderservice_total", "pricecalculator_sum", "calls"))
                .as("OrderService's classes still hold its call into the edited file")
                .isTrue();
        assertThat(edge(calc, "orderservice_audit", "baseservice_log", "calls")).isTrue();

        CodeGraph svc = refresh(root, false, Set.of("src/main/java/com/acme/shop/OrderService.java"))
                .graph();
        assertThat(edge(svc, "orderservice_total", "pricecalculator_sum", "calls"))
                .as("the edited file's own compiled calls are not read")
                .isFalse();
        assertThat(svc.node("src_main_java_com_acme_shop_orderservice_orderservice_total"))
                .as("its declarations still come from the source")
                .isNotNull();
    }

    @Test
    void sourceSpansAndClassFileFactsLandOnOneNode(@TempDir Path dir) throws IOException {
        CodeGraph g = refresh(workspace(dir), false).graph();
        String svc = "src_main_java_com_acme_shop_orderservice_orderservice";
        GraphNode type = Objects.requireNonNull(g.node(svc));
        assertThat(Decl.kind(type)).isEqualTo("class");
        assertThat(Decl.vis(type)).isEqualTo("public");
        assertThat(Decl.end(type)).isEqualTo(23);
        GraphNode total = Objects.requireNonNull(g.node(svc + "_total"));
        assertThat(Decl.kind(total)).isEqualTo("method");
        assertThat(Decl.end(total)).isEqualTo(12);
        assertThat(total.extra().get("attributes")).asString().contains("signatures");
        assertThat(Decl.annotations(Objects.requireNonNull(g.node(svc + "_audit"))))
                .containsExactly("Override");
        GraphNode log = Objects.requireNonNull(g.node("src_main_java_com_acme_shop_baseservice_baseservice_log"));
        assertThat(Decl.vis(log)).isEqualTo("protected");
        GraphNode auditable = Objects.requireNonNull(g.node("src_main_java_com_acme_shop_auditable_auditable"));
        assertThat(Decl.kind(auditable)).isEqualTo("interface");
    }

    /** -n answers from what was written; with nothing written it says so rather than build one. */
    @Test
    void noRefreshWithNoStoredGraphIsAnError(@TempDir Path dir) throws IOException {
        GraphMemory.clear();
        Path root = workspace(dir);
        GraphService.Request stored = new GraphService.Request(
                "query", List.of("order"), "bfs", 3, 2000, List.of(), false, 8, null, false, true);
        assertThatThrownBy(() -> GraphService.run(service(root), stored, GraphBuild.Progress.NONE))
                .hasMessageContaining("no graph has been built");
        assertThat(root.resolve("target/graph/" + GraphOutputs.SNAPSHOT)).doesNotExist();
    }

    /** A Java class's facts belong to the file in its package directory, never a same-named one elsewhere. */
    @Test
    void aJavaClassNeverTakesASameNamedFileInAnotherPackage() {
        Map<String, List<String>> byName = Map.of("Util.java", List.of("src/main/java/com/other/Util.java"));
        assertThat(GraphBuild.find(byName, "", "com/acme", "Util.java")).isNull();
        Map<String, List<String>> kotlin = Map.of("Util.kt", List.of("src/main/kotlin/Util.kt"));
        assertThat(GraphBuild.find(kotlin, "", "com/acme", "Util.kt")).isEqualTo("src/main/kotlin/Util.kt");
    }

    /** A file that will not read contributes nothing, and the fragment it had goes with it. */
    @Test
    void anUnreadableFileDropsItsEarlierFragment(@TempDir Path dir) throws Exception {
        GraphMemory.clear();
        Path root = workspace(dir);
        GraphBuild.Result first = refresh(root, false);
        assertThat(edge(first.graph(), "readme_shop", "orderservice_orderservice", "references"))
                .isTrue();
        Path readme = root.resolve("README.md");
        assumeTrue(readme.toFile().setReadable(false), "permissions can be dropped here");
        try {
            assumeFalse(Files.isReadable(readme), "this user reads everything");
            GraphBuild.Result second = refresh(root, false);
            assertThat(edge(second.graph(), "readme_shop", "orderservice_orderservice", "references"))
                    .isFalse();
        } finally {
            readme.toFile().setReadable(true);
        }
    }

    @Test
    void aLinkToAFileOutsideTheGraphIsNotStale(@TempDir Path dir) throws IOException {
        Path root = workspace(dir);
        Files.writeString(root.resolve("LICENSE"), "Apache-2.0\n");
        Files.writeString(root.resolve("README.md"), "# Shop\n\nSee [license](LICENSE) and [gone](gone.md).\n");
        GraphBuild.Result r = refresh(root, false);
        assertThat(r.graph().stale()).extracting(StaleRef::target).containsExactly("gone.md");
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

    /** {@code cp -p}, {@code touch -r}: new bytes behind the old size and mtime are read again. */
    @Test
    void anEditThatRestoresSizeAndMtimeIsExtracted(@TempDir Path dir) throws Exception {
        Path root = workspace(dir);
        Path readme = root.resolve("README.md");
        String before = Files.readString(readme);
        FileTime mtime = Files.getLastModifiedTime(readme);
        refresh(root, false);
        Thread.sleep(20);
        String after = before.replace("OrderService", "BaseService ");
        assertThat(after).hasSameSizeAs(before);
        Files.writeString(readme, after);
        Files.setLastModifiedTime(readme, mtime);

        GraphBuild.Result second = refresh(root, false);
        assertThat(second.extracted()).isEqualTo(1);
        assertThat(edge(second.graph(), "readme_shop", "baseservice_baseservice", "references"))
                .isTrue();
    }

    @Test
    void aServedWorkerAnswersAnUnchangedRefreshFromMemory(@TempDir Path dir) throws IOException {
        GraphMemory.clear();
        Path root = workspace(dir);
        GraphBuild.Result first = refresh(root, false);
        GraphBuild.Result second = refresh(root, false);
        assertThat(second.changed()).isFalse();
        assertThat(second.graph()).isSameAs(first.graph());
    }

    @Test
    void aGraphAnotherProcessWroteIsReadAgain(@TempDir Path dir) throws IOException {
        GraphMemory.clear();
        Path root = workspace(dir);
        GraphBuild.Result first = refresh(root, false);
        Path bin = first.dir().resolve(GraphOutputs.SNAPSHOT);
        Files.setLastModifiedTime(
                bin, FileTime.fromMillis(Files.getLastModifiedTime(bin).toMillis() + 5_000));
        GraphBuild.Result second = refresh(root, false);
        assertThat(second.changed()).isFalse();
        assertThat(second.graph()).isNotSameAs(first.graph());
        assertThat(second.graph().size()).isEqualTo(first.graph().size());
    }

    /** A snapshot of another layout, or a damaged one, is no graph: the next refresh builds it again. */
    @Test
    void aSnapshotThatDoesNotParseIsRebuilt(@TempDir Path dir) throws IOException {
        GraphMemory.clear();
        Path root = workspace(dir);
        GraphBuild.Result first = refresh(root, false);
        Path bin = first.dir().resolve(GraphOutputs.SNAPSHOT);
        byte[] whole = Files.readAllBytes(bin);
        Files.write(bin, Arrays.copyOf(whole, whole.length + 4));
        GraphMemory.clear();
        GraphService.Request stored = new GraphService.Request(
                "query", List.of("order"), "bfs", 3, 2000, List.of(), false, 8, null, false, true);
        assertThatThrownBy(() -> GraphService.run(service(root), stored, GraphBuild.Progress.NONE))
                .hasMessageContaining("no graph has been built");
        GraphBuild.Result second = refresh(root, false);
        assertThat(second.changed()).isTrue();
        assertThat(second.graph().size()).isEqualTo(first.graph().size());
    }

    @Test
    void anotherWorkspaceDropsTheFirstFromMemory(@TempDir Path dir) throws IOException {
        GraphMemory.clear();
        Path a = workspace(Files.createDirectories(dir.resolve("a")));
        GraphBuild.Result first = refresh(a, false);
        refresh(workspace(Files.createDirectories(dir.resolve("b"))), false);
        assertThat(refresh(a, false).graph()).isNotSameAs(first.graph());
    }

    @Test
    void theServiceAnswersEveryOperationFromOneRefresh(@TempDir Path dir) throws IOException {
        Path root = workspace(dir);
        assertThat(run(root, request("query", "how is the order total computed")))
                .contains("OrderService.total()  src/main/java/com/acme/shop/OrderService.java:");
        assertThat(run(root, request("path", "OrderService", "BaseService"))).startsWith("Shortest path, 1 hop:");
        assertThat(run(root, request("neighbors", "PriceCalculator"))).startsWith("Neighbors of PriceCalculator:");
        assertThat(run(root, request("callers", "BaseService.log")))
                .startsWith("Callers of BaseService.log(): 1 in main sources");
        GraphService.Request stored = new GraphService.Request(
                "neighbors", List.of("PriceCalculator"), "bfs", 3, 2000, List.of(), false, 8, null, false, true);
        assertThat(run(root, stored)).startsWith("Neighbors of PriceCalculator:");
        assertThatThrownBy(() -> run(root, request("stats"))).hasMessageContaining("unknown graph operation 'stats'");
    }

    private static String run(Path root, GraphService.Request r) throws IOException {
        return GraphService.run(service(root), r, GraphBuild.Progress.NONE).text();
    }

    private static GraphService.Workspace service(Path root) throws IOException {
        return new GraphService.Workspace(
                root,
                root.resolve("target/graph"),
                indexes(root, Set.of()),
                Modules.NONE,
                NO_NODE,
                Map.of(),
                List.of(),
                List.of(),
                null);
    }

    private static GraphService.Request request(String op, String... args) {
        return new GraphService.Request(op, List.of(args), "bfs", 3, 2000, List.of(), false, 8, null, false, false);
    }
}
