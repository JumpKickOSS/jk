// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.lock.LockManifestDigest;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.StepForecast;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.TaskForecaster;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A module forecast is reported in its plan's steps, each priced the way it declares. */
class PlanForecastTest {

    private static final TaskForecast.Task COMPILE =
            new TaskForecast.Task(TaskNames.COMPILE_MAIN, TaskForecast.Status.RUN, "source changed", null);
    private static final TaskForecast.Task JAVADOC =
            new TaskForecast.Task(TaskNames.PACKAGE_JAVADOC, TaskForecast.Status.RUN, "regenerate", null);
    private static final TaskForecast.Task RESTORE =
            new TaskForecast.Task(TaskNames.RESTORE_OUTPUTS, TaskForecast.Status.RUN, "outputs missing", null);

    @Test
    void verdicts_take_the_plans_names_and_steps_it_does_not_hold_are_dropped() {
        BuildPlan plan = BuildPlan.builder("m")
                .addTask(step(TaskNames.PARSE_BUILD, StepForecast.RIDES))
                .addTask(step(TaskNames.COMPILE_JAVA, StepForecast.PRICED, TaskNames.PARSE_BUILD))
                .build();

        TaskForecast.Module shaped = PlanForecast.shape(module(COMPILE, JAVADOC, RESTORE), plan);

        assertThat(shaped.steps())
                .extracting(TaskForecast.Task::name)
                .as("javac's verdict under the plan's step, the javadoc the plan never runs gone")
                .containsExactly(TaskNames.COMPILE_JAVA, TaskNames.RESTORE_OUTPUTS);
        assertThat(shaped.steps().getFirst().text()).isEqualTo("source changed");
    }

    @Test
    void an_always_run_step_runs_and_a_riding_step_is_never_reported() {
        BuildPlan plan = BuildPlan.builder("m")
                .addTask(step(TaskNames.PARSE_BUILD, StepForecast.RIDES))
                .addTask(step(TaskNames.WRITE_IMAGE, StepForecast.always("a side effect"), TaskNames.PARSE_BUILD))
                .build();

        TaskForecast.Module shaped = PlanForecast.shape(module(), plan);

        assertThat(shaped.steps())
                .containsExactly(
                        new TaskForecast.Task(TaskNames.WRITE_IMAGE, TaskForecast.Status.RUN, "a side effect", null));
        assertThat(shaped.dirty()).isTrue();
    }

    @Test
    void a_plan_with_nothing_priced_leaves_the_module_clean() {
        BuildPlan plan = BuildPlan.builder("m")
                .addTask(step(TaskNames.PARSE_BUILD, StepForecast.RIDES))
                .build();

        assertThat(PlanForecast.shape(module(JAVADOC), plan).dirty()).isFalse();
    }

    /**
     * On a real workspace and a cold cache, where nearly every arm of the walk reports, no verdict
     * names a step the module's plan declares as riding along, under PACKAGE or TEST. And {@code jk
     * test} on a module no sibling reads the jar of plans no package-jar, so its forecast has none.
     */
    @Test
    void the_walk_prices_no_step_its_plan_declares_riding(@TempDir Path tmp) throws Exception {
        Path root = tmp.toRealPath();
        Files.writeString(root.resolve("jk.toml"), """
                group = "ex"
                name = "ws"
                version = "1.0.0"
                java = 25

                [workspace]
                modules = ["lib", "app"]
                """);
        module(root, "lib", "Lib", "", "");
        module(root, "app", "App", """
                [application]
                main = "ex.App"
                assembly = true

                [dependencies]
                lib = { workspace = true }
                """, "public static void main(String[] a) {}");
        Files.createDirectories(root.resolve("lib/src/main/resources"));
        Files.writeString(root.resolve("lib/src/main/resources/lib.properties"), "k=v\n");
        Lockfile lf = new Lockfile(
                Lockfile.CURRENT_VERSION, "test", "pubgrub-v1", null, null, List.of(), List.of(), List.of());
        LockfileWriter.write(lf, root.resolve("jk-lock.toml"), LockManifestDigest.compute(root));
        BuildGraph.Result graph = BuildGraph.resolve(root, JkBuildParser.parse(root.resolve("jk.toml")));
        assertThat(graph.hasErrors()).as("%s", graph.errors()).isFalse();
        Path cache = root.resolve("cache");

        SessionContext.where(Session.defaults(), () -> {
            var ac = new ActionCache(JkStores.cacheCas(cache), cache.resolve("actions"));
            for (WorkspaceTarget target : List.of(WorkspaceTarget.PACKAGE, WorkspaceTarget.TEST)) {
                for (boolean skipTests : List.of(false, true)) {
                    var modules = TaskForecaster.of(graph, JkStores.storeCas(), ac, cache, skipTests, target);
                    assertThat(modules.stream().flatMap(m -> m.steps().stream()))
                            .as("the cold walk has verdicts to check")
                            .isNotEmpty();
                    assertThat(PlanForecast.misdeclared(modules, graph, cache, skipTests, target, null))
                            .as(target + (skipTests ? " skipping tests" : ""))
                            .isEmpty();
                }
            }
            var shaped = PlanForecast.shape(
                    TaskForecaster.of(graph, JkStores.storeCas(), ac, cache, false, WorkspaceTarget.TEST),
                    graph,
                    cache,
                    false,
                    WorkspaceTarget.TEST,
                    null);
            Path app = root.resolve("app");
            assertThat(shaped.stream()
                            .filter(m -> m.dir().equals(app))
                            .findFirst()
                            .orElseThrow()
                            .steps())
                    .extracting(TaskForecast.Task::name)
                    .doesNotContain(TaskNames.PACKAGE_JAR)
                    .contains(TaskNames.COMPILE_JAVA);
            Path lib = root.resolve("lib");
            assertThat(shaped.stream()
                            .filter(m -> m.dir().equals(lib))
                            .findFirst()
                            .orElseThrow()
                            .steps())
                    .extracting(TaskForecast.Task::name)
                    .as("app's suite reads lib's jar")
                    .contains(TaskNames.PACKAGE_JAR);
            return null;
        });
    }

    private static void module(Path root, String name, String type, String extra, String body) throws Exception {
        Path dir = Files.createDirectories(root.resolve(name));
        Path src = Files.createDirectories(dir.resolve("src/main/java/ex"));
        Files.writeString(src.resolve(type + ".java"), "package ex; public class " + type + " { " + body + " }\n");
        Path test = Files.createDirectories(dir.resolve("src/test/java/ex"));
        Files.writeString(test.resolve(type + "Test.java"), "package ex; class " + type + "Test {}\n");
        Files.writeString(dir.resolve("jk.toml"), """
                group = "ex"
                name = "%s"
                version = "1.0.0"
                java = 25
                %s
                """.formatted(name, extra));
    }

    private static Task step(String name, StepForecast how, String... requires) {
        return Task.builder(name).requires(requires).forecast(how).build();
    }

    private static TaskForecast.Module module(TaskForecast.Task... verdicts) {
        return new TaskForecast.Module(Path.of("m"), "g:m", List.of(verdicts), 1, 0, true, false);
    }
}
