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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * android-plan Task 5, blocker 2: project-declared Kotlin compiler plugins
 * ({@code [[kotlin-plugins]]}) — kotlinx-serialization is the Now-in-Android consumer. A
 * {@code @Serializable} class only gets its generated {@code .serializer()} companion when the
 * serialization compiler plugin actually loaded into kotlinc, so compiling a reference to it IS
 * the acceptance. Plain JVM project — the plugin surface is core, not Android-specific.
 *
 * <p>Network test (Maven Central; kotlinc worker via the test JVM's worker-jar property); the CAS
 * persists under build/ so repeat runs are warm.
 */
@Tag("slow")
class KotlinSerializationTest {

    @Test
    void serializable_class_compiles_with_the_declared_plugin(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("app"));
        Path cache = TestCaches.dir("android-spike-cache");

        Files.writeString(project.resolve("jk.toml"), """
                name    = "ser"
                group   = "com.example"
                version = "1.0.0"
                java    = 25
                kotlin  = "^2.4.10"

                [[kotlin-plugins]]
                coordinate = "org.jetbrains.kotlin:kotlin-serialization-compiler-plugin-embeddable"

                [dependencies]
                serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version = "1.7.3" }

                # This project runs no tests; owning [test-dependencies] keeps the injected
                # junit-jupiter "latest" out of the graph and the launcher pin keeps the lock
                # deterministic. (Half-published releases — metadata advertising a version
                # whose POM still 404s, observed live with junit 6.1.2 — no longer fail the
                # solve: the resolver retreats to the prior release.)
                [test-dependencies]
                junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "=6.1.3" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Path src = Files.createDirectories(project.resolve("src/com/example/ser"));
        Files.writeString(src.resolve("Main.kt"), """
                package com.example.ser

                import kotlinx.serialization.Serializable
                import kotlinx.serialization.json.Json

                @Serializable
                data class Greeting(val message: String)

                fun main() {
                    // serializer() only exists when the serialization compiler plugin ran.
                    println(Json.encodeToString(Greeting.serializer(), Greeting("hi")))
                }
                """);

        JkBuild build = JkBuildParser.parse(project.resolve("jk.toml"));
        assertThat(build.build().kotlinPlugins()).hasSize(1);
        assertThat(build.build().kotlinPlugins().getFirst().id())
                .isEqualTo("kotlin-serialization-compiler-plugin-embeddable");

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
        BuildPlan plan = BuildPlanner.fullPlan(in);
        BuildPlanResult result = plan.run();
        System.out.println(
                "STEPS: " + plan.steps().stream().map(ph -> ph.name()).toList());
        try (var w = Files.walk(project.resolve("target"))) {
            w.forEach(f -> System.out.println("TREE: " + project.relativize(f)));
        } catch (IOException ignored) {
            System.out.println("TREE: (no target)");
        }
        assertThat(result.errors()).isEmpty();
        assertThat(result.success()).isTrue();
        assertThat(anyFile(project.resolve("target"), "MainKt.class"))
                .as("kotlinc compiled Main.kt")
                .isTrue();
        // The plugin generated the serializer companion (a $serializer class in the output).
        assertThat(anyFile(project.resolve("target"), "$serializer.class"))
                .as("serialization plugin generated the $serializer class")
                .isTrue();
    }

    private static boolean anyFile(Path root, String nameFragment) throws IOException {
        if (!Files.isDirectory(root)) return false;
        try (var walk = Files.walk(root)) {
            return walk.anyMatch(f -> f.getFileName().toString().contains(nameFragment));
        }
    }
}
