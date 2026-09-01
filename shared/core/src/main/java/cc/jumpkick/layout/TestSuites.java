// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.layout;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Mill-shaped test suite roots for a module.
 *
 * <p>Default suite is always {@code test}:
 *
 * <ul>
 * <li>Simple layout: {@code test/src/} (sources by extension)
 * <li>Traditional: {@code src/test/java} + {@code src/test/kotlin} + {@code src/test/groovy} + {@code src/test/scala}
 * </ul>
 *
 * <p>Additional suites are sibling module dirs (e.g. {@code integration}):
 *
 * <ul>
 * <li>Simple: {@code <name>/src/}
 * <li>Traditional: {@code src/<name>/java} + {@code src/<name>/kotlin}
 * </ul>
 *
 * <p>A suite is "present" when at least one {@code.java}/{@code.kt}/{@code.groovy}/{@code.scala}
 * file exists under its roots.
 */
public final class TestSuites {

    /** Canonical default suite name (maps to {@code test/src} or {@code src/test/…}). */
    public static final String DEFAULT = "test";

    /** Canonical integration suite — the extra rung {@code --gate} includes when it exists. */
    public static final String INTEGRATION = "integration";

    /** Default {@code --gate} suite list: unit plus integration (integration is optional). */
    public static final List<String> GATE = List.of(DEFAULT, INTEGRATION);

    /** Top-level names that are never treated as optional test suites in simple layout. */
    static final Set<String> SIMPLE_RESERVED = Set.of(
            "src",
            "test",
            "resources",
            BuildLayout.TARGET,
            "build",
            "docs",
            "doc",
            "out",
            "bin",
            "lib",
            "libs",
            "gradle",
            "node_modules",
            ".git",
            ".jk",
            "jk",
            ".idea",
            ".vscode");

    private TestSuites() {}

