// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Session;
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
 * The FIRST build against a never-installed pinned JDK must already run on that JDK:
 * {@code JAVA_HOME} is published by ensure-jdk from its own install outcome, not
 * snapshotted in parse-build before the install exists (the old order compiled/tested the
 * first build on the running JVM and self-healed on the second — wrong once is wrong).
 *
 * <p>Uses {@code jdk = 17} on purpose (provisioning path). Product examples should prefer
 * {@code java = N} without a {@code jdk =} pin so the host LTS cross-compiles.
 *
 * <p>An empty {@code jdksDir} override reproduces the first-run shape deterministically.
 * Network test (Maven Central + the JDK feed; the CAS under build/ keeps repeats warm).
 */
@Tag("integration")
class FirstBuildJdkTest {

    @Test
    void first_build_with_uninstalled_pin_tests_on_the_pinned_jdk(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("app"));
        Path freshJdks = Files.createDirectories(tmp.resolve("jdks"));
        Path cache = Path.of(System.getProperty("user.dir"), "build", "android-spike-cache");

        Files.writeString(project.resolve("jk.toml"), """
                name    = "first17"
                group   = "com.example"
                version = "1.0.0"
                jdk     = 17
                layout  = "simple"

                [test-dependencies]
                junit-jupiter           = { group = "org.junit.jupiter", name = "junit-jupiter", version = "=6.1.1" }
                junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "=6.1.1" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("src/F.java"), """
                class F {}
                """);
        Files.createDirectories(project.resolve("test").resolve("src"));
        Files.writeString(project.resolve("test/src/FTest.java"), """
                import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
                import static org.junit.jupiter.api.Assertions.assertEquals;

                class FTest {
                    @Test
                    void first_build_already_runs_tests_on_the_pinned_jdk() {
                        assertEquals("17", System.getProperty("java.specification.version"));
                    }
                }
                """);

        var parsed = cc.jumpkick.config.JkBuildParser.parse(project.resolve("jk.toml"));
        // Isolated session: under `jk test` the ambient SessionContext is the monorepo (jdk 25).
        // Nested fixture plans must not inherit that pin or they skip the first-install path.
        Session nested = Session.defaults().withCacheDir(cache).withJdksDir(freshJdks);
        SessionContext.runWhere(nested, () -> {
            BuildPlan lock = LockPlans.lockBuildPlan(
                    project, parsed, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
            assertThat(lock.run().errors()).isEmpty();

            BuildPlanner.Inputs in = new BuildPlanner.Inputs(
                    project,
                    cache,
                    project.resolve("jk.toml"),
                    project.resolve("jk-lock.toml"),
                    project,
                    1,
                    1,
                    null,
                    freshJdks, // EMPTY: the pin is not installed here — the first-run shape
                    /* skipTests */ false,
                    false,
                    false,
                    false,
                    Set.of(),
                    nested);
            BuildPlan plan = BuildPlanner.coreBuilder(in).build();
            BuildPlanResult result = plan.run();
            StringBuilder dump = new StringBuilder();
            for (BuildPlanResult.Diagnostic d : result.errors()) {
                dump.append("DIAG [")
                        .append(d.step())
                        .append("]: ")
                        .append(d.message())
                        .append('\n');
            }
            plan.get(BuildPlanner.TEST_RESULT).ifPresent(ts -> {
                dump.append("NESTED-SUMMARY total=")
                        .append(ts.total())
                        .append(" fail=")
                        .append(ts.failed())
                        .append(" ok=")
                        .append(ts.succeeded())
                        .append('\n');
                for (var f : ts.failures()) {
                    dump.append("NESTED-FAIL name=")
                            .append(f.testName())
                            .append(" ex=")
                            .append(f.exceptionClass())
                            .append(" msg=")
                            .append(f.message())
                            .append('\n')
                            .append(f.stack() == null ? "" : f.stack())
                            .append('\n');
                }
            });
            try {
                Path reports = project.resolve("target/reports/test-results");
                if (Files.isDirectory(reports)) {
                    try (var stream = Files.walk(reports, 2)) {
                        for (Path p : stream.filter(x -> x.toString().endsWith(".xml"))
                                .toList()) {
                            dump.append("NESTED-XML ")
                                    .append(p.getFileName())
                                    .append(":\n")
                                    .append(Files.readString(p))
                                    .append('\n');
                        }
                    }
                }
            } catch (Exception e) {
                dump.append("NESTED-DUMP failed: ").append(e).append('\n');
            }
            assertThat(result.errors()).as(dump.toString()).isEmpty();
            assertThat(result.success())
                    .as("first build with an uninstalled pin runs its test on the pinned JDK\n" + dump)
                    .isTrue();
        });
    }
}
