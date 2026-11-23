// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.config.TestSelection;
import cc.jumpkick.layout.TestSuites;
import cc.jumpkick.lock.ManifestPaths;
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
import org.jspecify.annotations.Nullable;

/**
 * Pure ranking: each selected test class by what jk recorded of its last run ({@code recorded}), and
 * a class with no record by the changed types its bytecode names, into {@link AffectedTests}.
 */
public final class AffectedTestRanker {

    private AffectedTestRanker() {}

    public record Inputs(
            Path moduleDir,
            String moduleCoord,
            Path workspaceRoot,
            TestSelection selection,
            List<String> dirtyPaths,
            Map<String, ClassAbi.Fingerprint> preCompileAbi,
            Map<String, ClassAbi.Fingerprint> currentAbi,
            List<Path> compiledMainSources,
            List<TestClassIndex.Entry> tests,
            Set<String> productionFqcs,
            List<AffectedTests.ModuleRow> cone,
            /** Changed types other (dependency) modules classified — FQC → kind. Never refused on. */
            Map<String, ClassAbi.Kind> foreignChanged,
            /**
             * By test class with a record of its last run, how its recorded cone and reads meet the
             * change ({@code TestClassRecording.overlaps}): empty when they do not.
             */
            Map<String, String> recorded) {

        public Inputs(
                Path moduleDir,
                String moduleCoord,
                Path workspaceRoot,
                TestSelection selection,
                List<String> dirtyPaths,
                Map<String, ClassAbi.Fingerprint> preCompileAbi,
                Map<String, ClassAbi.Fingerprint> currentAbi,
                List<Path> compiledMainSources,
                List<TestClassIndex.Entry> tests,
                Set<String> productionFqcs,
                List<AffectedTests.ModuleRow> cone,
                Map<String, ClassAbi.Kind> foreignChanged) {
            this(
                    moduleDir,
                    moduleCoord,
                    workspaceRoot,
                    selection,
                    dirtyPaths,
                    preCompileAbi,
                    currentAbi,
                    compiledMainSources,
                    tests,
                    productionFqcs,
                    cone,
                    foreignChanged,
                    Map.of());
        }

        public Inputs(
                Path moduleDir,
                String moduleCoord,
                Path workspaceRoot,
                TestSelection selection,
                List<String> dirtyPaths,
                Map<String, ClassAbi.Fingerprint> preCompileAbi,
                Map<String, ClassAbi.Fingerprint> currentAbi,
                List<Path> compiledMainSources,
                List<TestClassIndex.Entry> tests,
                Set<String> productionFqcs,
                List<AffectedTests.ModuleRow> cone) {
            this(
                    moduleDir,
                    moduleCoord,
                    workspaceRoot,
                    selection,
                    dirtyPaths,
                    preCompileAbi,
                    currentAbi,
                    compiledMainSources,
                    tests,
                    productionFqcs,
                    cone,
                    Map.of(),
                    Map.of());
        }
    }

    public static AffectedTests rank(Inputs in) {
        List<AffectedTests.ModuleRow> cone = in.cone() == null ? List.of() : in.cone();
        if (in.dirtyPaths() != null && in.dirtyPaths().size() > AffectedTests.MAX_DIRTY_PATHS) {
            return AffectedTests.refused(
                    new AffectedTests.Refuse(
                            "too-many-paths",
                            in.dirtyPaths().size() + " dirty paths (max " + AffectedTests.MAX_DIRTY_PATHS + ")"),
                    cone,
                    List.of());
        }
        Path module =
                in.moduleDir() == null ? null : in.moduleDir().toAbsolutePath().normalize();
        Path root = in.workspaceRoot() == null
                ? module
                : in.workspaceRoot().toAbsolutePath().normalize();
        Set<String> suites = new LinkedHashSet<>(
                in.selection() == null
                        ? List.of(TestSuites.DEFAULT)
                        : in.selection().suites());
        if (suites.isEmpty()) suites.add(TestSuites.DEFAULT);
        // Layout-aware FQC derivation (compact and traditional); string heuristics as fallback.
        SourceFqcs fqcs = SourceFqcs.of(module, suites);
        Dirty dirty = classifyDirty(in, module, root, fqcs);
        AffectedTests refused = refusal(in, dirty, cone);
        if (refused != null) return refused;

        List<Scored> scored = score(in, dirty);
        List<AffectedTests.Row> rows = new ArrayList<>();
        String coord = in.moduleCoord() == null ? "" : in.moduleCoord();
        for (Scored s : scored) rows.add(new AffectedTests.Row(coord, s.className(), s.reason(), s.score()));
        if (rows.isEmpty()) return AffectedTests.empty(cone, toChanged(in, dirty.changed()));
        return AffectedTests.ranked(cone, toChanged(in, dirty.changed()), rows, rows.size());
    }

