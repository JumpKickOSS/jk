// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.layout.ModuleLayout;
import cc.jumpkick.layout.TestSuites;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Module-relative source path → FQC, resolved against the module's <em>actual</em> source roots
 * (traditional {@code src/main/java/…} and compact {@code src/…} / {@code test/src/…} alike), so a
 * compact module's dirty {@code src/com/acme/Foo.java} classifies as {@code com.acme.Foo} instead
 * of losing its first package segment to string surgery (JK-2609).
 *
 * <p>When the module dir does not exist on disk (pure ranker unit tests) or a path is under none
 * of the configured roots, classification falls back to the ranker's original string heuristics —
 * behavior-preserving for the layouts they understood.
 */
final class SourceFqcs {

    /** What a dirty source path is to the ranker. */
    enum Kind {
        MAIN,
        TEST_SELECTED,
        TEST_OUTSIDE,
        NONE
    }

    record Hit(Kind kind, @Nullable String fqc) {
        static final Hit NONE = new Hit(Kind.NONE, null);
    }

    private record Root(String rel, @Nullable String suite) {}

    /** Longest-prefix-first roots; empty when the module dir is unavailable (fallback-only). */
    private final List<Root> roots;

    private SourceFqcs(List<Root> roots) {
        this.roots = roots;
    }

    static SourceFqcs of(@Nullable Path moduleDir, Set<String> selectedSuites) {
        if (moduleDir == null || !Files.isDirectory(moduleDir)) return new SourceFqcs(List.of());
        try {
            Path module = moduleDir.toAbsolutePath().normalize();
            boolean compact = ModuleLayout.isCompact(module);
            List<Root> out = new ArrayList<>();
            LinkedHashSet<String> suites = new LinkedHashSet<>(TestSuites.discover(module, compact));
            suites.addAll(selectedSuites);
            suites.add(TestSuites.DEFAULT);
            for (String suite : suites) {
                LinkedHashSet<Path> suiteRoots = new LinkedHashSet<>();
                suiteRoots.addAll(TestSuites.javaRoots(module, compact, suite));
                suiteRoots.addAll(TestSuites.kotlinRoots(module, compact, suite));
                suiteRoots.addAll(TestSuites.groovyRoots(module, compact, suite));
                suiteRoots.addAll(TestSuites.scalaRoots(module, compact, suite));
                for (Path r : suiteRoots) {
                    out.add(new Root(module.relativize(r).toString().replace('\\', '/'), suite));
                }
            }
            if (compact) {
                out.add(new Root("src", null));
            } else {
                out.add(new Root("src/main/java", null));
                out.add(new Root("src/main/kotlin", null));
                out.add(new Root("src/main/groovy", null));
                out.add(new Root("src/main/scala", null));
            }
            // Longest first so a nested test root always beats a shorter main root prefix.
            out.sort((a, b) -> Integer.compare(b.rel().length(), a.rel().length()));
            return new SourceFqcs(List.copyOf(out));
        } catch (RuntimeException e) {
            return new SourceFqcs(List.of());
        }
    }

    /** Classify one module-relative source path ({@code /}-separated) against {@code suites}. */
    Hit classify(String rel, Set<String> suites) {
        for (Root root : roots) {
            String prefix = root.rel() + "/";
            if (!rel.startsWith(prefix)) continue;
            String fqc = toFqc(rel.substring(prefix.length()));
            if (root.suite() == null) return new Hit(Kind.MAIN, fqc);
            return suites.contains(root.suite()) ? new Hit(Kind.TEST_SELECTED, fqc) : new Hit(Kind.TEST_OUTSIDE, fqc);
        }
        return fallback(rel, suites);
    }

    /** The pre-JK-2609 string heuristics, for fake module dirs and paths outside every root. */
    private static Hit fallback(String rel, Set<String> suites) {
        if (AffectedTestRanker.isTestSource(rel)) {
            Kind kind = AffectedTestRanker.suiteContains(rel, suites) ? Kind.TEST_SELECTED : Kind.TEST_OUTSIDE;
            return new Hit(kind, AffectedTestRanker.fqcFromSource(rel));
        }
        if (AffectedTestRanker.isMainSource(rel)) {
            return new Hit(Kind.MAIN, AffectedTestRanker.fqcFromSource(rel));
        }
        return Hit.NONE;
    }

    private static @Nullable String toFqc(String underRoot) {
        int ext = underRoot.lastIndexOf('.');
        if (ext <= 0) return null;
        String base = underRoot.substring(0, ext);
        if (base.isBlank()) return null;
        return base.replace('/', '.');
    }
}
