// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Explicit {@code jdk = N} provisioning (rare in product examples — prefer {@code java = N}
 * so the host JDK 25 cross-compiles). These tests <em>must</em> pin {@code jdk = 17} /
 * {@code jdk = 21} to prove workers use that install as the forked test JVM.
 *
 * <p>Network tests (Maven Central; provisions the pin on first run); the CAS under build/
 * keeps repeats warm.
 */
class JdkFloorTest {

    @Test
    void jdk17_pinned_kotlin_project_builds_and_runs_tests(@TempDir Path tmp) throws Exception {
        runPinnedFloor(tmp, 17);
    }

    @Test
    void jdk21_pinned_kotlin_project_builds_and_runs_tests(@TempDir Path tmp) throws Exception {
        runPinnedFloor(tmp, 21);
    }

    private static void runPinnedFloor(Path tmp, int major) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("app" + major));
        Path cache = Path.of(System.getProperty("user.dir"), "build", "android-spike-cache");
        String majorStr = Integer.toString(major);

        Files.writeString(project.resolve("jk.toml"), """
                [project]
                name    = "floor%s"
                group   = "com.example"
                version = "1.0.0"
                jdk     = %s
                kotlin  = "^2.4.0"
                layout  = "simple"

                [test-dependencies]
                junit-jupiter           = { group = "org.junit.jupiter", name = "junit-jupiter", version = "=6.1.1" }
                junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "=6.1.1" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """.formatted(majorStr, majorStr));
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("src/Floor.kt"), """
                package com.example.floor
                class Floor {
                    fun jvm(): String = System.getProperty("java.specification.version")
                }
                """);
        Files.createDirectories(project.resolve("test").resolve("src"));
        Files.writeString(project.resolve("test/src/FloorTest.kt"), """
                package com.example.floor

                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                class FloorTest {
                    @Test
                    fun tests_run_on_the_pinned_jdk() {
                        // The forked test JVM IS the pinned JDK — that's the point of pinning.
                        assertEquals("%s", Floor().jvm())
                    }
                }
                """.formatted(majorStr));

        var parsed = cc.jumpkick.config.JkBuildParser.parse(project.resolve("jk.toml"));
        // Isolated session — see FirstBuildJdkTest (do not inherit monorepo jdk pin from jk test).
        Session nested = Session.defaults().withCacheDir(cache);
        SessionContext.runWhere(nested, () -> {
            BuildPlan lock = LockPlans.lockBuildPlan(
                    project, parsed, cache, null, java.util.List.of(), true, false, ResolveObserver.NOOP, null);
            assertThat(lock.run().errors()).isEmpty();

            BuildPlanner.Inputs in = new BuildPlanner.Inputs(
                    project,
                    cache,
                    project.resolve("jk.toml"),
                    project.resolve("jk-lock.toml"),
                    project,
                    1,
                    1,
                    null,
                    null,
                    /* skipTests */ false,
                    false,
                    false,
                    false,
                    java.util.Set.of(),
                    nested);
            BuildPlan plan = BuildPlanner.coreBuilder(in).build();
            BuildPlanResult result = plan.run();
            for (BuildPlanResult.Diagnostic d : result.errors()) {
                System.out.println("DIAG [" + d.step() + "]: " + d.message());
            }
            assertThat(result.errors()).isEmpty();
            assertThat(result.success())
                    .as("jdk=" + major + " pinned Kotlin project builds and its test passes")
                    .isTrue();
        });
    }
}
