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
 * The forked test JVM runs on the platform default thread stack, as Surefire's and Gradle's do: a
 * test that recurses six thousand generic frames deep — the shape of a recursive selection sort
 * over a five-thousand-element array — passes under jk as it does under Maven. A 512 KiB stack
 * dies of {@code StackOverflowError} at that depth.
 */
// Out of the unit tier: network resolve of JUnit + a forked test JVM.
@Tag("integration")
class TestStackDepthE2eTest {

    @Test
    void a_deep_recursion_that_passes_under_maven_passes_under_jk(@TempDir Path tmp) throws Exception {
        Path project = tmp.resolve("deep");
        Files.createDirectories(project);
        Files.writeString(project.resolve("jk.toml"), """
                name    = "deep"
                group   = "com.example"
                version = "1.0.0"
                java    = 25

                [test-dependencies]
                junit-jupiter = { group = "org.junit.jupiter", name = "junit-jupiter", version = "6.1.3" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Path test = Files.createDirectories(project.resolve("test/src/com/example"));
        Files.writeString(test.resolve("DeepRecursionTest.java"), """
                package com.example;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import org.junit.jupiter.api.Test;

                class DeepRecursionTest {
                    static <T extends Comparable<T>> int minIndex(T[] a, int start) {
                        if (start == a.length - 1) return start;
                        int rest = minIndex(a, start + 1);
                        return a[start].compareTo(a[rest]) <= 0 ? start : rest;
                    }

                    @Test
                    void recursesSixThousandFramesDeep() {
                        Integer[] a = new Integer[6000];
                        for (int i = 0; i < a.length; i++) a[i] = a.length - i;
                        assertEquals(a.length - 1, minIndex(a, 0));
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
        assertThat(result.errors())
                .as(
                        "the platform default stack survives the recursion; a StackOverflowError here means the fork got -Xss")
                .isEmpty();
        assertThat(result.success()).isTrue();
    }
}
