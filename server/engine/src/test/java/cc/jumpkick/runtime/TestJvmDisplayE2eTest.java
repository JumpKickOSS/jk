// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.testing.TestCaches;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The display variables of the shell running {@code jk} reach the forked test JVM, over whatever
 * the engine's own shell had, with no {@code [env]} or {@code [test] env} asking: a test that reads
 * {@code DISPLAY} and {@code XAUTHORITY} from its environment sees the request's values, and jk
 * sets no {@code java.awt.headless} of its own.
 */
// Out of the unit tier: network resolve of JUnit + a forked test JVM.
@Tag("integration")
class TestJvmDisplayE2eTest {

    @Test
    void the_requests_display_reaches_the_forked_jvm(@TempDir Path tmp) throws Exception {
        Path project = tmp.resolve("probe");
        Files.createDirectories(project);
        Files.writeString(project.resolve("jk.toml"), """
                name    = "probing"
                group   = "com.example"
                version = "1.0.0"
                java    = 25

                [test-dependencies]
                junit-jupiter = { group = "org.junit.jupiter", name = "junit-jupiter", version = "6.1.3" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Path test = Files.createDirectories(project.resolve("test/src/com/example"));
        Files.writeString(test.resolve("DisplayTest.java"), """
                package com.example;

                import static org.junit.jupiter.api.Assertions.assertEquals;
                import static org.junit.jupiter.api.Assertions.assertNull;

                import org.junit.jupiter.api.Test;

                class DisplayTest {
                    @Test
                    void theShellsDisplayIsThisJvms() {
                        assertEquals(":99", System.getenv("DISPLAY"), "DISPLAY of the shell running jk");
                        assertEquals("/tmp/xvfb-run.abc/Xauthority", System.getenv("XAUTHORITY"), "its XAUTHORITY");
                        assertNull(System.getProperty("java.awt.headless"), "jk sets no java.awt.headless");
                    }
                }
                """);

        JkBuild build = JkBuildParser.parse(project.resolve("jk.toml"));
        Path cache = TestCaches.dir("android-spike-cache");
        BuildPlan lock = LockPlans.lockBuildPlan(
                project, build, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).isTrue();
        Session previous = SessionContext.current();
        SessionContext.install(previous.withVariant(
                previous.variant(), Map.of("DISPLAY", ":99", "XAUTHORITY", "/tmp/xvfb-run.abc/Xauthority")));
        try {
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
                    false,
                    false,
                    false,
                    false,
                    Set.of(),
                    SessionContext.current());
            BuildPlanResult result = BuildPlanner.fullPlan(in).run();
            assertThat(result.errors()).isEmpty();
            assertThat(result.success()).isTrue();
        } finally {
            SessionContext.install(previous);
        }
    }
}
