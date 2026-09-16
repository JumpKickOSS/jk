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
 * End-to-end: an annotation processor on the compile classpath runs the way javac and Maven run
 * it. Lombok declared as a plain dependency generates the getter a module calls; the same module
 * with a {@code [processor-dependencies]} table that names something else compiles against that
 * path alone, so the classpath's Lombok stays silent and the call does not resolve.
 *
 * <p>Network: Lombok, jspecify and the junit launcher pin come from Maven Central into the cache
 * under {@code build/}, which persists across runs so repeats are warm.
 */
@Tag("integration")
class ClasspathProcessorE2eTest {

    private static final String MANIFEST = """
            name    = "getters"
            group   = "com.example"
            version = "1.0.0"
            java    = 25

            [repositories]
            central = "https://repo.maven.apache.org/maven2/"

            [dependencies]
            lombok = "org.projectlombok:lombok:1.18.48"

            [test-dependencies]
            junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "6.1.3" }
            """;

    private static final String PROCESSOR_TABLE = """

            [processor-dependencies]
            jspecify = "org.jspecify:jspecify:1.0.0"
            """;

    @Test
    void lombok_declared_as_a_plain_dependency_runs(@TempDir Path tmp) throws Exception {
        Path project = write(tmp.resolve("plain"), MANIFEST);

        BuildPlanResult result = build(project, cache());

        assertThat(result.errors()).isEmpty();
        assertThat(result.success()).isTrue();
        assertThat(project.resolve("target/classes/main/com/example/Person.class"))
                .exists();
        assertThat(project.resolve("target/classes/main/com/example/App.class")).exists();
    }

    @Test
    void a_declared_processor_path_shadows_the_classpath_processor(@TempDir Path tmp) throws Exception {
        Path project = write(tmp.resolve("declared"), MANIFEST + PROCESSOR_TABLE);

        BuildPlanResult result = build(project, cache());

        assertThat(result.success())
                .as("Lombok is on the compile classpath but only the declared path is searched")
                .isFalse();
        assertThat(result.errors().toString()).contains("getName");
    }

    private static Path write(Path project, String manifest) throws Exception {
        Files.createDirectories(project);
        Files.writeString(project.resolve("jk.toml"), manifest);
        // Lombok reads every lombok.config up the directory tree; this fixture lives under the
        // jk checkout, whose config makes accessors fluent (name(), not getName()), so the
        // fixture's own config ends the walk and the getter keeps its bean name.
        Files.writeString(project.resolve("lombok.config"), "config.stopBubbling = true\n");
        Path src = Files.createDirectories(project.resolve("src/main/java/com/example"));
        Files.writeString(src.resolve("Person.java"), """
                package com.example;

                public final class Person {
                    @lombok.Getter
                    private final String name;

                    public Person(String name) {
                        this.name = name;
                    }
                }
                """);
        Files.writeString(src.resolve("App.java"), """
                package com.example;

                public final class App {
                    public static String greet() {
                        return new Person("jk").getName();
                    }
                }
                """);
        return project;
    }

    private static Path cache() throws Exception {
        return Files.createDirectories(Path.of("build/test-cache/classpath-processor"));
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
