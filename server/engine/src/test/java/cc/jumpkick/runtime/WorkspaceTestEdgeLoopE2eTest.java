// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.runtime.workspace.WorkspaceExecute;
import cc.jumpkick.testing.TestCaches;
import cc.jumpkick.wire.runtime.ModulePlan;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import cc.jumpkick.wire.runtime.WorkspaceSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end: two members whose tests read each other build and test green, the shape Gradle's
 * {@code java-test-fixtures} gives a fixtures module.
 *
 * <p>infra's tests read arch's fixtures; arch's fixtures and tests read infra's main; svc's main
 * reads infra. Per source set nothing loops, so the build schedules infra and arch together, arch's
 * fixtures compile once infra's classes are whole, and infra's tests compile once arch's fixtures
 * are. Under {@code -j1} infra holds the only slot while its test compile waits on arch, which the
 * schedule must still start. Through the real preflight, planner and workspace scheduler.
 *
 * <p>Network: the junit pins come from Maven Central into the cache under {@code build/}, which
 * persists across runs so repeats are warm.
 */
@Tag("integration")
class WorkspaceTestEdgeLoopE2eTest {

    @Test
    void members_whose_tests_read_each_other_build_and_test_green(@TempDir Path tmp) throws Exception {
        Path cache = TestCaches.dir("test-edge-loop-cache");
        Path ws = loopWorkspace(tmp);
        lock(ws, cache);
        BuildLayout infra = layout(ws, "infra");
        BuildLayout arch = layout(ws, "arch");
        BuildLayout svc = layout(ws, "svc");

        build(ws, cache, 1, "the -j1 build");
        assertThat(arch.testFixturesClassesDir().resolve("com/example/ArchFixture.class"))
                .isRegularFile();
        for (BuildLayout module : List.of(infra, arch, svc)) {
            assertThat(module.testResultsDir())
                    .as("%s ran its tests", module.buildDir())
                    .isDirectoryContaining("glob:**.xml");
        }

        wipeTargets(ws);
        build(ws, cache, 4, "the parallel build");
        assertThat(infra.testClassesDir().resolve("com/example/InfraTest.class"))
                .isRegularFile();
    }

    private static BuildLayout layout(Path ws, String module) throws IOException {
        return BuildLayout.of(ws, ws.resolve(module), JkBuildParser.parse(ws.resolve(module + "/jk.toml")));
    }

    private static void build(Path ws, Path cache, int modules, String label) {
        List<String> errors = new CopyOnWriteArrayList<>();
        WorkspaceResult result = WorkspaceExecute.buildWorkspace(
                new WorkspaceRequest(
                        ws,
                        cache,
                        null,
                        0,
                        null,
                        false,
                        false,
                        modules,
                        null,
                        false,
                        false,
                        false,
                        "",
                        Map.of(),
                        false,
                        WorkspaceSpec.DEFAULT,
                        List.of(),
                        false),
                new WorkspaceBuildListener() {
                    @Override
                    public BuildPlanListener onModuleStart(ModulePlan module) {
                        return new BuildPlanListener() {
                            @Override
                            public void error(String step, String code, String message) {
                                errors.add(module.coord() + " " + step + ": " + message);
                            }
                        };
                    }
                });
        assertThat(result.errors()).as(label).isEmpty();
        assertThat(errors).as(label).isEmpty();
        assertThat(result.success()).as(label).isTrue();
    }

    private static void lock(Path ws, Path cache) throws IOException {
        JkBuild root = JkBuildParser.parse(ws.resolve("jk.toml"));
        BuildPlan lock =
                LockPlans.lockBuildPlan(ws, root, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).as("workspace lock").isTrue();
    }

    private static final String JUNIT = """
            junit-jupiter           = { group = "org.junit.jupiter", name = "junit-jupiter", version = "6.1.3" }
            junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "6.1.3" }
            """;

    private static final String CENTRAL = """

            [repositories]
            central = "https://repo.maven.apache.org/maven2/"
            """;

    private static Path loopWorkspace(Path tmp) throws IOException {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "com.example"
                name    = "ws"
                version = "1.0.0"
                java    = 25

                [workspace]
                modules = ["infra", "arch", "svc"]
                """);
        module(ws, "infra", """
                [test-dependencies]
                arch = { workspace = true, fixtures = true }
                """ + JUNIT);
        write(ws, "infra/src/main/java/com/example/Infra.java", """
                package com.example;
                public final class Infra {
                    public static String layer() { return "infra"; }
                }
                """);
        write(ws, "infra/src/test/java/com/example/InfraTest.java", """
                package com.example;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class InfraTest {
                    @Test void obeys_the_architecture() { assertEquals("infra", ArchFixture.layerOf()); }
                }
                """);

        module(ws, "arch", """
                [test]
                fixtures = true

                [test-dependencies]
                infra = { workspace = true }
                """ + JUNIT);
        write(ws, "arch/src/main/java/com/example/Arch.java", """
                package com.example;
                public final class Arch {}
                """);
        write(ws, "arch/src/fixtures/java/com/example/ArchFixture.java", """
                package com.example;
                /** Reads infra's main: only arch's fixtures tree carries it. */
                public final class ArchFixture {
                    public static String layerOf() { return Infra.layer(); }
                }
                """);
        write(ws, "arch/src/test/java/com/example/ArchTest.java", """
                package com.example;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class ArchTest {
                    @Test void sees_infra() { assertEquals(Infra.layer(), ArchFixture.layerOf()); }
                }
                """);

        module(ws, "svc", """
                [dependencies]
                infra = { workspace = true }

                [test-dependencies]
                """ + JUNIT);
        write(ws, "svc/src/main/java/com/example/Svc.java", """
                package com.example;
                public final class Svc {
                    public static String over() { return "svc>" + Infra.layer(); }
                }
                """);
        write(ws, "svc/src/test/java/com/example/SvcTest.java", """
                package com.example;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class SvcTest {
                    @Test void over_infra() { assertEquals("svc>infra", Svc.over()); }
                }
                """);
        return ws;
    }

    private static void module(Path ws, String name, String tables) throws IOException {
        write(ws, name + "/jk.toml", """
                group   = "com.example"
                name    = "%s"
                version = "1.0.0"
                java    = 25

                """.formatted(name) + tables + CENTRAL);
    }

    private static void write(Path ws, String rel, String body) throws IOException {
        Path file = ws.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
    }

    private static void wipeTargets(Path ws) {
        PathUtil.deleteRecursively(ws.resolve("target"));
        for (String module : List.of("infra", "arch", "svc"))
            PathUtil.deleteRecursively(ws.resolve(module).resolve("target"));
    }
}
