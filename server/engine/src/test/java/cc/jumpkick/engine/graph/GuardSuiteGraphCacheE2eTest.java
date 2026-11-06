// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.graph;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.GraphCacheKeys;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.engine.plugin.WarmWorkers;
import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.runtime.LockPlans;
import cc.jumpkick.runtime.base.GuardSuiteLibrary;
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
 * End-to-end: the guard suite's compile stamps its classes with its action key, as compile-java
 * does, so the code graph reads the suite's compiled facts from the action cache once it has
 * indexed them, instead of indexing them on every refresh.
 */
@Tag("integration")
class GuardSuiteGraphCacheE2eTest {

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
    void a_second_refresh_reads_the_guard_suites_facts_from_the_cache(@TempDir Path tmp) throws Exception {
        Path ws = workspace(tmp);
        JkBuild root = JkBuildParser.parse(ws.resolve("jk.toml"));
        BuildPlan lock =
                LockPlans.lockBuildPlan(ws, root, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).as("workspace lock").isTrue();
        WorkspaceResult built = WorkspaceExecute.buildWorkspace(
                new WorkspaceRequest(ws, cache, null, 0, null, false, false, 0, null, false, false),
                WorkspaceBuildListener.NOOP);
        assertThat(built.errors()).isEmpty();
        assertThat(built.success()).isTrue();

        Path guardClasses = BuildLayout.guardClassesDir(ws.resolve("app"));
        assertThat(guardClasses.resolve("demo/CalcRules.class")).isRegularFile();
        String compileKey = FreshnessStamp.stampedKey(guardClasses, BuildStamps.JAVA)
                .orElseThrow(() -> new AssertionError("no compile stamp in " + guardClasses));
        String key = GraphCacheKeys.classes("app/", "guard", List.of(compileKey));

        GraphAck first = GraphRunner.run(request(ws), p -> {});
        assertThat(first.error()).isNull();
        assertThat(first.text()).contains("CalcRules.doubles()");
        assertThat(guardRow(ws))
                .as("the first refresh stored the suite's facts under its compile key")
                .startsWith("\tapp/\tguard\t" + key + "\t");

        GraphAck second = GraphRunner.run(request(ws), p -> {});
        assertThat(second.error()).isNull();
        assertThat(second.text()).contains("CalcRules.doubles()");
        assertThat(guardRow(ws))
                .as("still a cache hit after the second refresh")
                .startsWith("\tapp/\tguard\t" + key + "\t");
    }

    /** The guard set's row as the engine hands it to the worker; a cache hit names no index file. */
    private String guardRow(Path ws) {
        return GraphRunner.classIndexes(ws, cache, false).rows().stream()
                .filter(row -> row.contains("\tguard\t"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no guard row"));
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

    /** {@code app} with a guard suite calling its main code, and the guard library as a member. */
    private static Path workspace(Path tmp) throws IOException {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "t"
                name    = "ws"
                version = "1.0.0"
                java    = 25

                [workspace]
                modules = ["guards", "app"]
                """);
        Files.createDirectories(ws.resolve("guards/src/main/java/rules"));
        Files.writeString(ws.resolve("guards/jk.toml"), """
                group   = "cc.jumpkick"
                name    = "%s"
                version = "1.0.0"
                java    = 25
                javadoc = false
                """.formatted(GuardSuiteLibrary.ARTIFACT));
        Files.writeString(ws.resolve("guards/src/main/java/rules/Rule.java"), "package rules; public class Rule {}\n");
        Files.createDirectories(ws.resolve("app/src/main/java/demo"));
        Files.createDirectories(ws.resolve("app/src/guard/java/demo"));
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
        Files.writeString(ws.resolve("app/src/guard/java/demo/CalcRules.java"), """
                package demo;

                class CalcRules {
                    int doubles() {
                        return new Calc().doubled(2);
                    }
                }
                """);
        return ws;
    }
}
