// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.guard.api.runtime.GuardConfig;
import cc.jumpkick.guard.eval.GuardSuites;
import cc.jumpkick.guard.extract.FactsIndexing;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Once a suite has run, its lane is keyed on what its guards read, as its read ledger says: an edit
 * to a file no guard read keeps the suite's verdict, an edit to one a guard read runs the suite
 * again, and so does an edit to the code the suite runs on. Without a ledger that can stand for
 * every guard, the key falls back to the whole tree's text.
 */
class GuardSuiteLedgerKeyTest {

    private static final String SUITE = """
            package fx;

            import cc.jumpkick.guard.api.Guard;
            import cc.jumpkick.guard.api.GuardSuite;
            import cc.jumpkick.guard.api.Text;
            import cc.jumpkick.guard.api.Violations;

            @GuardSuite
            final class ReadsText {
                @Guard(id = "reads-text", why = "a verdict on one file moves with that file")
                void readsText(Text text, Violations v) {
                    v.population(text.lines(".jk/notes.txt").size());
                }
            }
            """;

    @Test
    void an_edit_no_guard_read_keeps_the_suite_s_verdict(@TempDir Path tmp) throws Exception {
        Path root = tree(tmp);
        ledger(root, "ok");
        String before = suiteKey(root);

        Files.writeString(root.resolve(".jk/other.txt"), "no guard reads this\n");

        assertThat(suiteKey(root)).isEqualTo(before);
    }

    @Test
    void a_cached_suite_forecasts_cached_after_an_edit_no_guard_read(@TempDir Path tmp) throws Exception {
        Path root = tree(tmp);
        ledger(root, "ok");
        BuildLayout layout = BuildLayout.of(root, JkBuildParser.parse(root.resolve("jk.toml")));
        ActionCache cache = new ActionCache(new Cas(tmp.resolve("cache/cas")), tmp.resolve("cache/actions"));
        store(cache, root, probe(root));

        Files.writeString(root.resolve(".jk/other.txt"), "no guard reads this\n");

        TaskForecast.Task forecast =
                GuardKeys.forecastModuleLane(root, layout, cache, false, true).orElseThrow();
        assertThat(forecast.status()).isEqualTo(TaskForecast.Status.CACHED);
    }

    @Test
    void an_edit_to_a_file_a_guard_read_runs_the_suite(@TempDir Path tmp) throws Exception {
        Path root = tree(tmp);
        ledger(root, "ok");
        String before = suiteKey(root);

        Files.writeString(root.resolve(".jk/notes.txt"), "two\n");

        assertThat(suiteKey(root)).isNotEqualTo(before);
    }

    @Test
    void an_edit_to_the_code_the_suite_runs_on_runs_the_suite(@TempDir Path tmp) throws Exception {
        Path root = tree(tmp);
        ledger(root, "ok");
        String before = suiteKey(root);

        Files.writeString(
                root.resolve("src/main/java/app/Main.java"), "package app; public class Main { int body = 2; }\n");

        assertThat(suiteKey(root)).isNotEqualTo(before);
    }

    @Test
    void a_guard_the_ledger_cannot_replay_keys_the_suite_on_the_whole_tree(@TempDir Path tmp) throws Exception {
        Path root = tree(tmp);
        ledger(root, "threw");
        String before = suiteKey(root);

        Files.writeString(root.resolve(".jk/other.txt"), "no guard reads this\n");

        assertThat(suiteKey(root)).isNotEqualTo(before);
    }

    @Test
    void a_ledger_the_suite_s_guards_are_not_all_in_keys_the_suite_on_the_whole_tree(@TempDir Path tmp)
            throws Exception {
        Path root = tree(tmp);
        String before = suiteKey(root);

        Files.writeString(root.resolve(".jk/other.txt"), "no guard reads this\n");

        assertThat(suiteKey(root)).as("no run yet").isNotEqualTo(before);
    }

    /** The ledger and run configuration a run of the suite leaves: its one guard read {@code .jk/notes.txt}. */
    private static void ledger(Path root, String outcome) throws Exception {
        Path report = GuardSuites.report(BuildLayout.moduleTargetDir(root));
        Files.createDirectories(report.getParent());
        GuardConfig config = new GuardConfig(
                report.resolveSibling(report.getFileName() + ".part"),
                root,
                "",
                List.of(),
                List.of(),
                null,
                List.of(root),
                List.of(),
                List.of(),
                List.of(),
                null,
                false,
                null);
        Files.writeString(report.resolveSibling(GuardKeys.RUN_PROPERTIES), config.toProperties());
        String digest = Hashing.sha256Hex(Files.readString(root.resolve(".jk/notes.txt")))
                .substring(0, 16);
        Files.writeString(
                report.resolveSibling("ledger.jsonl"),
                "{\"path\":\".jk/notes.txt\",\"digest\":\"" + digest + "\"}\n"
                        + "{\"id\":\"reads-text\",\"stamp\":\"s\",\"outcome\":\"" + outcome + "\",\"replayable\":true,"
                        + "\"globs\":{},\"files\":[0,1],\"memo\":{},\"line\":\"{}\"}\n");
    }

    private static GuardKeys.LaneProbe probe(Path root) throws Exception {
        BuildLayout layout = BuildLayout.of(root, JkBuildParser.parse(root.resolve("jk.toml")));
        var load = PlannerGuards.rules(PlannerGuards.detectAt(root));
        return GuardKeys.probeModuleLane(root, root, layout, load, true);
    }

    private static String suiteKey(Path root) throws Exception {
        return Objects.requireNonNull(probe(root).suiteKey(), "the suite's key");
    }

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

    /** A module with main classes and a guard suite that reads one file, both compiled and indexed. */
    private static Path tree(Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("tree"));
        Files.writeString(root.resolve("jk.toml"), "group = \"t\"\nname = \"m\"\nversion = \"0.0.1\"\njava = 25\n");
        Files.createDirectories(root.resolve("src/main/java/app"));
        Files.writeString(root.resolve("src/main/java/app/Main.java"), "package app; public class Main {}\n");
        Files.createDirectories(root.resolve("src/guard/java/fx"));
        Files.writeString(root.resolve("src/guard/java/fx/ReadsText.java"), SUITE);
        Files.createDirectories(root.resolve(".jk"));
        Files.writeString(root.resolve(".jk/notes.txt"), "one\n");
        Files.writeString(root.resolve(".jk/other.txt"), "one\n");
        JkBuild build = JkBuildParser.parse(root.resolve("jk.toml"));
        BuildLayout layout = BuildLayout.of(root, build);

        compile(tmp.resolve("main-src"), layout.classesDir(), "app", "Main", "package app; public class Main {}");
        FactsIndexing.ensure(layout.classesDir(), FactsIndexing.indexPath(layout.buildDir(), "main"));
        compile(tmp.resolve("suite-src"), layout.guardClassesDir(), "fx", "ReadsText", SUITE);
        FactsIndexing.ensure(layout.guardClassesDir(), FactsIndexing.indexPath(layout.buildDir(), "guard"));
        return root;
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
