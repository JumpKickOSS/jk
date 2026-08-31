// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.config.AffectedChanged;
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
import cc.jumpkick.task.ClassAbi;
import java.io.IOException;
import java.nio.file.Files;
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

        // Pass 1 — classify every dirty module's changed types into one carrier, so pass 2 can
        // rank *dependent* cone modules against them (abi-import across modules, JK-2606).
        record Unit(Path dir, JkBuild build, BuildLayout layout, boolean dirtyHere) {}
        List<Unit> units = new ArrayList<>();
        AffectedChanged carrier = new AffectedChanged();
        for (Path modDir : cone.moduleDirs()) {
            JkBuild unit = modules.getOrDefault(modDir, build);
            BuildLayout layout =
                    build.isWorkspaceRoot() ? BuildLayout.of(root, modDir, unit) : BuildLayout.of(modDir, unit);
            boolean dirtyHere = hasLocalDirty(root, modDir, dirty);
            units.add(new Unit(modDir, unit, layout, dirtyHere));
            if (dirtyHere) {
                String rel = root.relativize(modDir).toString();
                if (rel.isBlank()) rel = ".";
                String coord = unit.project().group() + ":" + unit.project().name();
                var row = List.of(new AffectedTests.ModuleRow(rel, coord, "dirty"));
                // No compile happens on this path: a dirty source newer than its compiled class
                // means the bytecode is a lie — refuse rather than rank from it (JK-2612).
                String stale = staleDirtyMain(root, modDir, dirty, layout.classesDir());
                if (stale != null) {
                    return AffectedTests.refused(
                            new AffectedTests.Refuse(
                                    "stale", stale + " is newer than its compiled class — build first"),
                            row,
                            List.of());
                }
                AffectedChangedPublish.classifyInto(
                        carrier,
                        root,
                        modDir,
                        dirty,
                        AbiIndex.load(AbiIndex.path(layout.buildDir())),
                        AbiIndex.scanClasses(layout.classesDir()));
            }
        }
        long dirtyModules = units.stream().filter(Unit::dirtyHere).count();
        if (dirtyModules > AffectedTests.MAX_CHANGED_MODULES) {
            return AffectedTests.refused(
                    new AffectedTests.Refuse(
                            "too-many-modules",
                            dirtyModules + " modules with source changes (max " + AffectedTests.MAX_CHANGED_MODULES
                                    + ")"),
                    List.of(),
                    List.of());
        }

        // Pass 2 — rank each cone module: a dirty module scores its own changed types first, a
        // dependent scores the carrier's foreign types. Dependents keep a "dependent" module row.
        // -m intersects here, not in pass 1: an unselected dirty module still classifies, so the
        // selected modules' importers rank against its changed types (JK-2613).
        for (Unit u : units) {
            if (onlyModules != null
                    && !onlyModules.isEmpty()
                    && !onlyModules.contains(u.dir().toAbsolutePath().normalize())) {
                continue;
            }
            Map<String, ClassAbi.Fingerprint> current =
                    AbiIndex.scanClasses(u.layout().classesDir());
            Map<String, ClassAbi.Kind> foreign = AffectedChangedPublish.foreignFor(carrier, current.keySet());
            Set<String> production = new LinkedHashSet<>(current.keySet());
            production.addAll(foreign.keySet());
            Map<String, ClassAbi.Fingerprint> pre =
                    u.dirtyHere() ? AbiIndex.load(AbiIndex.path(u.layout().buildDir())) : Map.of();
            var tests = testsFor(u.dir(), u.layout().testClassesDir(), production, sel);
            String coord =
                    u.build().project().group() + ":" + u.build().project().name();
            String rel = root.relativize(u.dir()).toString();
            if (rel.isBlank()) rel = ".";
            String why = u.dirtyHere() ? "dirty" : "dependent";
            AffectedTests slice = AffectedTestRanker.rank(new AffectedTestRanker.Inputs(
                    u.dir(),
                    coord,
                    root,
                    sel,
                    u.dirtyHere() ? dirty : List.of(),
                    pre,
                    current,
                    List.of(),
                    tests,
                    production,
                    List.of(new AffectedTests.ModuleRow(rel, coord, why)),
                    foreign));
            acc = acc.merge(slice);
        }
        return capGlobal(acc);
    }

    /**
     * The module-relative path of a dirty main source that is newer than its compiled class, or
     * {@code null} when every dirty class file is at least as fresh as its source (or absent —
     * ranking from sources is honest, stale bytecode is not).
     */
    static String staleDirtyMain(Path root, Path moduleDir, List<String> dirty, Path classesDir) {
        Path module = moduleDir.toAbsolutePath().normalize();
        Path base = root.toAbsolutePath().normalize();
        SourceFqcs fqcs = SourceFqcs.of(module, Set.of(TestSuites.DEFAULT));
        for (String raw : dirty) {
            if (raw == null || raw.isBlank()) continue;
            Path p = Path.of(raw);
            if (!p.isAbsolute()) p = base.resolve(p);
            p = p.normalize();
            if (!p.startsWith(module) || !Files.isRegularFile(p)) continue;
            String name = p.getFileName() == null ? "" : p.getFileName().toString();
            if (!AffectedTestRanker.isClassSource(name)) continue;
            String rel = module.relativize(p).toString().replace('\\', '/');
            SourceFqcs.Hit hit = fqcs.classify(rel, Set.of(TestSuites.DEFAULT));
            if (hit.kind() != SourceFqcs.Kind.MAIN || hit.fqc() == null) continue;
            Path classFile = classesDir.resolve(hit.fqc().replace('.', '/') + ".class");
            try {
                if (Files.isRegularFile(classFile)
                        && Files.getLastModifiedTime(classFile).compareTo(Files.getLastModifiedTime(p)) < 0) {
                    return rel;
                }
            } catch (IOException e) {
                // unreadable timestamps never fabricate a refuse
            }
        }
        return null;
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
