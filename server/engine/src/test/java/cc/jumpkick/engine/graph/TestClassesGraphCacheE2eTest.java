// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.graph;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.GraphCacheKeys;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.engine.plugin.WarmWorkers;
import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.runtime.LockPlans;
import cc.jumpkick.runtime.PlannerTest;
import cc.jumpkick.runtime.workspace.WorkspaceExecute;
import cc.jumpkick.task.FreshnessStamp;
import cc.jumpkick.wire.protocol.GraphAck;
import cc.jumpkick.wire.protocol.GraphRequest;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end: compile-test stamps the test classes with its action key, so the code graph reads
 * the test set's compiled facts from the action cache once it has indexed them.
 */
@Tag("integration")
class TestClassesGraphCacheE2eTest {

    @AfterAll
    static void stopWorkers() {
        WarmWorkers.shutdown();
    }

    /** The action cache the build and the graph share: this test's own. */
    @TempDir
    Path cache;

    @BeforeEach
    void scratchCache() {
        System.setProperty("jk.env.JK_CACHE_DIR", cache.toString());
    }

    @AfterEach
    void clearCache() {
        System.clearProperty("jk.env.JK_CACHE_DIR");
    }

    @Test
    void a_second_refresh_reads_the_test_sets_facts_from_the_cache(@TempDir Path tmp) throws Exception {
        Path ws = workspace(tmp);
        build(ws);

        Path testClasses = BuildLayout.of(ws.resolve("app"), JkBuildParser.parse(ws.resolve("app/jk.toml")))
                .testClassesDir();
        assertThat(testClasses.resolve("demo/CalcTest.class")).isRegularFile();
        String compileKey = FreshnessStamp.stampedKey(testClasses, BuildStamps.JAVA)
                .orElseThrow(() -> new AssertionError("no compile stamp in " + testClasses));
        String key = GraphCacheKeys.classes("app/", "test", List.of(compileKey));

        GraphAck first = GraphRunner.run(request(ws), p -> {});
        assertThat(first.error()).isNull();
        assertThat(first.text()).contains("CalcTest.doubles()");
        assertThat(testRow(ws))
                .as("the first refresh stored the test set's facts under its compile key")
                .startsWith("\tapp/\ttest\t" + key + "\t");

        GraphAck second = GraphRunner.run(request(ws), p -> {});
        assertThat(second.error()).isNull();
        assertThat(second.text()).contains("CalcTest.doubles()");
        assertThat(testRow(ws))
                .as("still a cache hit after the second refresh")
                .startsWith("\tapp/\ttest\t" + key + "\t");
    }

    @Test
    void the_stamp_names_the_compile_of_every_suite_the_test_classes_hold(@TempDir Path tmp) throws Exception {
        Path ws = workspace(tmp);
        Files.createDirectories(ws.resolve("app/src/integration/java/demo"));
        Files.writeString(ws.resolve("app/src/integration/java/demo/CalcIT.java"), """
                package demo;

                import org.junit.jupiter.api.Test;

                class CalcIT {
                    @Test
                    void triples() {
                        if (new Calc().doubled(3) != 6) throw new AssertionError();
                    }
                }
                """);
        build(ws);
        Path testClasses = BuildLayout.of(ws.resolve("app"), JkBuildParser.parse(ws.resolve("app/jk.toml")))
                .testClassesDir();
        String defaultOnly = FreshnessStamp.stampedKey(testClasses, BuildStamps.JAVA)
                .orElseThrow(() -> new AssertionError("no compile stamp in " + testClasses));

        // Both suites compile into the one tree: its stamp is that compile's, not the default suite's.
        Session both = Session.defaults()
                .withTestSelection(TestSelection.of(List.of("test", "integration"), false, List.of(), List.of()));
        SessionContext.where(both, () -> {
            build(ws);
            return true;
        });
        assertThat(PlannerTest.compiledSuites(testClasses)).containsExactly("integration", "test");
        assertThat(testClasses.resolve("demo/CalcIT.class")).isRegularFile();
        String bothSuites = FreshnessStamp.stampedKey(testClasses, BuildStamps.JAVA)
                .orElseThrow(() -> new AssertionError("no compile stamp in " + testClasses));
        assertThat(bothSuites).isNotEqualTo(defaultOnly);

        String key = GraphCacheKeys.classes("app/", "test", List.of(bothSuites));
        GraphRunner.run(request(ws), p -> {});
        GraphAck second = GraphRunner.run(request(ws), p -> {});
        assertThat(second.error()).isNull();
        assertThat(second.text()).contains("CalcTest.doubles()").contains("CalcIT.triples()");
        assertThat(testRow(ws)).startsWith("\tapp/\ttest\t" + key + "\t");
    }

    private void build(Path ws) throws Exception {
        JkBuild root = JkBuildParser.parse(ws.resolve("jk.toml"));
        BuildPlan lock =
                LockPlans.lockBuildPlan(ws, root, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).as("workspace lock").isTrue();
        WorkspaceResult built = WorkspaceExecute.buildWorkspace(
                new WorkspaceRequest(ws, cache, null, 0, null, false, false, 0, null, false, false),
                WorkspaceBuildListener.NOOP);
        assertThat(built.errors()).isEmpty();
        assertThat(built.success()).isTrue();
    }

    /** The test set's row as the engine hands it to the worker; a cache hit names no index file. */
    private String testRow(Path ws) {
        return GraphRunner.classIndexes(ws, cache, false).rows().stream()
                .filter(row -> row.contains("\ttest\t"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no test row"));
    }

    private static GraphRequest request(Path dir) {
        return new GraphRequest(
                dir.toString(),
                "callers",
                List.of("Calc.doubled"),
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

    /** {@code app} with a test calling its main code. */
    private static Path workspace(Path tmp) throws IOException {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "t"
                name    = "ws"
                version = "1.0.0"
                java    = 25

                [workspace]
                modules = ["app"]
                """);
        Files.createDirectories(ws.resolve("app/src/main/java/demo"));
        Files.createDirectories(ws.resolve("app/src/test/java/demo"));
        Files.writeString(ws.resolve("app/jk.toml"), """
                group   = "t"
                name    = "app"
                version = "1.0.0"
                java    = 25
                javadoc = false
                """);
        Files.writeString(ws.resolve("app/src/main/java/demo/Calc.java"), """
                package demo;

                public class Calc {
                    public int doubled(int n) {
                        return n * 2;
                    }
                }
                """);
        Files.writeString(ws.resolve("app/src/test/java/demo/CalcTest.java"), """
                package demo;

                import org.junit.jupiter.api.Test;

                class CalcTest {
                    @Test
                    void doubles() {
                        if (new Calc().doubled(2) != 4) throw new AssertionError();
                    }
                }
                """);
        return ws;
    }
}
