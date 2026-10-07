// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.testing.TestCaches;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * One module with Kotlin and Groovy sources, main and test: kotlinc compiles first and groovyc
 * against its output, so a Groovy class calls a Kotlin one and a Groovy test reads a Kotlin test
 * helper; both outputs land in the module's classes and every test runs.
 *
 * <p>Network test (Maven Central for the Kotlin and Groovy closures).
 */
@Tag("slow")
class KotlinGroovyModuleE2eTest {

    @Test
    void kotlin_then_groovy_compile_and_both_test_languages_run(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("kg"));
        Files.writeString(project.resolve("jk.toml"), """
                name    = "kg"
                group   = "com.example"
                version = "1.0.0"
                kotlin  = "^2.4.10"
                groovy  = "5.0.4"

                [test-dependencies]
                junit-jupiter           = { group = "org.junit.jupiter", name = "junit-jupiter", version = "6.1.3" }
                junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "6.1.3" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Path kotlinMain = Files.createDirectories(project.resolve("src/main/kotlin/com/example"));
        Files.writeString(kotlinMain.resolve("Greeting.kt"), """
                package com.example

                class Greeting(val name: String) {
                    fun text(): String = "hello " + name
                }
                """);
        Path groovyMain = Files.createDirectories(project.resolve("src/main/groovy/com/example"));
        Files.writeString(groovyMain.resolve("Shouter.groovy"), """
                package com.example

                class Shouter {
                    static String shout(String name) { new Greeting(name).text().toUpperCase() }
                }
                """);
        Path kotlinTest = Files.createDirectories(project.resolve("src/test/kotlin/com/example"));
        Files.writeString(kotlinTest.resolve("Names.kt"), """
                package com.example

                object Names {
                    const val WHO = "jk"
                }
                """);
        Files.writeString(kotlinTest.resolve("GreetingTest.kt"), """
                package com.example

                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                class GreetingTest {
                    @Test
                    fun greets() = assertEquals("hello jk", Greeting(Names.WHO).text())
                }
                """);
        Path groovyTest = Files.createDirectories(project.resolve("src/test/groovy/com/example"));
        Files.writeString(groovyTest.resolve("ShouterTest.groovy"), """
                package com.example

                import org.junit.jupiter.api.Test
                import static org.junit.jupiter.api.Assertions.assertEquals

                class ShouterTest {
                    @Test
                    void shouts() { assertEquals("HELLO JK", Shouter.shout(Names.WHO)) }
                }
                """);

        Path cache = TestCaches.dir("kotlin-groovy-e2e-cache");
        BuildPlan lock = LockPlans.lockBuildPlan(
                project,
                JkBuildParser.parse(project.resolve("jk.toml")),
                cache,
                null,
                List.of(),
                true,
                false,
                ResolveObserver.NOOP,
                null);
        assertThat(lock.run().errors()).isEmpty();

        Session session = Session.defaults();
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
                /* skipTests */ false,
                false,
                false,
                false,
                Set.of(),
                session);
        BuildPlan plan = BuildPlanner.fullPlan(in);
        BuildPlanResult result = SessionContext.where(session, plan::run);
        assertThat(result.errors()).isEmpty();
        assertThat(result.success()).isTrue();

        Path classes = project.resolve("target/classes/com/example");
        assertThat(classes.resolve("Greeting.class")).exists();
        assertThat(classes.resolve("Shouter.class")).exists();
        Path testClasses = project.resolve("target/test-classes/com/example");
        assertThat(testClasses.resolve("GreetingTest.class")).exists();
        assertThat(testClasses.resolve("ShouterTest.class")).exists();
        TestSummary tests = plan.get(BuildPlanner.TEST_RESULT).orElseThrow();
        assertThat(tests.total()).as("one Kotlin test and one Groovy test").isEqualTo(2L);
        assertThat(tests.failures()).isEmpty();
    }
}
