// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.repo.HostClassifiers;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.runtime.workspace.ExecPlans;
import cc.jumpkick.testing.TestCaches;
import cc.jumpkick.wire.protocol.ExecPlan;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code [javafx]} contrib battery's example through the engine: the lock pins this host's
 * OpenJFX classifier, the suite runs with JavaFX as named modules, and {@code jk run}'s command
 * line loads them the same way.
 *
 * <p>Network test (Maven Central); the CAS persists under build/ so repeat runs are warm.
 */
@Tag("network")
class JavafxExampleTest {

    @Test
    void the_example_locks_the_host_classifier_tests_and_runs_on_the_module_path(@TempDir Path tmp) throws Exception {
        Path project = SchemaPresetExamplesTest.example(tmp, PluginJar.JAVAFX, "plugins/javafx");
        Path cache = TestCaches.dir("javafx-example-cache");
        SchemaPresetExamplesTest.lock(project, cache);

        String lock = Files.readString(project.resolve("jk-lock.toml"));
        assertThat(lock).contains("javafx-graphics");
        String host = HostClassifiers.properties().get("javafx.platform");
        if (host != null) assertThat(lock).contains(host);

        BuildPlanResult built = test(project, cache);
        assertThat(built.errors()).isEmpty();
        assertThat(built.success()).isTrue();
        assertThat(SchemaPresetExamplesTest.step(built, "run-tests").status()).isEqualTo(TaskStatus.SUCCESS);

        ExecPlan run = ExecPlans.execPlan(project, cache, "run", null, null);
        assertThat(run.error()).isNull();
        assertThat(run.argv()).contains("--module-path", "--add-modules", "javafx.controls");
        List<String> argv = new ArrayList<>(run.argv());
        argv.add("--check");
        Process p = new ProcessBuilder(argv)
                .directory(project.toFile())
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(out).contains("javafx.controls named=true");
    }

    private static BuildPlanResult test(Path project, Path cache) throws Exception {
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