    /**
     * Suite names that exist on disk for this module (always includes {@link #DEFAULT} when that
     * suite has sources; always lists {@link #DEFAULT} first when present).
     */
    public static List<String> discover(Path projectDir, boolean compact) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (hasSources(
                javaRoots(projectDir, compact, DEFAULT),
                kotlinRoots(projectDir, compact, DEFAULT),
                groovyRoots(projectDir, compact, DEFAULT),
                scalaRoots(projectDir, compact, DEFAULT))) {
            names.add(DEFAULT);
        }
        if (compact) {
            try (Stream<Path> stream = Files.list(projectDir)) {
                stream.filter(Files::isDirectory)
                        .map(p -> p.getFileName().toString())
                        .filter(n -> !n.startsWith("."))
                        .filter(n -> !SIMPLE_RESERVED.contains(n.toLowerCase(Locale.ROOT)))
                        .filter(n -> isSuiteName(n))
                        .sorted()
                        .forEach(n -> {
                            if (hasSources(
                                    javaRoots(projectDir, true, n),
                                    kotlinRoots(projectDir, true, n),
                                    groovyRoots(projectDir, true, n),
                                    scalaRoots(projectDir, true, n))) {
                                names.add(n);
                            }
                        });
            } catch (IOException ignored) {
                // discovery is best-effort
            }
        } else {
            Path src = projectDir.resolve("src");
            if (Files.isDirectory(src)) {
                try (Stream<Path> stream = Files.list(src)) {
                    stream.filter(Files::isDirectory)
                            .map(p -> p.getFileName().toString())
                            .filter(n -> !"main".equals(n) && !"test".equals(n))
                            .filter(TestSuites::isSuiteName)
                            .sorted()
                            .forEach(n -> {
                                if (hasSources(
                                        javaRoots(projectDir, false, n),
                                        kotlinRoots(projectDir, false, n),
                                        groovyRoots(projectDir, false, n),
                                        scalaRoots(projectDir, false, n))) {
                                    names.add(n);
                                }
                            });
                } catch (IOException ignored) {
                    // best-effort
                }
            }
            // Ensure default is first if present
            if (names.remove(DEFAULT)) {
                List<String> ordered = new ArrayList<>();
                ordered.add(DEFAULT);
                ordered.addAll(names);
                return List.copyOf(ordered);
            }
        }
        return List.copyOf(names);
    }

    /** True if {@code name} is a legal suite identifier ({@code [a-z][a-z0-9_-]*}). */
    public static boolean isSuiteName(String name) {
        if (name == null || name.isEmpty()) return false;
        // Enforce the documented grammarthe permissive isLetter start turned any
        // capitalized/Unicode sibling dir with sources (Demo/, Beispiele/) into a test suite.
        char first = name.charAt(0);
        if (first < 'a' || first > 'z') return false;
        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) return false;
        }
        return true;
    }

    /**
     * Source root directory for a suite in SIMPLE layout: {@code test/src} or {@code <suite>/src}.
     */
    public static Path simpleSuiteSrc(Path projectDir, String suite) {
        String s = suite == null || suite.isBlank() ? DEFAULT : suite;
        if (DEFAULT.equals(s)) return projectDir.resolve("test").resolve("src");
        return projectDir.resolve(s).resolve("src");
    }

    /** Java source roots for one suite (may not exist). */
    public static List<Path> javaRoots(Path projectDir, boolean compact, String suite) {
        String s = suite == null || suite.isBlank() ? DEFAULT : suite;
        if (compact) {
            return List.of(simpleSuiteSrc(projectDir, s));
        }
        return List.of(projectDir.resolve("src").resolve(s).resolve("java"));
    }

    /** Kotlin source roots for one suite (may not exist). */
    public static List<Path> kotlinRoots(Path projectDir, boolean compact, String suite) {
        String s = suite == null || suite.isBlank() ? DEFAULT : suite;
        if (compact) {
            // Simple layout:.kt lives alongside.java under test/src or <suite>/src
            return List.of(simpleSuiteSrc(projectDir, s));
        }
        Path base = projectDir.resolve("src").resolve(s);
        return List.of(base.resolve("kotlin"), base.resolve("java"));
    }

    /** Groovy source roots for one suite (may not exist). */
    public static List<Path> groovyRoots(Path projectDir, boolean compact, String suite) {
        String s = suite == null || suite.isBlank() ? DEFAULT : suite;
        if (compact) {
            return List.of(simpleSuiteSrc(projectDir, s));
        }
        Path base = projectDir.resolve("src").resolve(s);
        return List.of(base.resolve("groovy"), base.resolve("java"));
    }

    /** Scala source roots for one suite (may not exist). */
    public static List<Path> scalaRoots(Path projectDir, boolean compact, String suite) {
        String s = suite == null || suite.isBlank() ? DEFAULT : suite;
        if (compact) {
            return List.of(simpleSuiteSrc(projectDir, s));
        }
        Path base = projectDir.resolve("src").resolve(s);
        return List.of(base.resolve("scala"), base.resolve("java"));
    }

    /** Collect {@code .java} under the selected suites (deduped, stable order). */
    public static List<Path> collectJavaSources(Path projectDir, boolean compact, List<String> suites)
            throws IOException {
        LinkedHashSet<Path> out = new LinkedHashSet<>();
        for (String suite : effectiveSuites(suites)) {
            for (Path root : javaRoots(projectDir, compact, suite)) {
                out.addAll(collectExt(root, ".java"));
            }
        }
        return new ArrayList<>(out);
    }

    /** Collect {@code .kt} under the selected suites (deduped, stable order). */
    public static List<Path> collectKotlinSources(Path projectDir, boolean compact, List<String> suites)
            throws IOException {
        LinkedHashSet<Path> out = new LinkedHashSet<>();
        for (String suite : effectiveSuites(suites)) {
            for (Path root : kotlinRoots(projectDir, compact, suite)) {
                out.addAll(collectExt(root, ".kt"));
            }
        }
        return new ArrayList<>(out);
    }

    /** Collect {@code .groovy} under the selected suites (deduped, stable order). */
    public static List<Path> collectGroovySources(Path projectDir, boolean compact, List<String> suites)
            throws IOException {
        LinkedHashSet<Path> out = new LinkedHashSet<>();
        for (String suite : effectiveSuites(suites)) {
            for (Path root : groovyRoots(projectDir, compact, suite)) {
                out.addAll(collectExt(root, ".groovy"));
            }
        }
        return new ArrayList<>(out);
    }

    /** Collect {@code .scala} under the selected suites (deduped, stable order). */
    public static List<Path> collectScalaSources(Path projectDir, boolean compact, List<String> suites)
            throws IOException {
        LinkedHashSet<Path> out = new LinkedHashSet<>();
        for (String suite : effectiveSuites(suites)) {
            for (Path root : scalaRoots(projectDir, compact, suite)) {
                out.addAll(collectExt(root, ".scala"));
            }
        }
        return new ArrayList<>(out);
    }

    /** Primary Java root used for incremental compile task identity (first selected suite). */
    public static Path primaryJavaRoot(Path projectDir, boolean compact, List<String> suites) {
        List<String> eff = effectiveSuites(suites);
        String first = eff.isEmpty() ? DEFAULT : eff.getFirst();
        return javaRoots(projectDir, compact, first).getFirst();
    }

    private static List<String> effectiveSuites(List<String> suites) {
        if (suites == null || suites.isEmpty()) return List.of(DEFAULT);
        return suites;
    }

    @SafeVarargs
    private static boolean hasSources(List<Path>... rootSets) {
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        for (List<Path> set : rootSets) roots.addAll(set);
        try {
            for (Path r : roots) {
                if (!collectExt(r, ".java").isEmpty()) return true;
                if (!collectExt(r, ".kt").isEmpty()) return true;
                if (!collectExt(r, ".groovy").isEmpty()) return true;
                if (!collectExt(r, ".scala").isEmpty()) return true;
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }

    /**
     * Every file under {@code root} with the given extension, sorted. Public because a test source
     * root need not belong to a suite — {@code [test] extra-src} and {@code [test] fixtures} name
     * roots that compile with the test tier and are never selected to run — and that collection
     * rule has one owner, here.
     */
    public static List<Path> collectExt(Path root, String ext) throws IOException {
        // Once per (root, extension) per request — see CompileSupport for why the same roots are
        // asked repeatedly. TestSuites.hasSources alone calls this four times per root, and
        // discover() is reached from every test-count estimate (JK-1043).
        return InputTrees.of(root).withExtension(ext);
    }
}
