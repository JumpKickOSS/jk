// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.config.AffectedSelection;
import cc.jumpkick.config.DirtyPaths;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Rank tests for the working tree from on-disk classes. Does not compile or run. */
public final class AffectedTestsCompute {

    private AffectedTestsCompute() {}

    public static AffectedTests fromDisk(Path root, TestSelection selection, Set<Path> onlyModules) throws Exception {
        JkBuild build = JkBuildParser.parse(root.resolve(ManifestPaths.MANIFEST));
        List<String> dirty = DirtyPaths.wip(root);
        if (dirty == null) {
            return AffectedTests.refused(
                    new AffectedTests.Refuse("no-git-no-classes", "git working tree could not be read"),
                    List.of(),
                    List.of());
        }
        AffectedSelection.Result cone = AffectedSelection.resolveWip(root, build);
        if (!cone.ok()) {
            return AffectedTests.refused(
                    new AffectedTests.Refuse("no-git-no-classes", cone.errorMessage()), List.of(), List.of());
        }
        TestSelection sel = selection == null ? TestSelection.DEFAULT : selection;
        AffectedTests acc = AffectedTests.empty(List.of(), List.of());
        Map<Path, JkBuild> modules =
                build.isWorkspaceRoot() ? WorkspaceLoader.loadModules(root, build) : Map.of(root, build);
        for (Path modDir : cone.moduleDirs()) {
            Path abs = modDir.toAbsolutePath().normalize();
            if (onlyModules != null && !onlyModules.isEmpty() && !onlyModules.contains(abs)) continue;
            JkBuild unit = modules.getOrDefault(modDir, build);
            BuildLayout layout =
                    build.isWorkspaceRoot() ? BuildLayout.of(root, modDir, unit) : BuildLayout.of(modDir, unit);
            Map<String, ClassAbi.Fingerprint> current = AbiIndex.scanClasses(layout.classesDir());
            Map<String, ClassAbi.Fingerprint> pre = AbiIndex.load(AbiIndex.path(layout.buildDir()));
            var tests = TestClassIndex.scan(layout.testClassesDir(), current.keySet());
            String coord = unit.project().group() + ":" + unit.project().name();
            String rel = root.relativize(modDir).toString();
            if (rel.isBlank()) rel = ".";
            AffectedTests slice = AffectedTestRanker.rank(new AffectedTestRanker.Inputs(
                    modDir,
                    coord,
                    root,
                    sel,
                    dirty,
                    pre,
                    current,
                    List.of(),
                    tests,
                    current.keySet(),
                    List.of(new AffectedTests.ModuleRow(rel, coord, "dirty"))));
            acc = acc.merge(slice);
        }
        return capGlobal(acc);
    }

    /** Display/MCP budget: highest scores across the workspace, at most {@link AffectedTests#CAP}. */
    static AffectedTests capGlobal(AffectedTests acc) {
        if (acc.refused()) return acc;
        List<AffectedTests.Row> rows = new ArrayList<>(acc.ranked());
        rows.sort(Comparator.comparingInt(AffectedTests.Row::score)
                .reversed()
                .thenComparing(AffectedTests.Row::className));
        int candidates = Math.max(acc.candidateCount(), rows.size());
        if (rows.size() > AffectedTests.CAP) {
            rows = List.copyOf(rows.subList(0, AffectedTests.CAP));
        }
        if (rows.isEmpty()) return AffectedTests.empty(acc.modules(), acc.changed());
        return AffectedTests.ranked(acc.modules(), acc.changed(), rows, candidates);
    }
}
