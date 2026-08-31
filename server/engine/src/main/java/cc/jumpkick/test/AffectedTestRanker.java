// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.config.TestSelection;
import cc.jumpkick.layout.TestSuites;
import cc.jumpkick.lock.ManifestPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure ranking: dirty types × test index → capped {@link AffectedTests}. */
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
            List<AffectedTests.ModuleRow> cone) {}

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

        boolean outside = false;
        boolean manifest = false;
        boolean onlyNonClass = true;
        LinkedHashSet<String> dirtyTestClasses = new LinkedHashSet<>();
        LinkedHashMap<String, ClassAbi.Kind> changed = new LinkedHashMap<>();
        Set<String> suites = new LinkedHashSet<>(
                in.selection() == null
                        ? List.of(TestSuites.DEFAULT)
                        : in.selection().suites());
        if (suites.isEmpty()) suites.add(TestSuites.DEFAULT);

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
            boolean isJava = name.endsWith(".java") || name.endsWith(".kt") || name.endsWith(".groovy");
            if (!isJava) continue;
            onlyNonClass = false;
            if (module != null && p.startsWith(module)) {
                Path rel = module.relativize(p);
                String relStr = rel.toString().replace('\\', '/');
                if (isTestSource(relStr)) {
                    if (!suiteContains(relStr, suites)) {
                        outside = true;
                    } else {
                        String fqc = fqcFromSource(relStr);
                        if (fqc != null) dirtyTestClasses.add(fqc);
                    }
                } else if (isMainSource(relStr)) {
                    String fqc = fqcFromSource(relStr);
                    if (fqc != null) {
                        ClassAbi.Kind kind = kindOf(fqc, in);
                        changed.merge(
                                fqc,
                                kind,
                                (a, b) -> a == ClassAbi.Kind.ABI || b == ClassAbi.Kind.ABI
                                        ? ClassAbi.Kind.ABI
                                        : ClassAbi.Kind.BODY);
                    }
                }
            }
        }
        if (in.compiledMainSources() != null) {
            for (Path src : in.compiledMainSources()) {
                if (src == null) continue;
                Path p = src.toAbsolutePath().normalize();
                if (module != null
                        && p.startsWith(module)
                        && isMainSource(module.relativize(p).toString())) {
                    String fqc = fqcFromSource(module.relativize(p).toString().replace('\\', '/'));
                    if (fqc != null && !changed.containsKey(fqc)) {
                        changed.put(fqc, kindOf(fqc, in));
                    }
                    // Zinc rebuilt a dependent: treat the dirty type as ABI if another source compiled.
                    if (fqc != null
                            && in.dirtyPaths() != null
                            && in.dirtyPaths().size() >= 1) {
                        // leave kind as classified; extra ABI vote when this source is not itself dirty
                    }
                }
            }
        }
        if (manifest) {
            return AffectedTests.refused(
                    new AffectedTests.Refuse("manifest", "jk.toml or jk-lock.toml changed"),
                    cone,
                    toChanged(in, changed));
        }
        if (outside) {
            return AffectedTests.refused(
                    new AffectedTests.Refuse(
                            "outside-selection", "dirty test source is outside the current test selection"),
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
        boolean noCode = changed.isEmpty() && dirtyTestClasses.isEmpty();
        if (noCode
                && onlyNonClass
                && in.dirtyPaths() != null
                && !in.dirtyPaths().isEmpty()) {
            return AffectedTests.refused(
                    new AffectedTests.Refuse("non-class", "dirty set is only resources/docs"), cone, List.of());
        }

        List<String> include =
                in.selection() == null ? List.of() : in.selection().includeTags();
        List<String> exclude =
                in.selection() == null ? List.of() : in.selection().excludeTags();
        Map<String, Set<String>> bySimple =
                TestClassIndex.productionFqcsBySimple(in.productionFqcs() == null ? Set.of() : in.productionFqcs());

        List<Scored> scored = new ArrayList<>();
        for (TestClassIndex.Entry t : in.tests() == null ? List.<TestClassIndex.Entry>of() : in.tests()) {
            if (!tagOk(t.tags(), include, exclude)) continue;
            int score = 0;
            String reason = "";
            if (dirtyTestClasses.contains(t.className())) {
                score = 110;
                reason = "test-src";
            }
            Set<String> nameHits =
                    t.nameMatchSimple().isEmpty() ? Set.of() : bySimple.getOrDefault(t.nameMatchSimple(), Set.of());
            for (var e : changed.entrySet()) {
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
        LinkedHashSet<String> already = new LinkedHashSet<>();
        for (Scored s : scored) already.add(s.className());
        for (String fqc : dirtyTestClasses) {
            if (already.add(fqc)) scored.add(new Scored(fqc, "test-src", 110));
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed().thenComparing(Scored::className));
        int candidates = scored.size();
        List<AffectedTests.Row> rows = new ArrayList<>();
        String coord = in.moduleCoord() == null ? "" : in.moduleCoord();
        for (int i = 0; i < scored.size() && i < AffectedTests.CAP; i++) {
            Scored s = scored.get(i);
            rows.add(new AffectedTests.Row(coord, s.className(), s.reason(), s.score()));
        }
        if (rows.isEmpty()) return AffectedTests.empty(cone, toChanged(in, changed));
        return AffectedTests.ranked(cone, toChanged(in, changed), rows, candidates);
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

    static boolean isMainSource(String rel) {
        String s = rel.replace('\\', '/');
        return s.contains("src/main/") || s.startsWith("src/") && !isTestSource(s);
    }

    static boolean isTestSource(String rel) {
        String s = rel.replace('\\', '/');
        return s.contains("/test/")
                || s.contains("src/test/")
                || s.contains("src/integration/")
                || s.contains("src/e2e/")
                || s.contains("src/it/");
    }

    static boolean suiteContains(String rel, Set<String> suites) {
        String s = rel.replace('\\', '/');
        for (String suite : suites) {
            if ("test".equals(suite)
                    && (s.contains("src/test/") || s.contains("/test/java/") || s.contains("/test/kotlin/"))) {
                return true;
            }
            if (s.contains("src/" + suite + "/") || s.contains("/" + suite + "/")) return true;
        }
        return false;
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

    static String fqcFromSource(String rel) {
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
