// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.model.JkBuild;
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
 * android-plan §3.6's JUnit-4 path: Android's default test style is JUnit 4, and jk's runner
 * discovers engines from the <em>test classpath</em> (ServiceLoader over junit-platform-engine) —
 * so declaring {@code junit:junit} + {@code org.junit.vintage:junit-vintage-engine} in
 * {@code [test-dependencies]} runs {@code @org.junit.Test} classes with zero runner changes.
 * This proves that contract on a plain JVM module (the Android flavor differs only in classpath
 * additions — platform stubs + Robolectric config — wired by the android plugin).
 */
// Out of the unit tier: network resolve of junit4/vintage + a forked test JVM.
@Tag("integration")
class JUnit4VintageTest {

    @Test
    void junit4_tests_run_through_the_vintage_engine(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("j4"));
        Path cache = TestCaches.dir("android-spike-cache");

        Files.writeString(project.resolve("jk.toml"), """
                name    = "j4"
                group   = "com.example"
                version = "1.0.0"
                java    = 25

                [test-dependencies]
                junit          = { group = "junit", name = "junit", version = "=4.13.2" }
                vintage-engine = { group = "org.junit.vintage", name = "junit-vintage-engine", version = "=6.1.3" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Path src = Files.createDirectories(project.resolve("src/com/example"));
        Files.writeString(src.resolve("Adder.java"), """
                package com.example;

                public final class Adder {
                    public static int add(int a, int b) { return a + b; }
                }
                """);
        Path test = Files.createDirectories(project.resolve("test/src/com/example"));
        Files.writeString(test.resolve("AdderTest.java"), """
                package com.example;

                import static org.junit.Assert.assertEquals;
                import org.junit.Test;

                public class AdderTest {
                    @Test
                    public void adds() {
                        assertEquals(4, Adder.add(2, 2));
                    }
                }
                """);

        JkBuild build = JkBuildParser.parse(project.resolve("jk.toml"));
        BuildPlan lock = LockPlans.lockBuildPlan(
                project, build, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).isTrue();

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
                false, // run the tests — that IS the assertion
                false,
                false,
                false,
                Set.of(),
                SessionContext.current());
        BuildPlanResult result = BuildPlanner.fullPlan(in).run();
        assertThat(result.errors()).isEmpty();
        assertThat(result.success())
                .as("JUnit4 test discovered and passed via vintage")
                .isTrue();
    }
}
