// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.guard.extract.FactsIndexing;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A module whose {@code src/guard} suite reads the tree's text forecasts its lane on that text, as
 * the lane runs on it, under a verdict of the suite's own: an edit to root text alone re-runs the
 * suite without {@code --redo} and leaves the declared rules' verdict cached, whichever checkout
 * stored the lane's last verdicts, and a verdict another jk judged forecasts as {@code jk changed}.
 */
class GuardSuiteForecastTextTest {

    private static final String SUITE = """
            package fx;

            import cc.jumpkick.guard.api.Guard;
            import cc.jumpkick.guard.api.GuardSuite;
            import cc.jumpkick.guard.api.Text;
            import cc.jumpkick.guard.api.Violations;

            @GuardSuite
            final class ReadsText {
                @Guard(id = "reads-text", why = "a verdict on the tree's text moves with that text")
                void readsText(Text text, Violations v) {
                    v.population(text.files("**/*.txt").size());
                }
            }
            """;

    @Test
    void a_root_text_edit_runs_the_suite_and_keeps_the_declared_rules_cached(@TempDir Path tmp) throws Exception {
        Path root = tree(tmp);
        BuildLayout layout = BuildLayout.of(root, JkBuildParser.parse(root.resolve("jk.toml")));
        ActionCache cache = new ActionCache(new Cas(tmp.resolve("cache/cas")), tmp.resolve("cache/actions"));
        var load = PlannerGuards.rules(PlannerGuards.detectAt(root));
        GuardKeys.LaneProbe before = GuardKeys.probeModuleLane(root, root, layout, load, true);
        store(cache, root, before);
        assertThat(lane(root, layout, cache).status()).isEqualTo(TaskForecast.Status.CACHED);

        Files.writeString(root.resolve(".jk/notes.txt"), "two\n");

        GuardKeys.LaneProbe after = GuardKeys.probeModuleLane(root, root, layout, load, true);
        assertThat(after.key()).as("the declared rules read no text").isEqualTo(before.key());
        assertThat(after.suiteKey()).as("the suite reads the text").isNotEqualTo(before.suiteKey());
        TaskForecast.Task forecast = lane(root, layout, cache);
        assertThat(forecast.status()).isEqualTo(TaskForecast.Status.RUN);
        assertThat(forecast.text()).startsWith("guards · src/guard to run · ").endsWith(" rules cached");
    }

    @Test
    void a_lane_whose_suite_verdict_alone_is_stored_runs_its_declared_rules(@TempDir Path tmp) throws Exception {
        Path root = tree(tmp);
        BuildLayout layout = BuildLayout.of(root, JkBuildParser.parse(root.resolve("jk.toml")));
        ActionCache cache = new ActionCache(new Cas(tmp.resolve("cache/cas")), tmp.resolve("cache/actions"));
        var load = PlannerGuards.rules(PlannerGuards.detectAt(root));
        GuardKeys.LaneProbe probe = GuardKeys.probeModuleLane(root, root, layout, load, true);
        String taskId = ActionKey.qualifiedTaskId(TaskNames.GUARD, root);
        cache.storeVerdict(
                GuardVerdicts.suiteTaskId(taskId),
                Objects.requireNonNull(probe.suiteKey(), "the suite's key"),
                PlannerGuards.inputsOf(probe.suiteTokens(), probe.slice()));

        TaskForecast.Task forecast = lane(root, layout, cache);
        assertThat(forecast.status()).isEqualTo(TaskForecast.Status.RUN);
        assertThat(forecast.text()).endsWith("rules to evaluate");
    }

    /**
     * Every checkout of a project shares the lane's pointer: another checkout's verdicts, here over
     * a build that compiled no tests, written last, leave this one's reason as it was.
     */
    @Test
    void the_reason_holds_when_another_checkout_stored_the_last_verdict(@TempDir Path tmp) throws Exception {
        Path root = tree(tmp);
        BuildLayout layout = BuildLayout.of(root, JkBuildParser.parse(root.resolve("jk.toml")));
        ActionCache cache = new ActionCache(new Cas(tmp.resolve("cache/cas")), tmp.resolve("cache/actions"));
        var load = PlannerGuards.rules(PlannerGuards.detectAt(root));
        store(cache, root, GuardKeys.probeModuleLane(root, root, layout, load, true));
        Files.writeString(root.resolve(".jk/notes.txt"), "another checkout's text\n");
        store(cache, root, GuardKeys.probeModuleLane(root, root, layout, load, false));

        Files.writeString(root.resolve(".jk/notes.txt"), "two\n");

        TaskForecast.Task after = lane(root, layout, cache);
        assertThat(after.status()).isEqualTo(TaskForecast.Status.RUN);
        assertThat(after.text()).startsWith("guards · src/guard to run · ");
    }

    /** A verdict that waits on a tool is missed for the tool: the declared rules run. */
    @Test
    void a_lane_waiting_on_a_tool_runs_its_declared_rules(@TempDir Path tmp) throws Exception {
        Path root = tree(tmp);
        BuildLayout layout = BuildLayout.of(root, JkBuildParser.parse(root.resolve("jk.toml")));
        ActionCache cache = new ActionCache(new Cas(tmp.resolve("cache/cas")), tmp.resolve("cache/actions"));
        var load = PlannerGuards.rules(PlannerGuards.detectAt(root));
        GuardKeys.LaneProbe probe = GuardKeys.probeModuleLane(root, root, layout, load, true);
        store(cache, root, probe);
        String taskId = ActionKey.qualifiedTaskId(TaskNames.GUARD, root);
        cache.storeVerdict(
                taskId,
                Objects.requireNonNull(probe.key(), "the lane's key"),
                Map.of(GuardVerdicts.TOOLS, "a-tool-on-no-path"));

        TaskForecast.Task after = lane(root, layout, cache);
        assertThat(after.status()).isEqualTo(TaskForecast.Status.RUN);
        assertThat(after.text()).endsWith("rules to evaluate");
    }

