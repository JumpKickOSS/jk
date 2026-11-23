// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.orchestrator.TestRun;
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
 * A test class tagged only through a composed annotation another workspace module declares
 * ({@code @RollbackTest}, meta-annotated {@code @Tag("integration")}) runs under {@code -s
 * integration} and not in the default suite: the engine reads the annotation from the sibling on the
 * test classpath, as JUnit does.
 */
// Out of the unit tier: network resolve + real forked test JVMs.
@Tag("integration")
class SiblingComposedTagSuiteE2eTest {

    @TempDir
    static Path tmp;

    private static Path app;

    @BeforeAll
    static void workspace() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "com.example"
                name    = "ws"
                version = "1.0.0"
                java    = 25

                [workspace]
                modules = ["support", "app"]
                """);

        Path support = Files.createDirectories(ws.resolve("support"));
        Files.writeString(support.resolve("jk.toml"), """
                group   = "com.example"
                name    = "support"
                version = "1.0.0"
                java    = 25

                [dependencies]
                junit-jupiter-api = { group = "org.junit.jupiter", name = "junit-jupiter-api", version = "6.1.3" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Files.createDirectories(support.resolve("src/com/example/support"));
        Files.writeString(support.resolve("src/com/example/support/RollbackTest.java"), """
                package com.example.support;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;
                import org.junit.jupiter.api.Tag;
                @Target(ElementType.TYPE)
                @Retention(RetentionPolicy.RUNTIME)
                @Tag("integration")
                public @interface RollbackTest {}
                """);

        app = Files.createDirectories(ws.resolve("app"));
        Files.writeString(app.resolve("jk.toml"), """
                group   = "com.example"
                name    = "app"
                version = "1.0.0"
                java    = 25

                [test-dependencies]
                support                 = { workspace = true }
                junit-jupiter           = { group = "org.junit.jupiter", name = "junit-jupiter", version = "6.1.3" }
                junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "6.1.3" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Files.createDirectories(app.resolve("src/com/example"));
        Files.writeString(app.resolve("src/com/example/App.java"), """
                package com.example;
                public final class App {
                    public static int twice(int n) { return n * 2; }
                }
                """);
        Path tests = Files.createDirectories(app.resolve("test/src/com/example"));
        // Two tests tagged only through the sibling's annotation, one untagged.
        Files.writeString(tests.resolve("LedgerRollbackTest.java"), """
                package com.example;
                import com.example.support.RollbackTest;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                @RollbackTest
                class LedgerRollbackTest {
                    @Test void rollsBack() { assertEquals(4, App.twice(2)); }
                    @Test void rollsBackTwice() { assertEquals(8, App.twice(4)); }
                }
                """);
        Files.writeString(tests.resolve("FastTest.java"), """
                package com.example;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class FastTest {
                    @Test void twice() { assertEquals(4, App.twice(2)); }
                }
                """);

        Path cache = tmp.resolve("cache");
        BuildPlan lock = LockPlans.lockBuildPlan(ws, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).as("workspace lock").isTrue();
        Files.copy(ws.resolve("jk-lock.toml"), support.resolve("jk-lock.toml"));
        Files.copy(ws.resolve("jk-lock.toml"), app.resolve("jk-lock.toml"));
        // The sibling's jar is what app's tests compile and run against.
        BuildPlan.Builder b = BuildPlanner.coreBuilder(TestInputs.of(support, cache));
        PlannerTails.appendDeclaredTails(b, TestInputs.of(support, cache));
        assertThat(b.build().run().success()).as("support build").isTrue();
    }

    @Test
    void the_integration_suite_runs_the_class_tagged_through_the_siblings_annotation() throws Exception {
        assertThat(total(TestSelection.of(List.of("integration"), false, List.of(), List.of())))
                .as("LedgerRollbackTest's two tests")
                .isEqualTo(2L);
    }

    @Test
    void the_default_suite_leaves_it_out() throws Exception {
        assertThat(total(TestSelection.DEFAULT)).as("FastTest's test alone").isEqualTo(1L);
    }

    private static long total(TestSelection selection) throws Exception {
        Session session = Session.defaults().withTestSelection(selection);
        BuildPlanner.Inputs in = TestInputs.of(app, tmp.resolve("cache"), session)
                .withEstimatedTestCount(1)
                .withTestOnly(true);
        BuildPlan plan = BuildPlanner.coreBuilder(in).build();
        BuildPlanResult result = SessionContext.where(session, plan::run);
        assertThat(result.success()).as("%s", result.errors()).isTrue();
        TestSummary tests = plan.get(BuildPlanner.TEST_RUN).map(TestRun::result).orElseThrow();
        return tests.total();
    }
}
