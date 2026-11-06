// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.graph;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.engine.plugin.WarmWorkers;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.GraphLayout;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.ModuleInputProvenance;
import cc.jumpkick.wire.protocol.GraphAck;
import cc.jumpkick.wire.protocol.GraphProgress;
import cc.jumpkick.wire.protocol.GraphRequest;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import javax.tools.ToolProvider;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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

    @AfterEach
    void noCompiler() {
        GraphRunner.compiler(null);
    }

    /**
     * What a compile job leaves: every main source compiled into target/classes and the record of
     * which sources those classes are of. Counts its runs; a source that does not compile is refused
     * the way the engine's job reports it.
     */
    private static final class Javac implements GraphRunner.Compiler {
        final List<List<String>> runs = new ArrayList<>();

        @Override
        public @Nullable String compile(Path root, List<String> modules) {
            runs.add(modules);
            try {
                List<String> args = new ArrayList<>(
                        List.of("-g", "-d", root.resolve("target/classes").toString()));
                PathUtil.forEachRegularFile(root.resolve("src/main/java"), (f, attrs) -> {
                    if (f.toString().endsWith(".java")) args.add(f.toString());
                });
                int exit = ToolProvider.getSystemJavaCompiler()
                        .run(
                                null,
                                OutputStream.nullOutputStream(),
                                OutputStream.nullOutputStream(),
                                args.toArray(String[]::new));
                if (exit != 0) return "the code does not compile";
                BuildGraph.Result graph =
                        BuildGraph.resolve(root, JkBuildParser.parse(Files.readString(root.resolve("jk.toml"))));
                ModuleInputProvenance.recordClasses(
                        root, graph, Set.of(root.toAbsolutePath().normalize()));
                return null;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /**
     * A question after an edit answers for the code as edited, without a build: the stale module is
     * compiled first. An edit that does not compile is answered as before, with the reason said, and
     * an unchanged tree compiles nothing.
     */
    @Test
    void aQuestionAfterAnEditCompilesTheStaleModuleFirst(@TempDir Path tmp) throws Exception {
        Path root = workspace(tmp);
        Javac javac = new Javac();
        GraphRunner.compiler(javac);
        GraphAck before = GraphRunner.run(request(root, "callers", List.of("PriceCalculator.sum")), p -> {});
        assertThat(before.error()).isNull();
        assertThat(javac.runs).as("an unchanged tree: nothing to compile").isEmpty();

        Path order = root.resolve("src/main/java/com/acme/shop/OrderService.java");
        Files.writeString(order, Files.readString(order).replace("""
                    public long total(long a, long b) {""", """
                    public long twice(long a) {
                        return calculator.sum(a, a);
                    }

                    public long total(long a, long b) {"""));
        GraphAck after = GraphRunner.run(request(root, "callers", List.of("PriceCalculator.sum")), p -> {});
        assertThat(javac.runs).hasSize(1);
        assertThat(after.text()).contains("OrderService.twice()");
        assertThat(String.valueOf(after.note())).doesNotContain("edited since the build", "compile skipped");

        Files.writeString(order, Files.readString(order).replace("return calculator.sum(a, a);", "return nope(;"));
        GraphAck broken = GraphRunner.run(request(root, "callers", List.of("PriceCalculator.sum")), p -> {});
        assertThat(broken.error()).as("mid-edit code never fails the question").isNull();
        assertThat(broken.note())
                .contains("compile skipped: the code does not compile; answering from the last build's classes")
                .contains("OrderService.java");
    }

    /**
     * A fresh checkout has no classes: a callers question compiles the module first and answers
     * complete from what that compile wrote. Before it, the answer would have been complete with
     * no compiled call behind it.
     */
    @Test
    void aQuestionOnACheckoutThatWasNeverCompiledCompilesItFirst(@TempDir Path tmp) throws Exception {
        Path root = sources(tmp);
        Javac javac = new Javac();
        GraphRunner.compiler(javac);
        GraphAck ack = GraphRunner.run(request(root, "callers", List.of("PriceCalculator.sum")), p -> {});
        assertThat(ack.error()).isNull();
        assertThat(javac.runs).as("the never-compiled module is compiled").hasSize(1);
        assertThat(ack.text()).contains("OrderService.total()").contains("Complete from the compiled classes");
        assertThat(String.valueOf(ack.note())).doesNotContain("no compiled classes", "compile skipped");

        GraphRunner.run(request(root, "callers", List.of("PriceCalculator.sum")), p -> {});
        assertThat(javac.runs)
                .as("compiled once: the next question finds classes")
                .hasSize(1);
    }

    /**
     * A never-compiled module whose code does not compile is answered from its sources, says why in
     * one line naming the module, and is never called complete.
     */
    @Test
    void aNeverCompiledModuleThatDoesNotCompileIsNamedAndTheAnswerIsNotComplete(@TempDir Path tmp) throws Exception {
        Path root = sources(tmp);
        Path order = root.resolve("src/main/java/com/acme/shop/OrderService.java");
        Files.writeString(order, Files.readString(order).replace("return calculator.sum(a, b);", "return nope(;"));
        Javac javac = new Javac();
        GraphRunner.compiler(javac);
        GraphAck ack = GraphRunner.run(request(root, "callers", List.of("PriceCalculator.sum")), p -> {});
        assertThat(ack.error())
                .as("code that does not compile never fails the question")
                .isNull();
        assertThat(javac.runs).hasSize(1);
        assertThat(ack.text()).doesNotContain("Complete").contains("Not complete");
        assertThat(ack.note())
                .isEqualTo("compile skipped: the code does not compile; " + root.getFileName()
                        + " has no compiled classes, so the calls made there are missing: search it for calls too");
    }

    private static Path workspace(Path dir) throws Exception {
        sources(dir);
        Path pkg = dir.resolve("src/main/java/com/acme/shop");
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

    /** A one-module project as a fresh checkout has it: sources, nothing compiled. */
    private static Path sources(Path dir) throws Exception {
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

    @Test
    void aStaleModuleIsCompiledByItsPathSoTwoOfOneNameAreNotAmbiguous(@TempDir Path tmp) {
        assertThat(GraphRunner.moduleArg(tmp, tmp.resolve("plugins/quarkus"), "quarkus"))
                .isEqualTo("plugins/quarkus");
        assertThat(GraphRunner.moduleArg(tmp, tmp.resolve("server/guard/packs/quarkus"), "quarkus"))
                .isEqualTo("server/guard/packs/quarkus");
        assertThat(GraphRunner.moduleArg(tmp, tmp, "root")).isEqualTo("root");
    }

    /**
     * A build keeps an existing graph current and makes none: the first graph question makes one,
     * and {@code jk clean} is how a checkout stops. Nothing of the graph's is written in the tree.
     */
    @Test
    void aBuildRefreshesOnlyAGraphTheCheckoutHas(@TempDir Path tmp) throws Exception {
        Path root = workspace(tmp);
        Files.createDirectories(root.resolve(".jk/graph/src"));
        Files.writeString(root.resolve(".jk/graph/graph.jsonl"), "{}\n");
        GraphRunner.refreshExisting(root);
        assertThat(GraphRunner.hasGraph(root)).isFalse();

        assertThat(GraphRunner.run(request(root, "build", List.of()), p -> {}).error())
                .isNull();
        assertThat(GraphRunner.hasGraph(root)).isTrue();
        assertThat(root.resolve(".jk")).as("a tree store found is removed").isEmptyDirectory();
        Files.writeString(
                root.resolve("src/main/java/com/acme/shop/PriceCalculator.java"),
                Files.readString(root.resolve("src/main/java/com/acme/shop/PriceCalculator.java")) + "// edited\n");
        byte[] before = snapshot(root);
        GraphRunner.refreshExisting(root);
        assertThat(snapshot(root)).isNotEqualTo(before);

        // After a build the refresh runs behind the verdict, and still lands.
        Files.writeString(
                root.resolve("src/main/java/com/acme/shop/PriceCalculator.java"),
                Files.readString(root.resolve("src/main/java/com/acme/shop/PriceCalculator.java")) + "// again\n");
        byte[] refreshed = snapshot(root);
        GraphRunner.refreshAfterBuild(root);
        long deadline = System.currentTimeMillis() + 60_000;
        while (Arrays.equals(snapshot(root), refreshed) && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(snapshot(root)).isNotEqualTo(refreshed);
        assertThat(root.resolve(".jk")).isEmptyDirectory();

        PathUtil.deleteRecursively(GraphRunner.outputDir(root));
        GraphRunner.refreshExisting(root);
        assertThat(GraphRunner.hasGraph(root)).isFalse();
    }

    private static byte[] snapshot(Path root) throws IOException {
        return Files.readAllBytes(GraphRunner.outputDir(root).resolve(GraphLayout.SNAPSHOT));
    }

    /**
     * The refresh that follows a build indexes the classes that build left and compiles nothing,
     * even where sources moved since them. A compile it submitted would be a job of the engine's
     * own settings, not the build's: the default cache rather than the build's {@code --cache-dir}
     * (and none of its variant, offline or JVM settings), writing entries into a cache the build
     * never named, for modules the build chose not to compile. A graph question compiles them
     * when one is asked.
     */
    @Test
    void the_refresh_after_a_build_compiles_nothing_even_over_moved_sources(@TempDir Path tmp) throws Exception {
        Path root = workspace(tmp);
        assertThat(GraphRunner.run(request(root, "build", List.of()), p -> {}).error())
                .isNull();
        Javac javac = new Javac();
        GraphRunner.compiler(javac);
        Path calculator = root.resolve("src/main/java/com/acme/shop/PriceCalculator.java");
        Files.writeString(calculator, Files.readString(calculator) + "// edited after the build\n");
        byte[] before = snapshot(root);

        GraphRunner.refreshExisting(root);

        assertThat(javac.runs).as("the post-build refresh submits no compile").isEmpty();
        assertThat(snapshot(root)).as("and still refreshes the graph").isNotEqualTo(before);
        GraphAck asked = GraphRunner.run(request(root, "callers", List.of("PriceCalculator.sum")), p -> {});
        assertThat(asked.error()).isNull();
        assertThat(javac.runs).as("a question compiles the moved module").hasSize(1);
    }

    @Test
    void buildsInTheWorkerAndAnswersFromTheStoredGraphWhenNothingChanged(@TempDir Path tmp) throws Exception {
        Path root = workspace(tmp);
        List<GraphProgress> progress = new ArrayList<>();
        GraphAck built = GraphRunner.run(request(root, "build", List.of()), progress::add);
        assertThat(built.error()).isNull();
        assertThat(built.files()).isEqualTo(4);
        assertThat(built.classes()).isEqualTo(2);
        // Declarations another run already cached on this machine are read, not extracted.
        assertThat(built.extracted()).isBetween(0, 4);
        assertThat(progress).isNotEmpty();
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
