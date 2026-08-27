// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
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

/**
 * {@code BEFORE_COMPILE} is the documented codegen anchor, so a {@code .java} a stem script writes
 * must reach javac and end up in the jar as a class — not copied verbatim into {@code classes/} as
 * a data file.
 *
 * <p>The second build matters as much as the first: the anchor's output is action-cached, so a
 * cache hit has to leave the source root in the same state a real run does, or the artifact
 * silently depends on whether the cache was warm.
 */
@Tag("slow")
class BuildLogicCodegenE2eTest {

    private static final String REPOS = """
            [repositories]
            central = "https://repo.maven.apache.org/maven2/"

            [test-dependencies]
            junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "=6.1.3" }
            """;

    @Test
    void before_compile_generated_java_is_compiled_into_the_jar(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("gen"));
        Path cache = cache();
        Files.writeString(project.resolve("jk.toml"), """
                name    = "gen"
                group   = "com.example"
                version = "1.0.0"
                jdk     = 25
                java    = 25

                """ + REPOS);

        Path src = Files.createDirectories(project.resolve("src/com/example"));
        Files.writeString(src.resolve("App.java"), """
                package com.example;

                public final class App {
                    public static String version() {
                        return Generated.VALUE;
                    }
                }
                """);

        Files.createDirectories(project.resolve(".jk"));
        Files.writeString(project.resolve(".jk/before-compile.groovy"), """
                def pkg = outDir.resolve('com/example')
                pkg.toFile().mkdirs()
                pkg.resolve('Generated.java').toFile().text = '''
                package com.example;
                public final class Generated {
                  public static final String VALUE = "1.0.0";
                }
                '''
                """);

        BuildPlanResult first = build(project, cache);
        assertThat(first.errors()).isEmpty();
        assertThat(first.success()).isTrue();
        assertThat(project.resolve("target/classes/main/com/example/Generated.class"))
                .as("generated source reached javac")
                .exists();
        assertThat(project.resolve("target/classes/main/com/example/App.class")).exists();
        assertThat(project.resolve("target/classes/main/com/example/Generated.java"))
                .doesNotExist();

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
        var build = JkBuildParser.parse(project.resolve("jk.toml"));
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
