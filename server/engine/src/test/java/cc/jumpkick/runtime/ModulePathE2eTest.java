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
 * End-to-end: a module with a {@code module-info.java} compiles on the module path. Its descriptor
 * requires an explicit module ({@code org.jetbrains.annotations} ships a descriptor) and an
 * automatic one ({@code commons-lang3} names itself through {@code Automatic-Module-Name}), both
 * declared as ordinary dependencies; the test compile patches the module so a test in the module's
 * package reaches its package-private members while reading JUnit from the test classpath.
 *
 * <p>Network: the dependencies come from Maven Central into the cache under {@code build/}, which
 * persists across runs so repeats are warm.
 */
@Tag("integration")
class ModulePathE2eTest {

    private static final String MANIFEST = """
            name    = "modular"
            group   = "com.example"
            version = "1.0.0"
            java    = 25

            [repositories]
            central = "https://repo.maven.apache.org/maven2/"

            [dependencies]
            annotations   = "org.jetbrains:annotations:26.0.2"
            commons-lang3 = "org.apache.commons:commons-lang3:3.20.0"

            [test-dependencies]
            junit-jupiter           = "org.junit.jupiter:junit-jupiter:6.1.3"
            junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "6.1.3" }
            """;

    @Test
    void a_module_descriptor_requiring_an_explicit_and_an_automatic_module_compiles(@TempDir Path tmp)
            throws Exception {
        Path project = Files.createDirectories(tmp.resolve("modular"));
        Files.writeString(project.resolve("jk.toml"), MANIFEST);
        Path src = Files.createDirectories(project.resolve("src/main/java/com/example"));
        Files.writeString(project.resolve("src/main/java/module-info.java"), """
                module com.example.modular {
                    requires org.jetbrains.annotations;
                    requires org.apache.commons.lang3;
                    exports com.example;
                }
                """);
        Files.writeString(src.resolve("Greeting.java"), """
                package com.example;

                import org.jetbrains.annotations.NotNull;

                public final class Greeting {
                    static String secret() {
                        return "package-private";
                    }

                    public static @NotNull String of(String name) {
                        return org.apache.commons.lang3.StringUtils.capitalize(name);
                    }
                }
                """);
        Path test = Files.createDirectories(project.resolve("src/test/java/com/example"));
        Files.writeString(test.resolve("GreetingTest.java"), """
                package com.example;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import org.junit.jupiter.api.Test;

                class GreetingTest {
                    @Test
                    void capitalizes() {
                        assertEquals("Jk", Greeting.of("jk"));
                        assertEquals("package-private", Greeting.secret());
                    }
                }
                """);

        BuildPlanResult result = build(project, cache());

        assertThat(result.errors()).isEmpty();
        assertThat(result.success()).isTrue();
        assertThat(project.resolve("target/classes/main/module-info.class")).exists();
        assertThat(project.resolve("target/classes/main/com/example/Greeting.class"))
                .exists();
        assertThat(project.resolve("target/classes/test/com/example/GreetingTest.class"))
                .exists();
    }

    private static Path cache() throws Exception {
        return Files.createDirectories(Path.of("build/test-cache/module-path"));
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
                false,
                false,
                false,
                false,
                Set.of(),
                SessionContext.current());
        return BuildPlanner.fullPlan(in).run();
    }
}
