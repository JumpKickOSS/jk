// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The KSP round, on a plain JVM project. {@code PlannerKsp.kspStep} is not Android-specific — it
 * needs only {@code kotlin} plus a non-empty {@code [processor-dependencies]} whose jar registers
 * the {@code SymbolProcessorProvider} SPI — but its only coverage was the two Android/Hilt tests,
 * which need the SDK, its licences and Google Maven. This is the same round with none of that.
 *
 * <p>Moshi's codegen is the vehicle because it is Central-only and pure JVM. Referencing the
 * generated {@code GreetingJsonAdapter} from hand-written Kotlin is the acceptance: it compiles
 * only if KSP ran <em>and</em> its output was folded back into the same compile.
 *
 * <p>Network test (Maven Central); the CAS persists under build/ so repeat runs are warm.
 */
@Tag("slow")
class KspGeneratedSourcesTest {

    @Test
    void a_ksp_processor_generates_kotlin_that_the_same_build_compiles(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("app"));
        Path cache = Path.of(System.getProperty("user.dir"), "build", "android-spike-cache");

        Files.writeString(project.resolve("jk.toml"), """
                name    = "kspgen"
                group   = "com.example"
                version = "1.0.0"
                java    = 25
                kotlin  = "^2.4.0"

                [dependencies]
                moshi = { group = "com.squareup.moshi", name = "moshi", version = "=1.15.2" }

                [processor-dependencies]
                moshi-codegen = { group = "com.squareup.moshi", name = "moshi-kotlin-codegen", version = "=1.15.2" }

                # This project runs no tests; owning [test-dependencies] keeps the injected
                # junit-jupiter "latest" out of the graph and the launcher pin keeps the lock
                # deterministic.
                [test-dependencies]
                junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "=6.1.1" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Path src = Files.createDirectories(project.resolve("src/com/example/gen"));
        Files.writeString(src.resolve("Main.kt"), """
                package com.example.gen

                import com.squareup.moshi.JsonClass
                import com.squareup.moshi.Moshi

                @JsonClass(generateAdapter = true)
                data class Greeting(val message: String)

                fun main() {
                    // GreetingJsonAdapter exists only if the KSP round ran and its generated
                    // sources were unioned into this compile.
                    println(GreetingJsonAdapter(Moshi.Builder().build()).toJson(Greeting("hi")))
                }
                """);

        JkBuild build = JkBuildParser.parse(project.resolve("jk.toml"));
        BuildPlan lock = LockPlans.lockBuildPlan(
                project, build, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        BuildPlanResult lockResult = lock.run();
        assertThat(lockResult.errors()).isEmpty();

        BuildPlanResult result = fullBuild(project, cache);
        assertThat(result.errors()).isEmpty();
        assertThat(result.success()).isTrue();

        assertThat(anyFile(project.resolve("target/ksp"), "GreetingJsonAdapter.kt"))
                .as("KSP wrote the generated adapter under target/ksp")
                .isTrue();
        assertThat(anyFile(project.resolve("target/classes"), "GreetingJsonAdapter.class"))
                .as("kotlinc compiled the KSP-generated source in the same round")
                .isTrue();
        assertThat(anyFile(project.resolve("target/classes"), "MainKt.class"))
                .as("the hand-written source that references the generated type compiled")
                .isTrue();

        // Second run over an unchanged tree: the .kspstamp freshness gate must hold, and the
        // rebuild must still be green (a stale-stamp bug shows up as a failed second build).
        BuildPlanResult again = fullBuild(project, cache);
        assertThat(again.errors()).isEmpty();
        assertThat(again.success()).isTrue();
    }

    private static BuildPlanResult fullBuild(Path project, Path cache) throws Exception {
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

    private static boolean anyFile(Path root, String name) throws IOException {
        if (!Files.isDirectory(root)) return false;
        try (var walk = Files.walk(root)) {
            return walk.anyMatch(f -> f.getFileName().toString().equals(name));
        }
    }
}
