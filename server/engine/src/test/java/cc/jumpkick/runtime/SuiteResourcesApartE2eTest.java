// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TestSummary;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two directory suites ship a JUnit extension service file at the same path, each naming its own
 * extension. A run of every suite runs each suite with its own registration: neither file replaces
 * the other on the classpath its tests run with, and neither suite's extension reaches the other's
 * tests.
 */
// Out of the unit tier: network resolve + real forked test JVMs.
@Tag("integration")
class SuiteResourcesApartE2eTest {

    private static final String SERVICE = "META-INF/services/org.junit.jupiter.api.extension.Extension";

    @TempDir
    static Path tmp;

    private static Path project;

    @BeforeAll
    static void project() throws Exception {
        project = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(project.resolve("jk.toml"), """
                group   = "com.example"
                name    = "app"
                version = "1.0.0"
                java    = 25

                [test-dependencies]
                junit-jupiter           = { group = "org.junit.jupiter", name = "junit-jupiter", version = "6.1.3" }
                junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "6.1.3" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Files.createDirectories(project.resolve("src/com/example"));
        Files.writeString(project.resolve("src/com/example/App.java"), """
                package com.example;
                public final class App {
                    public static int twice(int n) { return n * 2; }
                }
                """);
        suite("test", "UnitMark", "UnitTest", "unit", "it");
        suite("integration", "ItMark", "WiringTest", "it", "unit");
        JkBuild build = JkBuildParser.parse(project.resolve("jk.toml"));
        BuildPlan lock = LockPlans.lockBuildPlan(
                project, build, tmp.resolve("cache"), null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).as("lock").isTrue();
    }

    @Test
    void every_suite_runs_with_its_own_extension_registration() throws Exception {
        Run run = run(TestSelection.of(List.of(), true, List.of(), List.of()));
        assertThat(run.tests().failures())
                .as("each suite saw its own extension and not the other's")
                .isEmpty();
        assertThat(run.result().success()).isTrue();
        assertThat(run.tests().total()).as("one test in each suite").isEqualTo(2L);
    }

    @Test
    void a_class_named_in_one_suite_runs_only_there() throws Exception {
        Run run = run(TestSelection.of(List.of(), true, List.of(), List.of()).withClasses(List.of("UnitTest")));
        assertThat(run.result().success()).isTrue();
        assertThat(run.tests().total()).isEqualTo(1L);
    }

    /**
     * A suite whose service file registers {@code extension}, which marks {@code mine} before its
     * test, and whose test fails when {@code theirs} was marked too.
     */
    private static void suite(String suite, String extension, String test, String mine, String theirs)
            throws Exception {
        Path src = Files.createDirectories(project.resolve(suite).resolve("src/com/example"));
        Path resources = project.resolve(suite).resolve("resources");
        Files.createDirectories(resources.resolve(SERVICE).getParent());
        Files.writeString(resources.resolve(SERVICE), "com.example." + extension + "\n");
        Files.writeString(
                resources.resolve("junit-platform.properties"),
                "junit.jupiter.extensions.autodetection.enabled=true\n# the " + suite + " suite\n");
        Files.writeString(src.resolve(extension + ".java"), """
                package com.example;
                import org.junit.jupiter.api.extension.BeforeAllCallback;
                import org.junit.jupiter.api.extension.ExtensionContext;
                public class %s implements BeforeAllCallback {
                    @Override public void beforeAll(ExtensionContext context) {
                        System.setProperty("mark.%s", "true");
                    }
                }
                """.formatted(extension, mine));
        Files.writeString(src.resolve(test + ".java"), """
                package com.example;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                import static org.junit.jupiter.api.Assertions.assertNull;
                class %s {
                    @Test void sees_only_its_own_extension() {
                        assertEquals("true", System.getProperty("mark.%s"), "own extension registered");
                        assertNull(System.getProperty("mark.%s"), "the other suite's extension registered");
                        assertEquals(4, App.twice(2));
                    }
                }
                """.formatted(test, mine, theirs));
    }

    private record Run(BuildPlan plan, BuildPlanResult result) {
        TestSummary tests() {
            return plan.get(BuildPlanner.TEST_RESULT).orElseThrow();
        }
    }

    private static Run run(TestSelection selection) throws Exception {
        Session session = Session.defaults().withTestSelection(selection);
        BuildPlanner.Inputs in = TestInputs.of(project, tmp.resolve("cache"), session)
                .withEstimatedTestCount(1)
                .withTestOnly(true);
        BuildPlan plan = BuildPlanner.coreBuilder(in).build();
        return new Run(plan, SessionContext.where(session, plan::run));
    }
}
