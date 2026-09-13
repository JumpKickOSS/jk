// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.testing.TestCaches;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The package step's key names the engine that packages: with nothing else changed, a second
 * build under the same engine restores the jar, and a build under another engine identity — a
 * rebuilt engine of the same version, whose packaging rules may differ — packages again instead
 * of restoring what the previous engine produced.
 */
@Tag("integration")
class PackagerIdentityKeyTest {

    @Test
    void a_new_engine_identity_repackages_where_the_same_one_restores(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("lib"));
        Path cache = TestCaches.dir("packager-identity-cache");
        Files.writeString(project.resolve("jk.toml"), """
                name    = "lib"
                group   = "com.example"
                version = "1.0.0"
                java    = 25

                [test-dependencies]
                junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "=6.1.3" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Path src = Files.createDirectories(project.resolve("src/com/example"));
        Files.writeString(src.resolve("Lib.java"), """
                package com.example;

                public class Lib {
                    public static String greet() { return "hi"; }
                }
                """);
        JkBuild parsed = JkBuildParser.parse(project.resolve("jk.toml"));
        BuildLayout layout = BuildLayout.of(project, parsed);
        String jarName = layout.mainJar().getFileName().toString();
        Session session = Session.defaults().withCacheDir(cache);

        SessionContext.runWhere(session, () -> {
            try {
                BuildPlan lock = LockPlans.lockBuildPlan(
                        project, parsed, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
                assertThat(lock.run().errors()).isEmpty();

                BuildIdentity.overrideBuildIdForTests("engine-one-0001");
                assertThat(packageLabels(project, cache, session))
                        .as("first build under engine one packages")
                        .anyMatch(l -> l.equals("package " + jarName));
                assertThat(packageLabels(project, cache, session))
                        .as("the same engine, the same inputs: the jar is restored")
                        .anyMatch(l -> l.equals(jarName + " up-to-date"));

                BuildIdentity.overrideBuildIdForTests("engine-two-0002");
                assertThat(packageLabels(project, cache, session))
                        .as("another engine identity is another producer: the jar is packaged again")
                        .anyMatch(l -> l.equals("package " + jarName));
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                BuildIdentity.overrideBuildIdForTests(null);
            }
        });
    }

    /** What the package-jar step said about itself during one build. */
    private static List<String> packageLabels(Path project, Path cache, Session session) {
        List<String> labels = new ArrayList<>();
        BuildPlan plan = BuildPlanner.coreBuilder(new BuildPlanner.Inputs(
                        project,
                        cache,
                        project.resolve("jk.toml"),
                        project.resolve("jk-lock.toml"),
                        project,
                        1,
                        0,
                        null,
                        null,
                        true,
                        false,
                        false,
                        false,
                        Set.of(),
                        session))
                .build();
        plan.addListener(new BuildPlanListener() {
            @Override
            public void label(String step, String label) {
                if (TaskNames.PACKAGE_JAR.equals(step)) {
                    synchronized (labels) {
                        labels.add(label);
                    }
                }
            }
        });
        BuildPlanResult result = plan.run();
        assertThat(result.errors()).isEmpty();
        assertThat(result.success()).isTrue();
        synchronized (labels) {
            return List.copyOf(labels);
        }
    }
}
