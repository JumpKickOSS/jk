// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.graph;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.ActionRecordText;
import cc.jumpkick.cache.GraphCacheKeys;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.engine.plugin.WarmWorkers;
import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.GraphLayout;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.ModuleInputProvenance;
import cc.jumpkick.runtime.ShadowManifests;
import cc.jumpkick.task.FreshnessStamp;
import cc.jumpkick.wire.protocol.GraphAck;
import cc.jumpkick.wire.protocol.GraphProgress;
import cc.jumpkick.wire.protocol.GraphRequest;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.tools.ToolProvider;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

    /** The action cache the graph's fragments go to: this test's own, never the machine's. */
    @TempDir
    Path cache;

    @BeforeEach
    void scratchCache() {
        System.setProperty("jk.env.JK_CACHE_DIR", cache.toString());
    }

    @AfterEach
    void noCompiler() {
        GraphRunner.compiler(null);
    }

    /**
     * What a compile job leaves: every main source compiled into target/classes, or for a test
     * compile every test source into target/test-classes, and the record of which sources those
     * classes are of. Counts its runs; a source that does not compile is refused the way the
     * engine's job reports it.
     */
    private static final class Javac implements GraphRunner.Compiler {
        final List<List<String>> runs = new ArrayList<>();
        final List<Boolean> tests = new ArrayList<>();

        @Override
        public @Nullable String compile(Path root, List<String> modules, boolean tests) {
            runs.add(modules);
            this.tests.add(tests);
            try {
                Path classes = root.resolve("target/classes");
                List<String> args = new ArrayList<>(List.of(
                        "-g",
                        "-d",
                        root.resolve(tests ? "target/test-classes" : "target/classes")
                                .toString(),
                        "-cp",
                        classes.toString()));
                PathUtil.forEachRegularFile(root.resolve(tests ? "src/test/java" : "src/main/java"), (f, attrs) -> {
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

    /**
     * A fresh checkout's tests never compiled either: the question compiles the main sources, then
     * the tests in a job of their own, and the test that calls the method is among its callers.
     * Before it, the answer said "0 in tests" under "Complete" with that caller missing.
     */
    @Test
    void aFreshCheckoutsTestsAreCompiledFirstSoATestCallerIsListed(@TempDir Path tmp) throws Exception {
        Path root = calc(tmp);
        Javac javac = new Javac();
        GraphRunner.compiler(javac);
        GraphAck ack = GraphRunner.run(request(root, "callers", List.of("Calc.doubled")), p -> {});
        assertThat(ack.error()).isNull();
        assertThat(javac.tests).as("the main sources, then the tests").containsExactly(false, true);
        assertThat(ack.text())
                .contains("CalcTest.doubledReturnsTwiceTheValue()")
                .contains("Complete from the compiled classes");
        assertThat(String.valueOf(ack.note())).doesNotContain("no compiled classes", "compile skipped");

        GraphRunner.run(request(root, "callers", List.of("Calc.doubled")), p -> {});
        assertThat(javac.runs).as("compiled once").hasSize(2);

        Path test = root.resolve("src/test/java/demo/CalcTest.java");
        Files.writeString(test, Files.readString(test) + "// edited\n");
        GraphAck edited = GraphRunner.run(request(root, "callers", List.of("Calc.doubled")), p -> {});
        assertThat(javac.runs).as("an edited test is named, not compiled").hasSize(2);
        assertThat(edited.note()).contains("1 source edited since the build (src/test/java/demo/CalcTest.java)");
    }

    /**
     * Building the graph asks no question: the first graph {@code jk new} writes, or {@code jk graph
     * build}, compiles nothing and leaves no classes, and the first question after it compiles first.
     */
    @Test
    void buildingTheGraphCompilesNothingAndTheFirstQuestionCompilesFirst(@TempDir Path tmp) throws Exception {
        Path root = calc(tmp);
        Javac javac = new Javac();
        GraphRunner.compiler(javac);
        GraphAck built = GraphRunner.run(request(root, "build", List.of()), p -> {});
        assertThat(built.error()).isNull();
        assertThat(javac.runs).as("no compile job").isEmpty();
        assertThat(root.resolve("target/classes")).doesNotExist();
        assertThat(root.resolve("target/test-classes")).doesNotExist();
        assertThat(GraphRunner.hasGraph(root)).isTrue();

        GraphAck asked = GraphRunner.run(request(root, "callers", List.of("Calc.doubled")), p -> {});
        assertThat(javac.tests).containsExactly(false, true);
        assertThat(asked.text()).contains("CalcTest.doubledReturnsTwiceTheValue()");
        assertThat(GraphRunner.readsCompiledFacts("rules")).isFalse();
        assertThat(GraphRunner.readsCompiledFacts("rename")).isTrue();
    }

    /**
     * Tests that never compiled and do not compile now are named in one line with why, and the
     * answer is not complete: their calls are missing.
     */
    @Test
    void testsThatDoNotCompileAreNamedAndTheAnswerIsNotComplete(@TempDir Path tmp) throws Exception {
        Path root = calc(tmp);
        Path test = root.resolve("src/test/java/demo/CalcTest.java");
        Files.writeString(test, Files.readString(test).replace("return", "return nope(;"));
        Javac javac = new Javac();
        GraphRunner.compiler(javac);
        GraphAck ack = GraphRunner.run(request(root, "callers", List.of("Calc.doubled")), p -> {});
        assertThat(ack.error()).isNull();
        assertThat(javac.tests).containsExactly(false, true);
        assertThat(ack.text()).doesNotContain("Complete").contains("Not complete");
        assertThat(ack.note())
                .isEqualTo("compile skipped: the code does not compile; src/test has no compiled classes, so the calls"
                        + " made there are missing: search it for calls too");
    }

    /**
     * A rename on a fresh checkout compiles the main sources and the tests first, so the test's call
     * is renamed with the rest and the answer is complete.
     */
    @Test
    void aRenameOnAFreshCheckoutCompilesFirstAndRenamesTheTestsCallToo(@TempDir Path tmp) throws Exception {
        Path root = calc(tmp);
        Javac javac = new Javac();
        GraphRunner.compiler(javac);
        GraphAck ack = GraphRunner.run(request(root, "rename", List.of("Calc.doubled", "twice")), p -> {});
        assertThat(ack.error()).isNull();
        assertThat(javac.tests).containsExactly(false, true);
        assertThat(ack.text())
                .startsWith("Renamed Calc.doubled to twice: 1 declaration, 1 call, in 2 files.")
                .contains("no search needed")
                .contains("the task is done");
        assertThat(ack.uncompiled()).isEmpty();
        assertThat(root.resolve("src/main/java/demo/Calc.java")).content().contains("public int twice(int n)");
        assertThat(root.resolve("src/test/java/demo/CalcTest.java")).content().contains("new Calc().twice(2)");
    }

    /**
     * A rename while a test set does not compile renames what the compiled classes record, names
     * the set, and never says the task is done: calls made there may be missing.
     */
    @Test
    void aRenameWhileTestsDoNotCompileNamesThemAndIsNotDone(@TempDir Path tmp) throws Exception {
        Path root = calc(tmp);
        Path main = root.resolve("src/main/java/demo");
        Files.writeString(main.resolve("Use.java"), """
                package demo;

                class Use {
                    int four() {
                        return new Calc().doubled(2);
                    }
                }
                """);
        Path test = root.resolve("src/test/java/demo/CalcTest.java");
        Files.writeString(test, "package demo;\n\nclass CalcTest {\n    boolean broken() { return nope(; }\n}\n");
        Javac javac = new Javac();
        GraphRunner.compiler(javac);
        GraphAck ack = GraphRunner.run(request(root, "rename", List.of("Calc.doubled", "twice")), p -> {});
        assertThat(ack.error()).isNull();
        assertThat(ack.text())
                .startsWith("Renamed Calc.doubled to twice: 1 declaration, 1 call, in 2 files.")
                .contains("Not complete: the note below names the modules with no compiled classes.")
                .doesNotContain("no search needed", "the task is done");
        assertThat(ack.uncompiled()).containsExactly("src/test");
        assertThat(ack.note())
                .isEqualTo("compile skipped: the code does not compile; src/test has no compiled classes, so the calls"
                        + " made there are missing: search it for calls too");
        assertThat(main.resolve("Use.java")).content().contains("new Calc().twice(2)");
    }

    /**
     * A question compiles the default test suite only, so the integration suite of a fresh checkout
     * stays uncompiled: callers names its directory and is not complete, and a rename does not
     * claim done: it quotes the suite's uses of the name instead. Once a compile that selected the
     * suite leaves its classes, its call is listed and renamed, and both are complete.
     */
    @Test
    void anIntegrationSuiteWithNoClassesIsNamedAndOnceCompiledItsCallsCount(@TempDir Path tmp) throws Exception {
        Path root = calcIt(Files.createDirectories(tmp.resolve("fresh")));
        Javac javac = new Javac();
        GraphRunner.compiler(javac);
        GraphAck callers = GraphRunner.run(request(root, "callers", List.of("Calc.doubled")), p -> {});
        assertThat(callers.error()).isNull();
        assertThat(javac.tests)
                .as("the main sources and the default suite: the integration suite is not compiled")
                .containsExactly(false, true);
        assertThat(callers.text())
                .contains("0 in main sources, 0 in tests")
                .contains("Not complete")
                .doesNotContain("Complete from the compiled classes");
        assertThat(callers.note())
                .isEqualTo("src/integration has no compiled classes, so the calls made there are missing: search it for"
                        + " calls too");

        GraphAck renamed = GraphRunner.run(request(root, "rename", List.of("Calc.doubled", "twice")), p -> {});
        assertThat(renamed.error()).isNull();
        assertThat(renamed.text())
                .contains("Not checked: these modules may call it but have no compiled classes.")
                .contains("src/integration/java/demo/CalcIT.java", "return new Calc().doubled(2) == 4;")
                .doesNotContain("no search needed", "the task is done");
        assertThat(renamed.uncompiled()).containsExactly("src/integration");

        root = calcIt(Files.createDirectories(tmp.resolve("compiled")));
        assertThat(GraphRunner.compileStale(root).skipped())
                .as("the main sources compile")
                .isNull();
        Path it = root.resolve("src/integration/java/demo");
        Path testClasses = Files.createDirectories(root.resolve("target/test-classes"));
        int exit = ToolProvider.getSystemJavaCompiler()
                .run(
                        null,
                        OutputStream.nullOutputStream(),
                        OutputStream.nullOutputStream(),
                        "-g",
                        "-d",
                        testClasses.toString(),
                        "-cp",
                        root.resolve("target/classes").toString(),
                        it.resolve("CalcIT.java").toString());
        assertThat(exit).isZero();
        Files.writeString(testClasses.resolve(".jk-suites"), "test,integration");
        BuildGraph.Result graph =
                BuildGraph.resolve(root, JkBuildParser.parse(Files.readString(root.resolve("jk.toml"))));
        ModuleInputProvenance.recordClasses(
                root, graph, Set.of(root.toAbsolutePath().normalize()));
        GraphAck compiled = GraphRunner.run(request(root, "callers", List.of("Calc.doubled")), p -> {});
        assertThat(compiled.text())
                .contains("CalcIT.doubledReturnsTwiceTheValue()")
                .contains("Complete from the compiled classes");
        GraphAck done = GraphRunner.run(request(root, "rename", List.of("Calc.doubled", "twice")), p -> {});
        assertThat(done.text()).contains("1 call").contains("the task is done");
        assertThat(it.resolve("CalcIT.java")).content().contains("new Calc().twice(2)");
    }

    /**
     * A question compiles neither the guard suite nor reads it until {@code jk guard} compiles it
     * into its own classes: before that callers names its directory and is not complete; after,
     * its call is listed, and both callers and a rename are complete.
     */
    @Test
    void aGuardSuitesCallIsListedOnceJkGuardCompiledIt(@TempDir Path tmp) throws Exception {
        Path root = calcGuard(Files.createDirectories(tmp.resolve("fresh")));
        GraphRunner.compiler(new Javac());
        GraphAck callers = GraphRunner.run(request(root, "callers", List.of("Calc.doubled")), p -> {});
        assertThat(callers.error()).isNull();
        assertThat(callers.text()).contains("0 in main sources, 0 in tests").contains("Not complete");
        assertThat(callers.note())
                .isEqualTo("src/guard has no compiled classes, so the calls made there are missing: search it for"
                        + " calls too");

        Path guard = root.resolve("src/guard/java/demo");
        Path guardClasses = Files.createDirectories(root.resolve("target/guard/classes"));
        int exit = ToolProvider.getSystemJavaCompiler()
                .run(
                        null,
                        OutputStream.nullOutputStream(),
                        OutputStream.nullOutputStream(),
                        "-g",
                        "-d",
                        guardClasses.toString(),
                        "-cp",
                        root.resolve("target/classes").toString(),
                        guard.resolve("CalcRules.java").toString());
        assertThat(exit).isZero();
        BuildGraph.Result graph =
                BuildGraph.resolve(root, JkBuildParser.parse(Files.readString(root.resolve("jk.toml"))));
        ModuleInputProvenance.recordClasses(
                root, graph, Set.of(root.toAbsolutePath().normalize()));
        GraphAck compiled = GraphRunner.run(request(root, "callers", List.of("Calc.doubled")), p -> {});
        assertThat(compiled.error()).isNull();
        assertThat(compiled.text())
                .contains("CalcRules.doubledReturnsTwiceTheValue()")
                .contains("Complete from the compiled classes");
        assertThat(compiled.note()).isNull();
        assertThat(compiled.uncompiled()).isEmpty();
        GraphAck done = GraphRunner.run(request(root, "rename", List.of("Calc.doubled", "twice")), p -> {});
        assertThat(done.text()).contains("1 call").contains("the task is done");
        assertThat(guard.resolve("CalcRules.java")).content().contains("new Calc().twice(2)");
    }

    /**
     * A module whose only way to see the method's module is a dependency that a Maven profile
     * active here declares can call it: while it has no compiled classes the rename is not complete
     * and names it, while an uncompiled module that sees neither is not named.
     */
    @Test
    void anUncompiledModuleThatSeesTheMethodOnlyThroughAProfileKeepsARenameNotComplete(@TempDir Path tmp)
            throws Exception {
        ShadowManifests.install(dir -> List.of());
        try {
            Path root = Files.createDirectories(tmp.resolve("reactor"));
            Files.writeString(root.resolve("pom.xml"), """
                    <project>
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>demo</groupId><artifactId>parent</artifactId><version>1.0</version>
                      <packaging>pom</packaging>
                      <properties><maven.compiler.release>25</maven.compiler.release></properties>
                      <modules><module>core</module><module>app</module><module>other</module></modules>
                    </project>
                    """);
            pomModule(root, "core", "", "Calc", """
                    public class Calc {
                        public int doubled(int n) {
                            return n * 2;
                        }
                    }
                    """);
            pomModule(root, "app", """
                      <profiles>
                        <profile>
                          <id>with-core</id>
                          <activation><activeByDefault>true</activeByDefault></activation>
                          <dependencies>
                            <dependency><groupId>demo</groupId><artifactId>core</artifactId><version>1.0</version></dependency>
                          </dependencies>
                        </profile>
                      </profiles>
                    """, "App", """
                    class App {
                        int six() {
                            return new Calc().doubled(3);
                        }
                    }
                    """);
            pomModule(root, "other", "", "Other", """
                    class Other {
                        int doubled() {
                            return 2;
                        }
                    }
                    """);
            Path core = root.resolve("core");
            Path classes = Files.createDirectories(core.resolve("target/classes"));
            int exit = ToolProvider.getSystemJavaCompiler()
                    .run(
                            null,
                            OutputStream.nullOutputStream(),
                            OutputStream.nullOutputStream(),
                            "-g",
                            "-d",
                            classes.toString(),
                            core.resolve("src/main/java/demo/Calc.java").toString());
            assertThat(exit).isZero();
            BuildGraph.Result graph = BuildGraph.resolve(root, JkBuildParser.parse(ManifestPaths.manifestIn(root)));
            ModuleInputProvenance.recordClasses(
                    root, graph, Set.of(core.toAbsolutePath().normalize()));

            GraphAck renamed = GraphRunner.run(request(root, "rename", List.of("Calc.doubled", "twice")), p -> {});
            assertThat(renamed.error()).isNull();
            assertThat(renamed.text())
                    .contains("Not checked: these modules may call it but have no compiled classes.")
                    .contains("app/src/main/java/demo/App.java", "return new Calc().doubled(3);")
                    .doesNotContain("other/src/main/java", "no search needed", "the task is done");
            assertThat(renamed.uncompiled()).containsExactly("app");
            assertThat(renamed.note())
                    .isEqualTo("app has no compiled classes, so the calls made there are missing: search it for calls"
                            + " too");
        } finally {
            ShadowManifests.install(dir -> List.of());
        }
    }

    private static void pomModule(Path root, String name, String extra, String type, String body) throws IOException {
        Path dir = Files.createDirectories(root.resolve(name).resolve("src/main/java/demo"));
        Files.writeString(dir.resolve(type + ".java"), "package demo;\n\n" + body);
        Files.writeString(root.resolve(name).resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>demo</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>%s</artifactId>
                %s</project>
                """.formatted(name, extra));
    }

    /**
     * Compiled facts the engine finds cached but the worker cannot read by the time it asks are
     * indexed and asked again: the answer still holds the calls.
     */
    @Test
    void cachedFactsGoneBeforeTheWorkerReadsThemAreIndexedAndAskedAgain(@TempDir Path tmp) throws Exception {
        Path root = workspace(tmp);
        Path classes = root.resolve("target/classes");
        FreshnessStamp.write(classes, BuildStamps.JAVA, "compile", "compile-key-1", List.of(), List.of(), 25, "");
        String key = Objects.requireNonNull(GraphCacheKeys.classes("", "main", List.of("compile-key-1")));
        byte[] unreadable = "[]".getBytes(StandardCharsets.UTF_8);
        String sha = Hashing.sha256Hex(unreadable);
        JkStores.cacheCas(cache).put(unreadable, sha);
        Path record = GraphCacheKeys.record(cache, key);
        Files.createDirectories(record.getParent());
        Files.writeString(
                record,
                ActionRecordText.render(
                        GraphCacheKeys.CLASSES,
                        key,
                        Map.of(GraphCacheKeys.CLASS_COUNT, "2"),
                        Map.of(GraphCacheKeys.OUTPUT, sha),
                        Set.of()));
        assertThat(GraphRunner.classIndexes(root, cache, false).rows())
                .as("the engine takes the set as cached")
                .allSatisfy(row -> assertThat(row).startsWith("\t"));

        GraphAck ack = GraphRunner.run(request(root, "callers", List.of("PriceCalculator.sum")), p -> {});
        assertThat(ack.error()).isNull();
        assertThat(ack.text()).contains("OrderService.total()").contains("Complete from the compiled classes");
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

    /** The fresh checkout of a project whose only caller of {@code Calc.doubled} is its test. */
    private static Path calc(Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "demo"
                name = "calc"
                version = "1.0.0"
                java = 25
                """);
        Path main = Files.createDirectories(dir.resolve("src/main/java/demo"));
        Files.writeString(main.resolve("Calc.java"), """
                package demo;

                public class Calc {
                    public int doubled(int n) {
                        return n * 2;
                    }
                }
                """);
        Path test = Files.createDirectories(dir.resolve("src/test/java/demo"));
        Files.writeString(test.resolve("CalcTest.java"), """
                package demo;

                class CalcTest {
                    boolean doubledReturnsTwiceTheValue() {
                        return new Calc().doubled(2) == 4;
                    }
                }
                """);
        return dir;
    }

    /** {@link #calc} with its only caller in the integration suite; the default suite's test calls nothing. */
    private static Path calcIt(Path dir) throws Exception {
        calc(dir);
        Path it = Files.createDirectories(dir.resolve("src/integration/java/demo"));
        Path test = dir.resolve("src/test/java/demo/CalcTest.java");
        Files.writeString(it.resolve("CalcIT.java"), Files.readString(test).replace("CalcTest", "CalcIT"));
        Files.writeString(test, "package demo;\n\nclass CalcTest {}\n");
        return dir;
    }

    /** {@link #calc} with its only caller in the guard suite; the default suite's test calls nothing. */
    private static Path calcGuard(Path dir) throws Exception {
        calc(dir);
        Path guard = Files.createDirectories(dir.resolve("src/guard/java/demo"));
        Path test = dir.resolve("src/test/java/demo/CalcTest.java");
        Files.writeString(
                guard.resolve("CalcRules.java"), Files.readString(test).replace("CalcTest", "CalcRules"));
        Files.writeString(test, "package demo;\n\nclass CalcTest {}\n");
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
        assertThat(built.extracted()).isEqualTo(4);
        assertThat(cache.resolve("actions/keys"))
                .as("the fragments are this test's cache entries")
                .isNotEmptyDirectory();
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
