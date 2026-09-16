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
 * {@code [test] jvm-args} and {@code [test] system-properties} reach every forked test JVM: a
 * test that reads a {@code -D} from the list, a property from the table, and checks that an
 * {@code --add-opens} from the list took effect passes, with no profile in play.
 */
// Out of the unit tier: network resolve of JUnit + a forked test JVM.
@Tag("integration")
class TestJvmArgsE2eTest {

    @Test
    void the_test_tables_jvm_args_and_system_properties_reach_the_forked_jvm(@TempDir Path tmp) throws Exception {
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

                [test]
                jvm-args = ["-Dprobe=1", "--add-opens", "java.base/java.lang=ALL-UNNAMED"]
                system-properties = { "spring.profiles.active" = "test", answer = 42 }
                """);
        Path test = Files.createDirectories(project.resolve("test/src/com/example"));
        Files.writeString(test.resolve("ProbeTest.java"), """
                package com.example;

                import static org.junit.jupiter.api.Assertions.assertEquals;
                import static org.junit.jupiter.api.Assertions.assertTrue;

                import org.junit.jupiter.api.Test;

                class ProbeTest {
                    @Test
                    void theJvmArgsAndPropertiesAreSet() {
                        assertEquals("1", System.getProperty("probe"), "-Dprobe=1 from [test] jvm-args");
                        assertEquals("test", System.getProperty("spring.profiles.active"), "[test] system-properties");
                        assertEquals("42", System.getProperty("answer"), "a number renders as its string");
                        assertTrue(
                                Object.class.getModule().isOpen("java.lang", ProbeTest.class.getModule()),
                                "--add-opens java.base/java.lang=ALL-UNNAMED from [test] jvm-args");
                    }
                }
                """);

        JkBuild build = JkBuildParser.parse(project.resolve("jk.toml"));
        Path cache = TestCaches.dir("android-spike-cache");
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
                false,
                false,
                false,
                false,
                Set.of(),
                SessionContext.current());
        BuildPlanResult result = BuildPlanner.fullPlan(in).run();
        assertThat(result.errors()).isEmpty();
        assertThat(result.success()).isTrue();
    }
}
