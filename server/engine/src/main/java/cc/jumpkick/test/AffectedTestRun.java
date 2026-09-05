// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static cc.jumpkick.runtime.BuildPlanner.MAIN_CLASSES;
import static cc.jumpkick.runtime.BuildPlanner.PROJECT;
import static cc.jumpkick.runtime.BuildPlanner.TEST_CLASSES;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.config.WorkspaceLocator;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.runtime.BuildPlanner;
import cc.jumpkick.task.ClassAbi;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Orchestrates ranking inside {@code run-tests}: scan, rank, refuse or filter the launcher. The
 * ranked slice rides the plan ({@code AFFECTED_TESTS}); the request's journal accumulator merges
 * slices and writes {@code jk-tests-affected.md} once at request-finish. Ranker stays pure.
 */
public final class AffectedTestRun {

    private AffectedTestRun() {}

    public record Outcome(AffectedTests report, List<String> classNames, String stampToken) {}

    /**
     * @return {@code null} when {@code session.affected()} is false (caller runs the full
     *     selection). Throws {@link RankingRefused} when ranking cannot be honest.
     */
    public static @Nullable Outcome apply(TaskContext ctx, BuildPlanner.Inputs in, TestSelection effectiveSel)
            throws Exception {
        if (!in.session().affected()) return null;
        Path moduleDir = in.dir();
        Path wsRoot = WorkspaceLocator.findRoot(moduleDir).orElse(moduleDir);
        JkBuild project = ctx.require(PROJECT);
        String coord = project.project().group() + ":" + project.project().name();
        Path classesDir = ctx.require(MAIN_CLASSES);
        Path testClasses = ctx.require(TEST_CLASSES);

        // One git exec per invocation: the carrier memoizes the WIP set for every module.
        List<String> dirty = in.session().affectedChanged().dirtyPaths(wsRoot);
        if (dirty == null && !Files.isDirectory(classesDir)) {
            throw refuse(ctx, "no-git-no-classes", "not a git repo and no compiled main classes");
        }
        if (dirty == null) dirty = List.of();

        Map<String, ClassAbi.Fingerprint> pre =
                ctx.get(BuildPlanner.PRE_COMPILE_ABI).orElse(Map.of());
        Map<String, ClassAbi.Fingerprint> current = AbiIndex.scanClasses(classesDir);
        // Dependency modules' changed types (classified at their compiles) rank this module's
        // importers too — a dependent in the cone is not "nothing affected".
        Map<String, ClassAbi.Kind> foreign = AffectedChangedPublish.foreignFor(in.session(), current.keySet());
        Set<String> production = new LinkedHashSet<>(current.keySet());
        production.addAll(foreign.keySet());
        List<TestClassIndex.Entry> tests = TestClassIndex.scan(testClasses, production);
        // run-tests only executes when test sources exist (NO_TEST_SOURCES short-circuits), so an
        // empty scan after compile-test means the classes are missing — refuse, don't guess.
        if (tests.isEmpty()) {
            throw refuse(ctx, "no-tests", "no compiled test classes");
        }
        if (!dirty.isEmpty()) {
            int dirtyModules = countDirtyModules(wsRoot, dirty);
            if (dirtyModules > AffectedTests.MAX_CHANGED_MODULES) {
                throw refuse(
                        ctx,
                        "too-many-modules",
                        dirtyModules + " modules with source changes (max " + AffectedTests.MAX_CHANGED_MODULES + ")");
            }
        }

        List<Path> compiled = ctx.get(BuildPlanner.COMPILED_MAIN_SOURCES).orElse(List.of());
        Path rel = wsRoot.relativize(moduleDir);
        String why = AffectedTestsCompute.hasLocalDirty(wsRoot, moduleDir, dirty) ? "dirty" : "dependent";
        AffectedTests.ModuleRow coneRow =
                new AffectedTests.ModuleRow(rel.toString().isEmpty() ? "." : rel.toString(), coord, why);

        AffectedTests report = AffectedTestRanker.rank(new AffectedTestRanker.Inputs(
                moduleDir,
                coord,
                wsRoot,
                effectiveSel,
                dirty,
                pre,
                current,
                compiled,
                tests,
                production,
                List.of(coneRow),
                foreign));
        ctx.put(BuildPlanner.AFFECTED_TESTS, report);
        AffectedTests.Refuse refuse = report.refuse();
        if (refuse != null) {
            String refusal = refuse.message() == null ? "" : refuse.message();
            ctx.error("affected-refuse", refusal);
            throw new RankingRefused(refusal);
        }
        if (report.ranked().isEmpty()) {
            ctx.label("nothing affected");
            return new Outcome(report, List.of(), "");
        }
        ctx.label("affected " + report.ranked().size() + " classes");
        return new Outcome(report, report.classNames(), report.identityToken());
    }

    /** Distinct workspace modules owning dirty paths; 1 when not a workspace or unreadable. */
    private static int countDirtyModules(Path wsRoot, List<String> dirty) {
        try {
            JkBuild root = JkBuildParser.parse(wsRoot.resolve(ManifestPaths.MANIFEST));
            if (!root.isWorkspaceRoot()) return 1;
            int n = 0;
            for (Path modDir : WorkspaceLoader.loadModules(wsRoot, root).keySet()) {
                if (AffectedTestsCompute.hasLocalDirty(wsRoot, modDir, dirty)) n++;
            }
            return n;
        } catch (Exception e) {
            return 1; // best-effort: an unreadable workspace never fabricates a refuse
        }
    }

    private static RankingRefused refuse(TaskContext ctx, String code, String message) {
        AffectedTests report = AffectedTests.refused(new AffectedTests.Refuse(code, message), List.of(), List.of());
        ctx.put(BuildPlanner.AFFECTED_TESTS, report);
        ctx.error("affected-refuse", message);
        return new RankingRefused(message);
    }

    public static final class RankingRefused extends RuntimeException {
        public RankingRefused(String message) {
            super(message);
        }
    }
}