    /**
     * What the dirty set says: a manifest touched, only non-class files, the test classes edited
     * (the selection's or not: {@link #score} ranks only the selected), and the changed production
     * types by kind.
     */
    private record Dirty(
            boolean manifest,
            boolean onlyNonClass,
            LinkedHashSet<String> dirtyTestClasses,
            LinkedHashMap<String, ClassAbi.Kind> changed) {}

    private static Dirty classifyDirty(Inputs in, @Nullable Path module, @Nullable Path root, SourceFqcs fqcs) {
        boolean manifest = false;
        boolean onlyNonClass = true;
        LinkedHashSet<String> dirtyTestClasses = new LinkedHashSet<>();
        LinkedHashMap<String, ClassAbi.Kind> changed = new LinkedHashMap<>();
        for (String raw : in.dirtyPaths() == null ? List.<String>of() : in.dirtyPaths()) {
            if (raw == null || raw.isBlank()) continue;
            Path p = Path.of(raw);
            if (!p.isAbsolute() && root != null) p = root.resolve(p);
            p = p.normalize();
            String name = p.getFileName() == null ? "" : p.getFileName().toString();
            if (name.equals(ManifestPaths.MANIFEST) || name.equals(ManifestPaths.LOCK)) {
                manifest = true;
                onlyNonClass = false;
                continue;
            }
            if (name.equals("package-info.java") || name.equals("module-info.java")) continue;
            if (!isClassSource(name)) continue;
            onlyNonClass = false;
            if (module != null && p.startsWith(module)) {
                String relStr = module.relativize(p).toString().replace('\\', '/');
                SourceFqcs.Hit hit = fqcs.classify(relStr);
                switch (hit.kind()) {
                    case TEST -> {
                        if (hit.fqc() != null) dirtyTestClasses.add(hit.fqc());
                    }
                    case MAIN -> {
                        if (hit.fqc() != null) {
                            changed.merge(
                                    hit.fqc(),
                                    kindOf(hit.fqc(), in),
                                    (a, b) -> a == ClassAbi.Kind.ABI || b == ClassAbi.Kind.ABI
                                            ? ClassAbi.Kind.ABI
                                            : ClassAbi.Kind.BODY);
                        }
                    }
                    case NONE -> {
                        /* under no source root — not a classifiable source */
                    }
                }
            }
        }
        if (in.compiledMainSources() != null) {
            for (Path src : in.compiledMainSources()) {
                if (src == null) continue;
                Path p = src.toAbsolutePath().normalize();
                if (module == null || !p.startsWith(module)) continue;
                String relStr = module.relativize(p).toString().replace('\\', '/');
                SourceFqcs.Hit hit = fqcs.classify(relStr);
                if (hit.kind() == SourceFqcs.Kind.MAIN && hit.fqc() != null && !changed.containsKey(hit.fqc())) {
                    changed.put(hit.fqc(), kindOf(hit.fqc(), in));
                }
            }
        }
        return new Dirty(manifest, onlyNonClass, dirtyTestClasses, changed);
    }

