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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code [profiles.<name>] jvm-args} reach the forked test JVM: a test that reads a system
 * property the profile sets passes only under {@code --profile}, and the profile is a run-tests
 * input, so the two runs never share a green marker.
 */
// Out of the unit tier: network resolve of JUnit + two forked test JVMs.
@Tag("integration")
class ProfileJvmArgsE2eTest {

    @Test
    void the_profile_jvm_args_reach_the_test_jvm_only_when_the_profile_is_active(@TempDir Path tmp) throws Exception {
        Path cache = TestCaches.dir("android-spike-cache");

        BuildPlanResult withoutProfile = run(tmp.resolve("plain"), cache, null);
        assertThat(withoutProfile.success())
                .as("no profile: the property is unset and the test fails")
                .isFalse();
        assertThat(withoutProfile.errors()).anySatisfy(d -> {
            assertThat(d.className()).isEqualTo("com.example.ProbeTest");
            assertThat(d.message()).contains("probe");
        });

        BuildPlanResult withProfile = run(tmp.resolve("probe"), cache, "probe");
        assertThat(withProfile.errors()).isEmpty();
        assertThat(withProfile.success())
                .as("--profile probe: -Dprobe=1 rides the forked JVM")
                .isTrue();
    }

    private static BuildPlanResult run(Path project, Path cache, @Nullable String profile) throws Exception {
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

                [profiles.probe]
                jvm-args = ["-Dprobe=1"]
                """);
        Path test = Files.createDirectories(project.resolve("test/src/com/example"));
        Files.writeString(test.resolve("ProbeTest.java"), """
                package com.example;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import org.junit.jupiter.api.Test;

                class ProbeTest {
                    @Test
                    void probeIsSet() {
                        assertEquals("1", System.getProperty("probe"), "probe property from the profile");
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
                profile,
                null,
                false,
                false,
                false,
                false,
                Set.of(),
                SessionContext.current());
        return BuildPlanner.fullPlan(in).run();
    }
}
