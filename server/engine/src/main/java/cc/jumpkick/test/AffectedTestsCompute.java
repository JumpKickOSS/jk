// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.config.AffectedSelection;
import cc.jumpkick.config.DirtyPaths;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.ModuleLayout;
import cc.jumpkick.layout.TestSuites;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Rank tests from sources and on-disk classes. Does not compile or run. */
public final class AffectedTestsCompute {

    private AffectedTestsCompute() {}

    public static AffectedTests fromDisk(Path root, TestSelection selection, Set<Path> onlyModules) throws Exception {
        return fromDisk(root, selection, onlyModules, null);
    }

    /**
     * {@code since} null/blank is the working-tree cone ({@code --affected}); otherwise
     * {@code since...HEAD} ({@code --affected-since}).
     */
    public static AffectedTests fromDisk(Path root, TestSelection selection, Set<Path> onlyModules, String since)
            throws Exception {
        JkBuild build = JkBuildParser.parse(root.resolve(ManifestPaths.MANIFEST));
        List<String> dirty;
        AffectedSelection.Result cone;
        if (since != null && !since.isBlank()) {
            dirty = AffectedSelection.gitDiffNameOnly(root, since);
            if (dirty == null) {
                return AffectedTests.refused(
                        new AffectedTests.Refuse("no-git-no-classes", "git ref `" + since + "` could not be resolved"),
                        List.of(),
                        List.of());
            }
            cone = AffectedSelection.resolve(root, build, since);
        } else {
            dirty = DirtyPaths.wip(root);
            if (dirty == null) {
                return AffectedTests.refused(
                        new AffectedTests.Refuse("no-git-no-classes", "git working tree could not be read"),
                        List.of(),
                        List.of());
            }
            cone = AffectedSelection.resolveWip(root, build);
        }
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
            if (!hasLocalDirty(root, abs, dirty)) continue;
            JkBuild unit = modules.getOrDefault(modDir, build);
            BuildLayout layout =
                    build.isWorkspaceRoot() ? BuildLayout.of(root, modDir, unit) : BuildLayout.of(modDir, unit);
            Map<String, ClassAbi.Fingerprint> current = AbiIndex.scanClasses(layout.classesDir());
            Map<String, ClassAbi.Fingerprint> pre = AbiIndex.load(AbiIndex.path(layout.buildDir()));
            var tests = testsFor(modDir, layout.testClassesDir(), current.keySet(), sel);
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

    static boolean hasLocalDirty(Path root, Path module, List<String> dirty) {
        if (dirty == null || dirty.isEmpty()) return false;
        Path mod = module.toAbsolutePath().normalize();
        Path base = root.toAbsolutePath().normalize();
        for (String raw : dirty) {
            if (raw == null || raw.isBlank()) continue;
            Path p = Path.of(raw);
            if (!p.isAbsolute()) p = base.resolve(p);
            if (p.normalize().startsWith(mod)) return true;
        }
        return false;
    }

    /**
     * Compiled test classes (imports + tags) plus every test source in the selection, so ranking
     * still works when {@code target/classes/test} has not been built yet.
     */
    static List<TestClassIndex.Entry> testsFor(
            Path moduleDir, Path testClassesDir, Set<String> production, TestSelection sel) throws IOException {
        LinkedHashMap<String, TestClassIndex.Entry> byName = new LinkedHashMap<>();
        for (TestClassIndex.Entry e : TestClassIndex.scan(testClassesDir, production)) {
            byName.put(e.className(), e);
        }
        for (TestClassIndex.Entry e : scanTestSources(moduleDir, sel)) {
            byName.putIfAbsent(e.className(), e);
        }
        return List.copyOf(byName.values());
    }

    static List<TestClassIndex.Entry> scanTestSources(Path moduleDir, TestSelection sel) throws IOException {
        boolean compact = ModuleLayout.isCompact(moduleDir);
        List<String> suites;
        if (sel != null && sel.allSuites()) {
            suites = TestSuites.discover(moduleDir, compact);
        } else if (sel == null || sel.suites().isEmpty()) {
            suites = List.of(TestSuites.DEFAULT);
        } else {
            suites = sel.suites();
        }
        LinkedHashMap<String, TestClassIndex.Entry> out = new LinkedHashMap<>();
        for (String suite : suites) {
            LinkedHashSet<Path> roots = new LinkedHashSet<>();
            roots.addAll(TestSuites.javaRoots(moduleDir, compact, suite));
            roots.addAll(TestSuites.kotlinRoots(moduleDir, compact, suite));
            roots.addAll(TestSuites.groovyRoots(moduleDir, compact, suite));
            for (Path root : roots) {
                PathUtil.forEachRegularFile(root, (p, attrs) -> {
                    String fn = p.getFileName().toString();
                    if (!(fn.endsWith(".java") || fn.endsWith(".kt") || fn.endsWith(".groovy"))) return;
                    if (fn.equals("package-info.java") || fn.equals("module-info.java")) return;
                    String rel = root.relativize(p).toString().replace('\\', '/');
                    int ext = rel.lastIndexOf('.');
                    if (ext < 0) return;
                    String fqc = rel.substring(0, ext).replace('/', '.');
                    if (fqc.isBlank() || fqc.contains("$")) return;
                    out.putIfAbsent(
                            fqc,
                            new TestClassIndex.Entry(fqc, Set.of(), Set.of(), TestClassIndex.nameMatchSimple(fqc)));
                });
            }
        }
        return List.copyOf(out.values());
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
