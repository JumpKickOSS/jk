// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.graph;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.plugin.WarmWorkers;
import cc.jumpkick.engine.runtime.NewProjectOps;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.runtime.LockPlans;
import cc.jumpkick.runtime.workspace.WorkspaceExecute;
import cc.jumpkick.testing.RepoRoot;
import cc.jumpkick.wire.protocol.GraphAck;
import cc.jumpkick.wire.protocol.GraphRequest;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import cc.jumpkick.wire.runtime.WorkspaceSpec;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End to end: a graph question on a project {@code jk new}'s library template wrote, which declares
 * {@code [build-info]}, compiles the project's tests and answers with their calls. The test-compile
 * goal the question runs once failed to plan on any such project ("step 'compile-test' requires
 * unknown 'build-info'"), so every answer said "compile skipped" and sent the agent to search
 * src/test. The compiler here runs the goals the engine's {@link GraphCompiles} submits
 * ({@code compile}, then {@code test-compile}) as the workspace build, in process.
 */
@Tag("integration")
class BuildInfoGraphCompileE2eTest {

    @AfterAll
    static void stopWorkers() {
        WarmWorkers.shutdown();
    }

    @TempDir
    Path cache;

    @BeforeEach
    void scratchCache() {
        System.setProperty("jk.env.JK_CACHE_DIR", cache.toString());
    }

    @AfterEach
    void reset() {
        GraphRunner.compiler(null);
        System.clearProperty("jk.env.JK_CACHE_DIR");
    }

    @Test
    void callers_on_a_new_library_lists_its_tests_use_and_skips_no_compile(@TempDir Path tmp) throws Exception {
        // As jk new -t <checkout>/templates/java/library.g8 writes it, with the template's defaults.
        Path dir = NewProjectOps.create(new NewProjectOps.Request(
                        "demo-lib",
                        tmp.toString(),
                        "com.example",
                        null,
                        null,
                        RepoRoot.find(BuildInfoGraphCompileE2eTest.class)
                                .resolve("templates/java/library.g8")
                                .toString(),
                        true,
                        Map.of(),
                        false,
                        null,
                        tmp.toString()))
                .path();
        assertThat(Files.readString(dir.resolve("jk.toml"))).contains("[build-info]");
        BuildPlan lock = LockPlans.lockBuildPlan(dir, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).as("lock").isTrue();

        List<WorkspaceTarget> ran = new ArrayList<>();
        GraphRunner.compiler((root, modules, tests) -> {
            WorkspaceTarget target = tests ? WorkspaceTarget.TEST_COMPILE : WorkspaceTarget.COMPILE;
            ran.add(target);
            WorkspaceResult result = WorkspaceExecute.buildWorkspace(
                    new WorkspaceRequest(root, cache, null, 0, null, false, false, 0, null, false, false)
                            .withModules(modules)
                            .withSpec(WorkspaceSpec.of(target)),
                    WorkspaceBuildListener.NOOP);
            return result.success() ? null : "the code does not compile (" + result.errors() + ")";
        });

        GraphAck ack = GraphRunner.run(callers(dir, "Greeter.greet"), p -> {});

        assertThat(ack.error()).isNull();
        assertThat(ran).containsExactly(WorkspaceTarget.COMPILE, WorkspaceTarget.TEST_COMPILE);
        assertThat(ack.text())
                .contains("GreeterTest.greets_by_name()")
                .doesNotContain("compile skipped")
                .doesNotContain("no compiled classes");
    }

    private static GraphRequest callers(Path dir, String method) {
        return new GraphRequest(
                dir.toString(),
                "usages",
                List.of(method),
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
}
