// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Engine compile lane: Scala-only and mixed Java+Scala through Zinc compileMixed. */
@Tag("slow")
class ScalaBuildE2eTest {

    private static final String REPOS = """
            [repositories]
            central = "https://repo.maven.apache.org/maven2/"

            [test-dependencies]
            junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "=6.1.1" }
            """;

    @Test
    void scala_only_module_compiles_and_packages(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("sapp"));
        Path cache = cache();
        Files.writeString(project.resolve("jk.toml"), """
                name    = "sapp"
                group   = "com.example"
                version = "1.0.0"
                java    = 25
                scala   = "3.8.4"

                """ + REPOS);
        Path src = Files.createDirectories(project.resolve("src/com/example"));
        Files.writeString(src.resolve("Greeting.scala"), """
                package com.example

                class Greeting(val message: String):
                  def shout: String = message.toUpperCase
                """);

        BuildPlanResult result = build(project, cache);
        assertThat(result.errors()).isEmpty();
        assertThat(result.success()).isTrue();
        assertThat(project.resolve("target/classes/main/com/example/Greeting.class"))
                .exists();
        try (var walk = Files.walk(project.resolve("target"))) {
            assertThat(walk.anyMatch(f -> f.getFileName().toString().equals("sapp-1.0.0.jar")))
                    .as("packaged jar")
                    .isTrue();
        }
    }

    @Test
    void mixed_java_scala_circular_compiles(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("mixed"));
        Path cache = cache();
        Files.writeString(project.resolve("jk.toml"), """
                name    = "mixed"
                group   = "com.example"
                version = "1.0.0"
                java    = 25
                scala   = "3.8.4"

                """ + REPOS);
        Path src = Files.createDirectories(project.resolve("src"));
        Files.writeString(src.resolve("A.scala"), """
                class A:
                  def ping: String = "a"
                  def fromB(b: B): String = b.pong
                """);
        Files.writeString(src.resolve("B.java"), """
                public class B {
                  public String pong() { return "b"; }
                  public String fromA() { return new A().ping(); }
                }
                """);

        BuildPlanResult result = build(project, cache);
        assertThat(result.errors()).isEmpty();
        assertThat(result.success()).isTrue();
        assertThat(project.resolve("target/classes/main/A.class")).exists();
        assertThat(project.resolve("target/classes/main/B.class")).exists();
    }

    private static Path cache() {
        return Path.of(System.getProperty("user.dir"), "build", "scala-e2e-cache");
    }

    private static BuildPlanResult build(Path project, Path cache) throws Exception {
        var build = cc.jumpkick.config.JkBuildParser.parse(project.resolve("jk.toml"));
        BuildPlan lock = LockPlans.lockBuildPlan(
                project, build, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        BuildPlanResult lockResult = lock.run();
        assertThat(lockResult.errors()).isEmpty();

        BuildPlanner.Inputs in = new BuildPlanner.Inputs(
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
                SessionContext.current());
        return BuildPlanner.fullPlan(in).run();
    }
}
