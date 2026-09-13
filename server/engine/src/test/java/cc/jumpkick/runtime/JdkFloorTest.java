// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.jdk.JdkEnsure;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.testing.TestCaches;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Explicit {@code jdk = N} provisioning (rare in product examples — prefer {@code java = N}
 * so the host JDK 25 cross-compiles). These tests <em>must</em> pin {@code jdk = 17} /
 * {@code jdk = 21} to prove workers use that install as the forked test JVM.
 *
 * <p>{@code jdk = 17} is a floor on the major, not a requirement (see {@code docs/user/lockfile.md}
 * "Toolchain pins"): with only a 25 on disk the build is entitled to it, and in a sandbox home no
 * 17 exists until something installs one. So each case installs its major first, the way {@code jk
 * jdk install} would, and then proves the resolver forks the install the manifest named rather
 * than the newer JVM the engine itself runs on.
 *
 * <p>Network tests (Maven Central and the JDK feed; provisions the pin on first run); the CAS
 * under build/ and the sandbox jdks root keep repeats warm.
 */
@Tag("network")
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
        // The named major has to exist for the pin to name it: a floor with nothing on disk at
        // that major is cleared by the newer JVM, by design.
        JdkEnsure.install("temurin-" + major, warning -> System.out.println("JDK: " + warning));
        Path project = Files.createDirectories(tmp.resolve("app" + major));
        Path cache = TestCaches.dir("android-spike-cache");
        String majorStr = Integer.toString(major);

        Files.writeString(project.resolve("jk.toml"), """
                name    = "floor%s"
                group   = "com.example"
                version = "1.0.0"
                jdk     = %s
                kotlin  = "^2.4.10"

                [test-dependencies]
                junit-jupiter           = { group = "org.junit.jupiter", name = "junit-jupiter", version = "=6.1.3" }
                junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "=6.1.3" }

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

        var parsed = JkBuildParser.parse(project.resolve("jk.toml"));
        // Isolated session — see FirstBuildJdkTest (do not inherit monorepo jdk pin from jk test).
        Session nested = Session.defaults().withCacheDir(cache);
        SessionContext.runWhere(nested, () -> {
            BuildPlan lock = LockPlans.lockBuildPlan(
                    project, parsed, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
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
                    Set.of(),
                    nested);
            BuildPlan plan = BuildPlanner.fullPlan(in);
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
