// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.baseline.Baseline;
import cc.jumpkick.guard.baseline.BaselineFile;
import cc.jumpkick.guard.extract.FactsIndexing;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.rules.GuardRules;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.guard.rules.LoadResult;
import cc.jumpkick.guard.rules.Rule;
import cc.jumpkick.guard.schema.Lane;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.model.GuardsConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The guard lanes on jk's own tree, in process, each the median of {@value #RUNS} runs: the facts
 * index of the largest module built from scratch and rebuilt after a one-class change, that
 * module's lane with each rule's share, and the tree lane. Needs a built checkout; run with
 * {@code jk test -m server/guard --profile bench --class cc.jumpkick.guard.eval.GuardLaneBenchTest}.
 */
@Tag("bench")
class GuardLaneBenchTest {

    private static final int RUNS = 5;
    private static final int WARMUP = 10;
    private static final String MODULE = "server/engine";

    @Test
    void the_guard_lanes_on_jks_own_tree(@TempDir Path tmp) throws Exception {
        Path root = repoRoot();
        Path classes = root.resolve(MODULE).resolve("target/classes");
        Assumptions.assumeTrue(Files.isDirectory(classes), "build " + MODULE + " first");
        LoadResult load = GuardRules.load(root, new GuardsConfig(true, null, true));
        assertThat(load.hasErrors()).as("%s", load.problems()).isFalse();
        Baseline baseline = BaselineFile.read(GuardsPresence.baselineFile(root));

        // ---- facts index: from scratch, then after one class changes
        Path copy = tmp.resolve("classes");
        List<Path> classFiles = new ArrayList<>();
        PathUtil.forEachRegularFile(classes, (f, attrs) -> {
            Path to = copy.resolve(classes.relativize(f).toString());
            Files.createDirectories(to.getParent());
            Files.copy(f, to);
            if (to.toString().endsWith(".class")) classFiles.add(to);
        });
        List<Long> scratch = new ArrayList<>();
        for (int i = 0; i < RUNS; i++) {
            long t0 = System.nanoTime();
            FactsIndexing.ensure(copy, tmp.resolve("scratch" + i).resolve("main-guard.idx"));
            scratch.add(System.nanoTime() - t0);
        }
        Path idx = tmp.resolve("scratch0").resolve("main-guard.idx");
        List<Long> oneChanged = new ArrayList<>();
        FactsIndexing.Ensured index = FactsIndexing.ensure(copy, idx);
        for (int i = 0; i < WARMUP + RUNS; i++) {
            Files.setLastModifiedTime(classFiles.get(i * 7 % classFiles.size()), FileTime.fromMillis(1_000_000L + i));
            long t0 = System.nanoTime();
            index = FactsIndexing.ensure(copy, idx);
            if (i >= WARMUP) oneChanged.add(System.nanoTime() - t0);
        }
        FactsIndex facts = FactsIndexing.load(index);
        int classCount = facts.classes().size();

        // ---- the module lane, then each of its rules alone
        List<Rule> moduleRules = LaneRun.rulesFor(Lane.MODULE, load.rules(), MODULE);
        List<Long> module = new ArrayList<>();
        // The engine is long-lived: the lane is measured once its code is compiled, not on first use.
        for (int i = 0; i < WARMUP + RUNS; i++) {
            EvalContext ctx = moduleContext(root, facts).withRules(load.rules());
            long t0 = System.nanoTime();
            LaneRun.run(Lane.MODULE, moduleRules, ctx, baseline);
            if (i >= WARMUP) module.add(System.nanoTime() - t0);
        }
        List<String> perRule = new ArrayList<>();
        for (Rule rule : moduleRules) {
            List<Long> one = new ArrayList<>();
            for (int i = 0; i < RUNS; i++) {
                EvalContext ctx = moduleContext(root, facts).withRules(load.rules());
                long t0 = System.nanoTime();
                LaneRun.evaluate(List.of(rule), ctx);
                one.add(System.nanoTime() - t0);
            }
            perRule.add(String.format(Locale.ROOT, "  %-36s %-9s %4d ms", rule.id(), rule.kind(), median(one)));
        }

        // ---- the tree lane
        List<Rule> treeRules = LaneRun.rulesFor(Lane.TREE, load.rules(), "");
        List<Path> modules = WorkspaceModules.of(root);
        List<Long> tree = new ArrayList<>();
        for (int i = 0; i < RUNS; i++) {
            EvalContext ctx = new EvalContext(
                            Lane.TREE, root, "", null, modules, () -> FactsIndex.EMPTY, () -> null, List::of)
                    .withRules(load.rules());
            long t0 = System.nanoTime();
            LaneRun.run(Lane.TREE, treeRules, ctx, baseline);
            tree.add(System.nanoTime() - t0);
        }

        System.out.printf(
                Locale.ROOT,
                "facts index %s, %d classes: from scratch %d ms, one class changed %d ms (%d ms / 1,000)%n",
                MODULE,
                classCount,
                median(scratch),
                median(oneChanged),
                median(oneChanged) * 1000 / Math.max(1, classCount));
        System.out.printf(Locale.ROOT, "module lane %s: %d rules, %d ms%n", MODULE, moduleRules.size(), median(module));
        for (String line : perRule) System.out.println(line);
        System.out.printf(Locale.ROOT, "tree lane: %d rules, %d ms%n", treeRules.size(), median(tree));
    }

    private static EvalContext moduleContext(Path root, FactsIndex facts) {
        Path dir = root.resolve(MODULE);
        return new EvalContext(Lane.MODULE, root, MODULE, dir, List.of(dir), () -> facts, () -> null, List::of);
    }

    private static long median(List<Long> nanos) {
        List<Long> sorted = new ArrayList<>(nanos);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2) / 1_000_000;
    }

    private static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isRegularFile(p.resolve(GuardsPresence.RULES_FILE))) p = p.getParent();
        Assumptions.assumeTrue(p != null, "not inside a checkout with " + GuardsPresence.RULES_FILE);
        return p;
    }
}
