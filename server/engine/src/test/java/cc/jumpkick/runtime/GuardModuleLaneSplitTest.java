// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.guard.eval.EvalContext;
import cc.jumpkick.guard.eval.GuardSuites;
import cc.jumpkick.guard.explain.RuleSummaries;
import cc.jumpkick.guard.extract.FactsIndexing;
import cc.jumpkick.guard.rules.Rule;
import cc.jumpkick.guard.schema.Lane;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A module lane that runs a {@code src/guard} suite judges the suite and its declared rules under a
 * verdict each: an input only the suite reads moves, and the suite runs again while the declared
 * rules replay theirs; the module's own facts move, and both run.
 */
class GuardModuleLaneSplitTest {

    private static final String RULES = """
            [guards.no-sleep]
            kind = "forbid"
            signatures = ["java.lang.Thread#sleep(**)"]
            instead = "a scheduled executor"
            why = "no sleeping in the library"
            """;

    @Test
    void an_input_only_the_suite_reads_runs_the_suite_alone(@TempDir Path tmp) throws Exception {
        Path root = tree(tmp);
        ModuleLane lane = new ModuleLane(root, tmp);

        Run first = lane.run(List.of("facts:one"), "suite-reads:one");
        assertThat(first.suiteRuns()).isEqualTo(1);
        assertThat(first.ctx().cached).isFalse();
        assertThat(first.ctx().errors).isEmpty();

        Run unchanged = lane.run(List.of("facts:one"), "suite-reads:one");
        assertThat(unchanged.suiteRuns()).as("nothing moved").isZero();
        assertThat(unchanged.ctx().cached).isTrue();

        Run suiteMoved = lane.run(List.of("facts:one"), "suite-reads:two");
        assertThat(suiteMoved.suiteRuns()).as("what the suite reads moved").isEqualTo(1);
        assertThat(suiteMoved.ctx().cached).isFalse();
        assertThat(suiteMoved.ctx().label)
                .as("the declared rules replay their verdict")
                .startsWith("1 rule · clean (cached) · src/guard ")
                .doesNotEndWith("(cached)");

        Run moduleMoved = lane.run(List.of("facts:two"), "suite-reads:two");
        assertThat(moduleMoved.suiteRuns()).as("the suite reads the module too").isEqualTo(1);
        assertThat(moduleMoved.ctx().label).doesNotContain("(cached)");

        assertThat(evidence(root))
                .as("each verdict's evidence under its own name")
                .contains(
                        "guard_" + ActionKey.taskTag(root) + ".rules.jsonl",
                        "guard-suite_" + ActionKey.taskTag(root) + ".rules.jsonl");
    }

    /** One run of a lane: its context and how often the suite ran. */
    private record Run(Recording ctx, int suiteRuns) {}

    /** The module lane at {@code root}, run as the step runs it with the tokens a test hands it. */
    private static final class ModuleLane {
        private final Path root;
        private final PlannerGuards.LaneEnv env;
        private final List<Rule> suiteRules;

        ModuleLane(Path root, Path tmp) throws Exception {
            this.root = root;
            this.env = new PlannerGuards.LaneEnv(
                    PlannerGuards.detectAt(root),
                    new ActionCache(new Cas(tmp.resolve("cache/cas")), tmp.resolve("cache/actions")),
                    TestInputs.of(root, Files.createDirectories(tmp.resolve("cache")), SessionContext.current()));
            GuardSuites.Declared guard = new GuardSuites.Declared(
                    "reads-text",
                    "a verdict on the tree's text",
                    "",
                    "fx.ReadsText",
                    "readsText",
                    1,
                    false,
                    List.of(),
                    null,
                    true,
                    false);
            this.suiteRules = List.of(GuardSuites.rule(guard, root, ""));
        }

        Run run(List<String> moduleTokens, String suiteToken) throws Exception {
            BuildLayout layout = BuildLayout.of(root, JkBuildParser.parse(root.resolve("jk.toml")));
            FactsIndexing.Ensured main =
                    FactsIndexing.ensure(layout.classesDir(), FactsIndexing.indexPath(layout.buildDir(), "main"));
            EvalContext ectx = new EvalContext(
                    Lane.MODULE,
                    root,
                    "",
                    root,
                    List.of(root),
                    EvalContext.lazy(() -> FactsIndexing.load(main)),
                    () -> null,
                    List::of);
            List<String> withSuite = new ArrayList<>(moduleTokens);
            withSuite.add(suiteToken);
            AtomicInteger runs = new AtomicInteger();
            Path report = GuardSuites.report(BuildLayout.moduleTargetDir(root));
            PlannerGuards.Evaluation suite = evaluate -> {
                runs.incrementAndGet();
                Files.createDirectories(report.getParent());
                Files.writeString(
                        report, "{\"id\":\"reads-text\",\"outcome\":\"ok\",\"population\":1,\"violations\":[]}\n");
                return evaluate.get();
            };
            Recording ctx = new Recording();
            PlannerGuards.executeModuleLane(
                    ctx,
                    env,
                    ectx,
                    () -> moduleTokens,
                    () -> withSuite,
                    ActionKey.qualifiedTaskId(TaskNames.GUARD, root),
                    false,
                    suiteRules,
                    suite);
            return new Run(ctx, runs.get());
        }
    }

    /** The names of the evidence files the lanes left. */
    private static List<String> evidence(Path root) throws Exception {
        List<String> names = new ArrayList<>();
        try (var list = Files.list(RuleSummaries.dir(root))) {
            list.forEach(p -> names.add(p.getFileName().toString()));
        }
        return names;
    }

    /** A module with one main class and a rule over it. */
    private static Path tree(Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("tree"));
        Files.writeString(root.resolve("jk.toml"), "group = \"t\"\nname = \"m\"\nversion = \"0.0.1\"\njava = 25\n");
        Files.writeString(root.resolve("jk-guards.toml"), RULES);
        BuildLayout layout = BuildLayout.of(root, JkBuildParser.parse(root.resolve("jk.toml")));
        Path src = Files.createDirectories(tmp.resolve("main-src/app")).resolve("Main.java");
        Files.writeString(src, "package app; public class Main {}\n");
        Files.createDirectories(layout.classesDir());
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        int rc = javac.run(null, null, null, "-d", layout.classesDir().toString(), "-proc:none", src.toString());
        assertThat(rc).as("javac").isZero();
        return root;
    }

    /** The label, the cached mark and the errors a lane leaves on its step. */
    private static final class Recording implements TaskContext {
        @Nullable
        String label;

        boolean cached;
        final List<String> errors = new ArrayList<>();

        @Override
        public void progress(int delta) {}

        @Override
        public void updateTicks(int additional) {}

        @Override
        public void label(@Nullable String description) {
            label = description;
        }

        @Override
        public void output(@Nullable String line) {}

        @Override
        public void warn(String code, String message) {}

        @Override
        public void error(String code, String message) {
            errors.add(code + "=" + message);
        }

        @Override
        public void cached() {
            cached = true;
        }

        @Override
        public boolean cancelled() {
            return false;
        }

        @Override
        public <T> void put(BuildPlanKey<T> key, T value) {}

        @Override
        public <T> Optional<T> get(BuildPlanKey<T> key) {
            return Optional.empty();
        }

        @Override
        public <T> T require(BuildPlanKey<T> key) {
            throw new IllegalStateException("no " + key);
        }
    }
}
