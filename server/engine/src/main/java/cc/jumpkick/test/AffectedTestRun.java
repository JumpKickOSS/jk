// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static cc.jumpkick.runtime.BuildPlanner.MODULE;
import static cc.jumpkick.runtime.BuildPlanner.PARSED_BUILD;
import static cc.jumpkick.runtime.BuildPlanner.mainClasses;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.config.UserConfigParseException;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.config.WorkspaceLocator;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.runtime.BuildPlanner;
import cc.jumpkick.task.ClassAbi;
import cc.jumpkick.task.MainAbi;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
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
    public static @Nullable Outcome apply(
            TaskContext ctx, BuildPlanner.Inputs in, TestSelection effectiveSel, SuiteFilter suites) throws Exception {
        if (!in.session().affected()) return null;
        Path moduleDir = in.dir();
        Path wsRoot = WorkspaceLocator.findRoot(moduleDir).orElse(moduleDir);
        JkBuild project = ctx.require(MODULE).project();
        String coord = project.project().group() + ":" + project.project().name();
        Path classesDir = mainClasses(ctx);
        Path testClasses = ctx.require(PARSED_BUILD).testClassesDir();

        // One git exec per invocation: the carrier memoizes the WIP set for every module.
        List<String> dirty = in.session().affectedChanged().dirtyPaths(wsRoot);
        if (dirty == null && !Files.isDirectory(classesDir)) {
            throw refuse(ctx, "no-git-no-classes", "not a git repo and no compiled main classes");
        }
        if (dirty == null) dirty = List.of();

        Optional<MainAbi> mainAbi = ctx.get(BuildPlanner.MAIN_ABI);
        Map<String, ClassAbi.Fingerprint> pre = mainAbi.map(MainAbi::before).orElse(Map.of());
        Map<String, ClassAbi.Fingerprint> current = AbiIndex.scanClasses(classesDir);
        // Dependency modules' changed types (classified at their compiles) rank this module's
        // importers too — a dependent in the cone is not "nothing affected".
        Map<String, ClassAbi.Kind> foreign = AffectedChangedPublish.foreignFor(in.session(), current.keySet());
        Set<String> production = new LinkedHashSet<>(current.keySet());
        production.addAll(foreign.keySet());
        List<TestClassIndex.Entry> tests = runnable(
                TestClassIndex.scan(testClasses, production), project.build().testExcludeClasses(), suites);
        // run-tests only executes when compile-test found test sources, so an empty scan after
        // compile-test means the classes are missing — refuse, don't guess.
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

        List<Path> compiled = mainAbi.map(MainAbi::compiled).orElse(List.of());
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

    /**
     * {@code tests} within the run's suites, by class and tag, less the classes {@code [test]
     * exclude-classes} keeps out of every run.
     */
    static List<TestClassIndex.Entry> runnable(
            List<TestClassIndex.Entry> tests, List<String> excluded, SuiteFilter suites) {
        String body = JUnitClassFilter.excludeBody(excluded);
        Pattern out = body == null ? null : Pattern.compile(body);
        Map<String, ClassTags> tags = new HashMap<>();
        for (TestClassIndex.Entry t : tests) tags.put(t.className(), new ClassTags(t.classTags(), t.tags()));
        Predicate<String> in = suites.withTags(tags::get).acceptor();
        return tests.stream()
                .filter(t -> in.test(t.className()))
                .filter(t -> out == null || !out.matcher(t.className()).matches())
                .toList();
    }

    /** Distinct workspace modules owning dirty paths; 1 when not a workspace or unreadable. */
    private static int countDirtyModules(Path wsRoot, List<String> dirty) {
        try {
            JkBuild root = JkBuildParser.parse(ManifestPaths.manifestIn(wsRoot));
            if (!root.isWorkspaceRoot()) return 1;
            int n = 0;
            for (Path modDir : WorkspaceLoader.loadModules(wsRoot, root).keySet()) {
                if (AffectedTestsCompute.hasLocalDirty(wsRoot, modDir, dirty)) n++;
            }
            return n;
        } catch (UserConfigParseException unparsed) {
            throw unparsed; // config.toml's own error, with its position — never this site's fallback
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
