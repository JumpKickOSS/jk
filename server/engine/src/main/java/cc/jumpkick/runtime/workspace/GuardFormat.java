// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.config.AffectedSelection;
import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.DirtyPaths;
import cc.jumpkick.config.FormatStyles;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.guard.baseline.Baselines;
import cc.jumpkick.layout.InputTrees;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.base.FormatPlans;
import cc.jumpkick.runtime.base.Perf;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * {@code [format] enforce} on a {@code jk guard} run: before the preflight fingerprints the tree,
 * format what changed (under CI, check it). The sources come from the job's own walk, and every
 * file rewritten is handed back to that walk, so the run reads one tree, formatted.
 */
final class GuardFormat {

    /** At most this many unformatted files are named on a failed check. */
    static final int NAMED = 20;

    private GuardFormat() {}

    /** What the step did: the files it rewrote, or the files a check found unformatted. */
    record Result(List<Path> rewritten, List<Path> unformatted, int errors) {}

    /** {@code null} when the run is not a guard run or the entry does not enforce formatting. */
    static @Nullable Result run(
            Path entryDir,
            Path cache,
            JkBuild entry,
            List<BuildGraph.BuildUnit> units,
            @Nullable Map<String, String> clientEnv) {
        return run(entryDir, cache, entry, units, ci(clientEnv));
    }

    /** The client's {@code CI} decides; a request that carries none falls back to the engine's. */
    static boolean ci(@Nullable Map<String, String> clientEnv) {
        return Baselines.ciMode(BuildEnv.over(clientEnv));
    }

    static @Nullable Result run(
            Path entryDir, Path cache, JkBuild entry, List<BuildGraph.BuildUnit> units, boolean check) {
        JkBuild.FormatConfig config = entry.format();
        if (!config.enforce() || !guardRequested()) return null;
        long t0 = Perf.start();
        Path root = entryDir.toAbsolutePath().normalize();
        List<Path> sources = new ArrayList<>();
        for (BuildGraph.BuildUnit unit : units) {
            sources.addAll(InputTrees.moduleSources(unit.dir(), ".java", ".kt", ".groovy", ".scala"));
        }
        Set<Path> only = config.ratchetFrom() == null ? null : changedSince(root, config.ratchetFrom());
        FormatStyles.Resolved styles = FormatStyles.resolve(null, null, null, null, null, null, config);
        List<Path> changed = new ArrayList<>();
        int[] errors = {0};
        BuildPlan plan = FormatPlans.formatSourcesPlan(
                root,
                cache,
                check,
                styles.java(),
                styles.kotlin(),
                styles.optimizeImports(),
                styles.importOrder(),
                styles.removeUnusedImports(),
                sources,
                only,
                (path, status, message, index, total) -> {
                    Path p = Path.of(path);
                    Path abs = (p.isAbsolute() ? p : root.resolve(p)).normalize();
                    if ("changed".equals(status)) {
                        synchronized (changed) {
                            changed.add(abs);
                        }
                    } else if ("error".equals(status)) {
                        synchronized (errors) {
                            errors[0]++;
                        }
                    }
                });
        BuildPlanResult result = plan.run();
        if (!result.success() && errors[0] == 0) errors[0] = 1;
        List<Path> sorted = changed.stream().sorted().toList();
        Perf.end("guard-format", t0, "sources", sources.size(), "changed", sorted.size(), "check", check);
        if (check) return new Result(List.of(), sorted, errors[0]);
        InputTrees.rewritten(sorted);
        return new Result(sorted, List.of(), errors[0]);
    }

    private static boolean guardRequested() {
        var session = SessionContext.current();
        return session != null && session.testSelection().runGuardScripts();
    }

    /**
     * Files changed since {@code ref}'s merge-base, committed or not, absolute. Empty when git cannot
     * answer: a ratchet that cannot be read formats nothing rather than everything.
     */
    static Set<Path> changedSince(Path root, String ref) {
        Set<Path> out = new HashSet<>();
        List<String> committed = AffectedSelection.gitDiffNameOnly(root, ref);
        List<String> working = DirtyPaths.workingTree(root);
        for (List<String> rels : Arrays.asList(committed, working)) {
            if (rels == null) continue;
            for (String rel : rels) out.add(root.resolve(rel).toAbsolutePath().normalize());
        }
        return out;
    }

    /** The guard step's lines for a failed check: one per unformatted file, the first with the remedy. */
    static List<String> checkErrors(Result result, Path entryDir) {
        List<String> out = new ArrayList<>();
        Path root = entryDir.toAbsolutePath().normalize();
        List<Path> named = result.unformatted()
                .subList(0, Math.min(NAMED, result.unformatted().size()));
        for (Path p : named) {
            String line = "not formatted: "
                    + (p.startsWith(root) ? root.relativize(p) : p).toString().replace('\\', '/');
            out.add(out.isEmpty() ? line + " ([format] enforce: jk guard outside CI, or jk format, formats it)" : line);
        }
        int more = result.unformatted().size() - named.size();
        if (more > 0) out.add("+" + more + " more not formatted");
        return out;
    }
}