    @Test
    void a_run_the_modules_own_inputs_also_moved_runs_its_declared_rules(@TempDir Path tmp) throws Exception {
        Path root = tree(tmp);
        BuildLayout layout = BuildLayout.of(root, JkBuildParser.parse(root.resolve("jk.toml")));
        ActionCache cache = new ActionCache(new Cas(tmp.resolve("cache/cas")), tmp.resolve("cache/actions"));
        var load = PlannerGuards.rules(PlannerGuards.detectAt(root));
        GuardKeys.LaneProbe before = GuardKeys.probeModuleLane(root, root, layout, load, true);
        // The last verdicts were judged over other facts of the module than the ones it has now.
        List<String> otherFacts = new ArrayList<>(before.tokens());
        otherFacts.replaceAll(t -> t.startsWith("facts:") ? "facts:another-build" : t);
        String taskId = ActionKey.qualifiedTaskId(TaskNames.GUARD, root);
        cache.storeVerdict(
                taskId,
                GuardKeys.laneKey(taskId, otherFacts, before.slice()),
                PlannerGuards.inputsOf(otherFacts, before.slice()));

        Files.writeString(root.resolve(".jk/notes.txt"), "two\n");

        TaskForecast.Task after = lane(root, layout, cache);
        assertThat(after.status()).isEqualTo(TaskForecast.Status.RUN);
        assertThat(after.text()).endsWith("rules to evaluate");
    }

    /** Every lane key names the jk that judged it: a lane whose last verdict was another jk's says so. */
    @Test
    void a_lane_another_jk_judged_forecasts_jk_changed(@TempDir Path tmp) throws Exception {
        Path root = tree(tmp);
        BuildLayout layout = BuildLayout.of(root, JkBuildParser.parse(root.resolve("jk.toml")));
        ActionCache cache = new ActionCache(new Cas(tmp.resolve("cache/cas")), tmp.resolve("cache/actions"));
        var load = PlannerGuards.rules(PlannerGuards.detectAt(root));
        try {
            BuildIdentity.overrideCacheKeyVersionForTests("jk-before-the-reinstall");
            store(cache, root, GuardKeys.probeModuleLane(root, root, layout, load, true));
            assertThat(lane(root, layout, cache).status()).isEqualTo(TaskForecast.Status.CACHED);

            BuildIdentity.overrideCacheKeyVersionForTests("jk-after-the-reinstall");
            TaskForecast.Task after = lane(root, layout, cache);

            assertThat(after.status()).isEqualTo(TaskForecast.Status.RUN);
            assertThat(after.text()).isEqualTo("guards · jk changed");
        } finally {
            BuildIdentity.overrideCacheKeyVersionForTests(null);
        }
    }

    /** A module with main classes and a text-reading guard suite, both compiled and indexed. */
    private static Path tree(Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("tree"));
        Files.writeString(root.resolve("jk.toml"), "group = \"t\"\nname = \"m\"\nversion = \"0.0.1\"\njava = 25\n");
        Files.createDirectories(root.resolve("src/main/java/app"));
        Files.writeString(root.resolve("src/main/java/app/Main.java"), "package app; public class Main {}\n");
        Files.createDirectories(root.resolve("src/guard/java/fx"));
        Files.writeString(root.resolve("src/guard/java/fx/ReadsText.java"), SUITE);
        Path notes = Files.createDirectories(root.resolve(".jk")).resolve("notes.txt");
        Files.writeString(notes, "one\n");
        JkBuild build = JkBuildParser.parse(root.resolve("jk.toml"));
        BuildLayout layout = BuildLayout.of(root, build);

        compile(tmp.resolve("main-src"), layout.classesDir(), "app", "Main", "package app; public class Main {}");
        FactsIndexing.ensure(layout.classesDir(), FactsIndexing.indexPath(layout.buildDir(), "main"));
        compile(tmp.resolve("suite-src"), layout.guardClassesDir(), "fx", "ReadsText", SUITE);
        FactsIndexing.ensure(layout.guardClassesDir(), FactsIndexing.indexPath(layout.buildDir(), "guard"));
        return root;
    }

    /** The verdicts of the lane at {@code root} over {@code probe}, its declared rules' and its suite's, stored as the lane stores them. */
    private static void store(ActionCache cache, Path root, GuardKeys.LaneProbe probe) throws Exception {
        String taskId = ActionKey.qualifiedTaskId(TaskNames.GUARD, root);
        cache.storeVerdict(
                taskId,
                Objects.requireNonNull(probe.key(), "the lane's key"),
                PlannerGuards.inputsOf(probe.tokens(), probe.slice()));
        cache.storeVerdict(
                GuardVerdicts.suiteTaskId(taskId),
                Objects.requireNonNull(probe.suiteKey(), "the suite's key"),
                PlannerGuards.inputsOf(probe.suiteTokens(), probe.slice()));
    }

    private static TaskForecast.Task lane(Path root, BuildLayout layout, ActionCache cache) {
        return GuardKeys.forecastModuleLane(root, layout, cache, false, true).orElseThrow();
    }

    private static void compile(Path src, Path out, String pkg, String simpleName, String source) throws Exception {
        Path file = Files.createDirectories(src.resolve(pkg)).resolve(simpleName + ".java");
        Files.writeString(file, source);
        Files.createDirectories(out);
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        int rc = javac.run(
                null,
                null,
                null,
                "-d",
                out.toString(),
                "-cp",
                System.getProperty("java.class.path"),
                "-proc:none",
                file.toString());
        assertThat(rc).as("javac").isZero();
    }
}
