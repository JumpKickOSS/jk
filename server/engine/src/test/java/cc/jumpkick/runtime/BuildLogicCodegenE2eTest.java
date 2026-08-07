// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code BEFORE_COMPILE} is the documented codegen anchor, so a {@code .java} it writes must reach
 * javac and end up in the jar as a class — not copied verbatim into {@code classes/} as a data
 * file, which is what merging into the classes tree did (JK-1602).
 *
 * <p>The second build matters as much as the first: the anchor's output is action-cached, so a
 * cache hit has to leave the source root in the same state a real run does, or the artifact
 * silently depends on whether the cache was warm.
 */
@Tag("slow")
class BuildLogicCodegenE2eTest {

    private static final String REPOS =
            """
            [repositories]
            central = "https://repo.maven.apache.org/maven2/"

            [test-dependencies]
            junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "=6.1.1" }
            """;

    @Test
    void before_compile_generated_java_is_compiled_into_the_jar(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("gen"));
        Path cache = cache();
        Files.writeString(project.resolve("jk.toml"), """
                [project]
                name    = "gen"
                group   = "com.example"
                version = "1.0.0"
                jdk     = 25
                java    = 25
                layout  = "simple"

                """ + REPOS);

        // Product code references a type that only exists if codegen ran and was compiled.
        Path src = Files.createDirectories(project.resolve("src/com/example"));
        Files.writeString(src.resolve("App.java"), """
                package com.example;

                public final class App {
                    public static String version() {
                        return Generated.VALUE;
                    }
                }
                """);

        Path logic = Files.createDirectories(project.resolve(".jk-build/src/demo"));
        Files.writeString(logic.resolve("GenLogic.java"), """
                package demo;

                import cc.jumpkick.plugin.buildlogic.*;
                import java.nio.file.Files;
                import java.nio.file.Path;

                public class GenLogic implements BuildLogicContributor {
                    public void register(BuildLogicGraph g) {
                        g.task("gen-version", BuildLogicAnchor.BEFORE_COMPILE, ctx -> {
                            Path out = ctx.outDir().resolve("com/example");
                            Files.createDirectories(out);
                            Files.writeString(out.resolve("Generated.java"),
                                "package com.example;\\n"
                                    + "public final class Generated {\\n"
                                    + "  public static final String VALUE = \\"1.0.0\\";\\n"
                                    + "}\\n");
                        });
                    }
                }
                """);

        BuildPlanResult first = build(project, cache);
        assertThat(first.errors()).isEmpty();
        assertThat(first.success()).isTrue();
        assertThat(project.resolve("target/classes/main/com/example/Generated.class"))
                .as("generated source reached javac")
                .exists();
        assertThat(project.resolve("target/classes/main/com/example/App.class")).exists();
        // The .java itself must not be packaged as a data file.
        assertThat(project.resolve("target/classes/main/com/example/Generated.java"))
                .doesNotExist();

        // Second build: the anchor's output is a cache hit, and must still compile.
        BuildPlanResult second = build(project, cache);
        assertThat(second.errors()).isEmpty();
        assertThat(second.success()).isTrue();
        assertThat(project.resolve("target/classes/main/com/example/Generated.class"))
                .as("cache hit leaves the source root usable")
                .exists();
    }

    private static Path cache() throws Exception {
        return Files.createDirectories(Path.of("build/test-cache/build-logic-codegen"));
    }

    private static BuildPlanResult build(Path project, Path cache) throws Exception {
        var build = cc.jumpkick.config.JkBuildParser.parse(project.resolve("jk.toml"));
        BuildPlan lock = LockPlans.lockBuildPlan(
                project, build, cache, null, java.util.List.of(), true, false, ResolveObserver.NOOP, null);
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
                java.util.Set.of(),
                SessionContext.current());
        return BuildPlanner.coreBuilder(in).build().run();
    }
}