    /** The refusals the classified dirty set decides, in precedence order; null when ranking may proceed. */
    private static @Nullable AffectedTests refusal(Inputs in, Dirty dirty, List<AffectedTests.ModuleRow> cone) {
        Map<String, ClassAbi.Kind> changed = dirty.changed();
        if (dirty.manifest()) {
            return AffectedTests.refused(
                    new AffectedTests.Refuse("manifest", "jk.toml or jk-lock.toml changed"),
                    cone,
                    toChanged(in, changed));
        }
        if (changed.size() > AffectedTests.MAX_CHANGED_FQCS) {
            return AffectedTests.refused(
                    new AffectedTests.Refuse(
                            "too-many-fqcs",
                            changed.size() + " changed types (max " + AffectedTests.MAX_CHANGED_FQCS + ")"),
                    cone,
                    toChanged(in, changed));
        }
        boolean foreignEmpty =
                in.foreignChanged() == null || in.foreignChanged().isEmpty();
        boolean noCode = changed.isEmpty() && dirty.dirtyTestClasses().isEmpty() && foreignEmpty;
        boolean noneRead =
                in.recorded() == null || in.recorded().values().stream().allMatch(String::isEmpty);
        if (noCode
                && noneRead
                && dirty.onlyNonClass()
                && in.dirtyPaths() != null
                && !in.dirtyPaths().isEmpty()) {
            return AffectedTests.refused(
                    new AffectedTests.Refuse("non-class", "dirty set is only resources/docs"), cone, List.of());
        }
        return null;
    }

    /**
     * Every selected test that scores against the change, best first, ties by name. A class with a
     * record scores by how its recorded cone and reads meet the change; only a class with none is
     * scored by the changed types its bytecode names. A dirty test source ranks only through its
     * entry in {@code tests}: one the run's tags or {@code [test] exclude-classes} keep out has none,
     * and stays out.
     */
    private static List<Scored> score(Inputs in, Dirty dirty) {
        LinkedHashSet<String> dirtyTestClasses = dirty.dirtyTestClasses();
        List<String> include =
                in.selection() == null ? List.of() : in.selection().includeTags();
        List<String> exclude =
                in.selection() == null ? List.of() : in.selection().excludeTags();
        // Local changed types score first; a dependency module's classified types (foreign) fill
        // in behind them so a dependent's importers rank too. Local wins a duplicate.
        LinkedHashMap<String, ClassAbi.Kind> scoreable = new LinkedHashMap<>(dirty.changed());
        if (in.foreignChanged() != null) {
            for (var e : in.foreignChanged().entrySet()) scoreable.putIfAbsent(e.getKey(), e.getValue());
        }
        Set<String> nameable = new LinkedHashSet<>(in.productionFqcs() == null ? Set.of() : in.productionFqcs());
        nameable.addAll(scoreable.keySet());
        Map<String, Set<String>> bySimple = TestClassIndex.productionFqcsBySimple(nameable);

        Map<String, String> recorded = in.recorded() == null ? Map.of() : in.recorded();
        List<Scored> scored = new ArrayList<>();
        for (TestClassIndex.Entry t : in.tests() == null ? List.<TestClassIndex.Entry>of() : in.tests()) {
            // A helper or fixture under the test root runs no test: never a candidate.
            if (!t.runsTests() || !tagOk(t.tags(), include, exclude)) continue;
            int score = 0;
            String reason = "";
            if (dirtyTestClasses.contains(t.className())) {
                score = 110;
                reason = "test-src";
            }
            String overlap = recorded.get(t.className());
            if (overlap != null) {
                int fromRecord = recordScore(overlap);
                if (fromRecord > score) {
                    score = fromRecord;
                    reason = overlap;
                }
                if (score > 0) scored.add(new Scored(t.className(), reason, score));
                continue;
            }
            Set<String> nameHits =
                    t.nameMatchSimple().isEmpty() ? Set.of() : bySimple.getOrDefault(t.nameMatchSimple(), Set.of());
            for (var e : scoreable.entrySet()) {
                boolean imported = t.imports().contains(e.getKey());
                boolean named = nameHits.contains(e.getKey())
                        || (!t.nameMatchSimple().isEmpty()
                                && t.nameMatchSimple().equals(simpleName(e.getKey())));
                if (e.getValue() == ClassAbi.Kind.BODY && named && score < 100) {
                    score = 100;
                    reason = "name-body:" + e.getKey();
                } else if (e.getValue() == ClassAbi.Kind.ABI && imported && score < 90) {
                    score = 90;
                    reason = "abi-import:" + e.getKey();
                } else if (e.getValue() == ClassAbi.Kind.BODY && imported && score < 80) {
                    score = 80;
                    reason = "body-import:" + e.getKey();
                } else if (e.getValue() == ClassAbi.Kind.ABI && named && score < 70) {
                    score = 70;
                    reason = "name-abi:" + e.getKey();
                }
            }
            if (score > 0) scored.add(new Scored(t.className(), reason, score));
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed().thenComparing(Scored::className));
        return scored;
    }

