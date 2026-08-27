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
 * wave 2 acceptance: the engine compile lane drives the groovy-compiler worker end to
 * end — a groovy-only module compiles and packages, and a mixed Groovy+Java module resolves
 * references in <em>both</em> directions (Groovy→Java via the joint sweep, Java→Groovy via the
 * emitted Groovy classes + retained stubs) before assemble-classes merges the outputs.
 *
 * <p>Network test (Maven Central for the Groovy closure; groovyc worker via the test JVM's
 * worker-jar property); the CAS persists under build/ so repeat runs are warm.
 */
@Tag("slow")
class GroovyBuildE2eTest {

    private static final String REPOS = """
            [repositories]
            central = "https://repo.maven.apache.org/maven2/"

            # This project runs no tests; owning [test-dependencies] keeps the injected
            # junit-jupiter "latest" out of the graph and the launcher pin keeps the lock
            # deterministic (see KotlinSerializationTest).
            [test-dependencies]
            junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "=6.1.3" }
            """;

    @Test
    void groovy_only_module_compiles_and_packages(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("gapp"));
        Path cache = cache();
        Files.writeString(project.resolve("jk.toml"), """
                name    = "gapp"
                group   = "com.example"
                version = "1.0.0"
                jdk     = 25
                groovy  = "5.0.4"

                """ + REPOS);
        Path src = Files.createDirectories(project.resolve("src/com/example"));
        Files.writeString(src.resolve("Greeting.groovy"), """
                package com.example

                class Greeting {
                    String message
                    String shout() { message.toUpperCase() }
                }
                """);

        BuildPlanResult result = build(project, cache);
        assertThat(result.errors()).isEmpty();
        assertThat(result.success()).isTrue();
        assertThat(project.resolve("target/classes/main/com/example/Greeting.class"))
                .exists();
        // Groovy-only modules still stamp + package.
        assertThat(project.resolve("target/classes/main/.gstamp")).exists();
        try (var walk = Files.walk(project.resolve("target"))) {
            assertThat(walk.anyMatch(f -> f.getFileName().toString().equals("gapp-1.0.0.jar")))
                    .as("packaged jar")
                    .isTrue();
        }
    }

    @Test
    void mixed_module_resolves_both_directions(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("mixed"));
        Path cache = cache();
        Files.writeString(project.resolve("jk.toml"), """
                name    = "mixed"
                group   = "com.example"
                version = "1.0.0"
                jdk     = 25
                java    = 25
                groovy  = "5.0.4"

                """ + REPOS);
        Path src = Files.createDirectories(project.resolve("src/com/example"));
        // Groovy→Java: the Groovy class calls a Java helper (joint sweep).
        Files.writeString(src.resolve("Util.java"), """
                package com.example;

                public final class Util {
                    private Util() {}

                    public static String decorate(String s) {
                        return "<" + s + ">";
                    }
                }
                """);
        Files.writeString(src.resolve("Greeter.groovy"), """
                package com.example

                class Greeter {
                    String greet(String name) { Util.decorate("hi " + name) }
                }
                """);
        // Java→Groovy: a Java class references the Groovy type (stubs/classes on javac's path).
        Files.writeString(src.resolve("Main.java"), """
                package com.example;

                public class Main {
                    public static void main(String[] args) {
                        System.out.println(new Greeter().greet("jk"));
                    }
                }
                """);

        BuildPlanResult result = build(project, cache);
        assertThat(result.errors()).isEmpty();
        assertThat(result.success()).isTrue();
        Path classes = project.resolve("target/classes/main/com/example");
        assertThat(classes.resolve("Util.class")).exists();
        assertThat(classes.resolve("Greeter.class")).exists();
        assertThat(classes.resolve("Main.class")).exists();
    }

    private static Path cache() {
        return Path.of(System.getProperty("user.dir"), "build", "groovy-e2e-cache");
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
