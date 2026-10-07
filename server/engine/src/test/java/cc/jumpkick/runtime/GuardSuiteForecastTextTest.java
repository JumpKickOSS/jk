// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.guard.extract.FactsIndexing;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.wire.runtime.TaskForecast;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A module whose {@code src/guard} suite reads the tree's text forecasts its lane on that text, as
 * the lane runs on it: an edit to root text alone re-runs the suite without {@code --redo}.
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
    void a_root_text_edit_turns_a_cached_text_suite_lane_into_a_run(@TempDir Path tmp) throws Exception {
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

        ActionCache cache = new ActionCache(new Cas(tmp.resolve("cache/cas")), tmp.resolve("cache/actions"));
        var load = PlannerGuards.rules(PlannerGuards.detectAt(root));
        String key = Objects.requireNonNull(
                GuardKeys.probeModuleLane(root, root, layout, load, true).key(), "the lane's key");
        cache.storeVerdict(ActionKey.qualifiedTaskId(TaskNames.GUARD, root), key, Map.of());
        assertThat(lane(root, layout, cache).status()).isEqualTo(TaskForecast.Status.CACHED);

        Files.writeString(notes, "two\n");

        assertThat(GuardKeys.probeModuleLane(root, root, layout, load, true).key())
                .isNotEqualTo(key);
        TaskForecast.Task after = lane(root, layout, cache);
        assertThat(after.status()).isEqualTo(TaskForecast.Status.RUN);
        assertThat(after.text()).contains("rules to evaluate");
    }

    private static TaskForecast.Task lane(Path root, BuildLayout layout, ActionCache cache) {
        return GuardKeys.forecastModuleLane(root, layout, cache, false, false).orElseThrow();
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