    /**
     * A recorded overlap's score: a class that ran a changed class's code or read a changed file
     * ranks with a test named for the changed type, one whose cone only holds it below an import.
     */
    private static int recordScore(String overlap) {
        if (overlap.startsWith("cone-ran:") || overlap.startsWith("reads:")) return 100;
        return overlap.isEmpty() ? 0 : 75;
    }

    private record Scored(String className, String reason, int score) {}

    private static ClassAbi.Kind kindOf(String fqc, Inputs in) {
        ClassAbi.Fingerprint prev =
                in.preCompileAbi() == null ? null : in.preCompileAbi().get(fqc);
        ClassAbi.Fingerprint cur =
                in.currentAbi() == null ? null : in.currentAbi().get(fqc);
        if (cur == null) return ClassAbi.Kind.ABI;
        return ClassAbi.classify(prev, cur);
    }

    private static List<AffectedTests.ChangedType> toChanged(Inputs in, Map<String, ClassAbi.Kind> changed) {
        String coord = in.moduleCoord() == null ? "" : in.moduleCoord();
        List<AffectedTests.ChangedType> out = new ArrayList<>();
        for (var e : changed.entrySet()) {
            out.add(new AffectedTests.ChangedType(coord, e.getKey(), e.getValue()));
        }
        return out;
    }

    private static boolean tagOk(Set<String> tags, List<String> include, List<String> exclude) {
        if (exclude != null) {
            for (String e : exclude) {
                if (tags.contains(e)) return false;
            }
        }
        if (include != null && !include.isEmpty()) {
            for (String i : include) {
                if (tags.contains(i)) return true;
            }
            return false;
        }
        return true;
    }

    /**
     * The module-relative path of a dirty main source that is newer than its compiled class, or
     * {@code null} when every dirty class file is at least as fresh as its source (or absent —
     * ranking from sources is honest, stale bytecode is not).
     */
    public static @Nullable String staleDirtyMain(Path root, Path moduleDir, List<String> dirty, Path classesDir) {
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
            if (!isClassSource(name)) continue;
            String rel = module.relativize(p).toString().replace('\\', '/');
            SourceFqcs.Hit hit = fqcs.classify(rel);
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

    /** A source file whose compilation produces classes — the grain the ranker reasons in. */
    static boolean isClassSource(String fileName) {
        return fileName.endsWith(".java")
                || fileName.endsWith(".kt")
                || fileName.endsWith(".groovy")
                || fileName.endsWith(".scala");
    }

    static boolean isMainSource(String rel) {
        String s = rel.replace('\\', '/');
        return s.contains("src/main/") || s.startsWith("src/") && !isTestSource(s);
    }

    static boolean isTestSource(String rel) {
        String s = rel.replace('\\', '/');
        // Layout roots only — not a production package named `test` under src/main.
        if (s.contains("src/test/")
                || s.contains("src/integration/")
                || s.contains("src/e2e/")
                || s.contains("src/it/")) {
            return true;
        }
        return s.startsWith("test/") || s.startsWith("integration/") || s.startsWith("e2e/") || s.startsWith("it/");
    }

    static String simpleName(String fqc) {
        if (fqc == null || fqc.isBlank()) return "";
        int dot = fqc.lastIndexOf('.');
        String simple = dot < 0 ? fqc : fqc.substring(dot + 1);
        if (simple.endsWith("Kt") && simple.length() > 2) {
            return simple.substring(0, simple.length() - 2);
        }
        return simple;
    }

    static @Nullable String fqcFromSource(String rel) {
        String s = rel.replace('\\', '/');
        int src = s.indexOf("src/");
        if (src < 0) return null;
        String rest = s.substring(src + 4);
        int slash = rest.indexOf('/');
        if (slash < 0) return null;
        rest = rest.substring(slash + 1); // drop main/test/java|kotlin|groovy
        // drop language dir if present
        for (String lang : List.of("java/", "kotlin/", "groovy/")) {
            if (rest.startsWith(lang)) {
                rest = rest.substring(lang.length());
                break;
            }
        }
        int ext = rest.lastIndexOf('.');
        if (ext < 0) return null;
        return rest.substring(0, ext).replace('/', '.');
    }

    static boolean exists(Path p) {
        return p != null && Files.exists(p);
    }
}
