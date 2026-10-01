// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.layout;

import cc.jumpkick.config.WorkspaceModules;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * <p>A suite is "present" when at least one {@code .java}/{@code .kt}/{@code .groovy}/{@code .scala}
 * file exists under its roots.
 */
public final class TestSuites {

    /** Canonical default suite name (maps to {@code test/src} or {@code src/test/…}). */
    public static final String DEFAULT = "test";

    /** Canonical integration suite — the extra rung {@code --guard} includes when it exists. */
    public static final String INTEGRATION = "integration";

    /** Default {@code --guard} suite list: unit plus integration (integration is optional). */
    public static final List<String> GUARD_SUITES = List.of(DEFAULT, INTEGRATION);

    /**
     * The guard suite: {@code src/guard/java} holds guard tests, compiled by {@code compile-guard}
     * and run by the guard lanes — never by {@code jk test}, {@code --all} or the gate. Reserved,
     * like {@code fixtures}: discovery does not return it and a {@code --suite guard} is an error.
     */
    public static final String GUARD = "guard";

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
     * The test-fixtures source set, which is not a test suite: it has its own step ({@code
     * compile-test-fixtures}) and output, so discovery must not fold {@code src/fixtures/java} into
     * {@code compile-test}. Matched by directory name; {@code BuildBlock.DEFAULT_FIXTURES} is the
     * spelling {@code fixtures = true} stores.
     */
    static final String FIXTURES_DIR = "fixtures";

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
            // Under a workspace root a sibling holding src/ is a member, not a suite of the root.
            Set<Path> members = Set.copyOf(WorkspaceModules.memberDirs(projectDir));
            try (Stream<Path> stream = Files.list(projectDir)) {
                stream.filter(Files::isDirectory)
                        .filter(p -> !members.contains(p.normalize()))
                        .map(p -> p.getFileName().toString())
                        .filter(n -> !n.startsWith("."))
                        .filter(n -> !SIMPLE_RESERVED.contains(n.toLowerCase(Locale.ROOT)))
                        .filter(n -> !FIXTURES_DIR.equals(n) && !GUARD.equals(n))
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
                            .filter(n -> !FIXTURES_DIR.equals(n) && !GUARD.equals(n))
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

    /**
     * The suites a selection can name in this module: the directory suites {@link #discover} finds,
     * then each suite {@code [test.suites.<name>] classes} declares, in manifest order. A
     * class-pattern suite is always present: its classes are the default suite's.
     */
    public static List<String> available(Path projectDir, boolean compact, Map<String, List<String>> classSuites) {
        List<String> found = discover(projectDir, compact);
        if (classSuites.isEmpty()) return found;
        LinkedHashSet<String> names = new LinkedHashSet<>(found);
        names.addAll(classSuites.keySet());
        return List.copyOf(names);
    }

    /**
     * The suites whose source roots compile-test collects for the resolved {@code selected} suites.
     * A class-pattern suite reads the default suite's classes, so it brings {@link #DEFAULT} (first)
     * and its own directory only when that has sources; a pattern suite and the default suite then
     * compile the same roots, so switching between them is one compile. A selection with no
     * class-pattern suite is returned as is.
     */
    public static List<String> compiled(
            Path projectDir, boolean compact, List<String> selected, Map<String, List<String>> classSuites) {
        if (selected.stream().noneMatch(classSuites::containsKey)) return selected;
        LinkedHashSet<String> out = new LinkedHashSet<>();
        out.add(DEFAULT);
        for (String suite : selected) {
            if (!classSuites.containsKey(suite) || hasSuiteSources(projectDir, compact, suite)) out.add(suite);
        }
        return List.copyOf(out);
    }

    private static boolean hasSuiteSources(Path projectDir, boolean compact, String suite) {
        return hasSources(
                javaRoots(projectDir, compact, suite),
                kotlinRoots(projectDir, compact, suite),
                groovyRoots(projectDir, compact, suite),
                scalaRoots(projectDir, compact, suite));
    }

    /** Whether the module carries a guard suite: Java sources under its {@link #GUARD} root. */
    public static boolean hasGuardSuite(Path projectDir, boolean compact) {
        return hasSources(javaRoots(projectDir, compact, GUARD), List.of(), List.of(), List.of());
    }

    /** The guard suite's Java sources, in path order; empty when there is no suite. */
    public static List<Path> guardSources(Path projectDir, boolean compact) throws IOException {
        List<Path> out = new ArrayList<>();
        for (Path root : javaRoots(projectDir, compact, GUARD)) {
            if (Files.isDirectory(root)) out.addAll(collectExt(root, ".java"));
        }
        return out;
    }

    /** True if {@code name} is a legal suite identifier ({@code [a-z][a-z0-9_-]*}). */
    public static boolean isSuiteName(String name) {
        if (name == null || name.isEmpty()) return false;
        // A capitalized or non-ASCII sibling directory (Demo/, Beispiele/) is not a suite.
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
        // discover is reached from every test-count estimate.
        return InputTrees.of(root).withExtension(ext);
    }
}
