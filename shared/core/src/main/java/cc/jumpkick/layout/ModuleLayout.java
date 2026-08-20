// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.layout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Canonical module input roots.
 *
 * <p><b>SIMPLE (Mill-shaped — no {@code src/main/{java,kotlin,scala,groovy,resources}} dir):</b>
 *
 * <ul>
 * <li>main sources {@code src/}
 * <li>main resources {@code resources/}
 * <li>default tests {@code test/src/}
 * <li>default test resources {@code test/resources/}
 * <li>named suite {@code <name>/src/}, resources {@code <name>/resources/}
 * </ul>
 *
 * <p><b>TRADITIONAL (Maven import):</b> {@code src/main/{java,kotlin,groovy,resources}},
 * {@code src/test/…}, {@code src/<suite>/{java,kotlin,resources}}.
 *
 * <p>Language is by file extension inside each source dir. Outputs remain under {@code target/}.
 * Flat-siblings ({@code test/} as source root, top-level {@code test-resources/}, {@code
 * integration-resources/}) are <b>not</b> recognized.
 */
public final class ModuleLayout {

    public enum Kind {
        SOURCE,
        RESOURCE,
        TEST,
        TEST_RESOURCE
    }

    /** Relative path under the module dir. */
    public record Root(String relative, Kind kind) {
        public boolean test() {
            return kind == Kind.TEST || kind == Kind.TEST_RESOURCE;
        }

        public boolean resource() {
            return kind == Kind.RESOURCE || kind == Kind.TEST_RESOURCE;
        }
    }

    private ModuleLayout() {}

    /** Compact/SIMPLE layout: no {@code src/main/{java,kotlin,scala,groovy,resources}} directory. */
    public static boolean isCompact(Path moduleDir) {
        return SourceLayout.isSimpleLayout(moduleDir);
    }

    static boolean hasTraditionalDirs(Path moduleDir) {
        return SourceLayout.looksTraditional(moduleDir);
    }

    /**
     * Main Groovy source roots. SIMPLE shares {@code src/} by extension; TRADITIONAL is
     * {@code src/main/groovy} plus {@code src/main/java} (stray {@code.groovy} under the Java
     * root compiles too, mirroring the Kotlin collector).
     */
    public static List<Path> mainGroovyRoots(Path moduleDir, boolean compact) {
        if (compact) return List.of(moduleDir.resolve("src"));
        return List.of(moduleDir.resolve("src/main/groovy"), moduleDir.resolve("src/main/java"));
    }

    /** Main resources directory (SIMPLE: {@code resources/}; TRADITIONAL: {@code src/main/resources}). */
    public static Path mainResourcesDir(Path moduleDir, boolean compact) {
        return compact ? moduleDir.resolve("resources") : moduleDir.resolve("src/main/resources");
    }

    /** Default-suite test resources. */
    public static Path testResourcesDir(Path moduleDir, boolean compact) {
        return suiteResourcesDir(moduleDir, compact, TestSuites.DEFAULT);
    }

    /**
     * Test-resource root for a suite Mill-like).
     *
     * <ul>
     * <li>SIMPLE default: {@code test/resources/}
     * <li>SIMPLE named {@code integration}: {@code integration/resources/}
     * <li>TRADITIONAL default: {@code src/test/resources}
     * <li>TRADITIONAL named: {@code src/<name>/resources}
     * </ul>
     */
    public static Path suiteResourcesDir(Path moduleDir, boolean compact, String suite) {
        String s = suite == null || suite.isBlank() ? TestSuites.DEFAULT : suite;
        if (compact) {
            if (TestSuites.DEFAULT.equals(s)) return moduleDir.resolve("test").resolve("resources");
            return moduleDir.resolve(s).resolve("resources");
        }
        if (TestSuites.DEFAULT.equals(s)) return moduleDir.resolve("src/test/resources");
        return moduleDir.resolve("src").resolve(s).resolve("resources");
    }

    /** Resource dirs for the selected suites that exist on disk (stable order). */
    public static List<Path> suiteResourceDirs(Path moduleDir, boolean compact, List<String> suites) {
        List<String> want = suites == null || suites.isEmpty() ? List.of(TestSuites.DEFAULT) : suites;
        LinkedHashSet<Path> out = new LinkedHashSet<>();
        for (String suite : want) {
            Path r = suiteResourcesDir(moduleDir, compact, suite);
            if (Files.isDirectory(r)) out.add(r.toAbsolutePath().normalize());
        }
        return List.copyOf(out);
    }

    /**
     * On-disk roots only — no plugin-schema parse. CLI IDE generators use this so
     * {@code PluginDescriptor} stays off the native reachability set (JK-2151).
     */
    public static List<Root> diskRoots(Path moduleDir) {
        boolean compact = isCompact(moduleDir);
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<Root> out = new ArrayList<>();

        if (compact) {
            addIfDir(out, seen, moduleDir, "src", Kind.SOURCE);
            addIfDir(out, seen, moduleDir, "resources", Kind.RESOURCE);
        } else {
            addIfDir(out, seen, moduleDir, "src/main/java", Kind.SOURCE);
            addIfDir(out, seen, moduleDir, "src/main/kotlin", Kind.SOURCE);
            addIfDir(out, seen, moduleDir, "src/main/groovy", Kind.SOURCE);
            addIfDir(out, seen, moduleDir, "src/main/resources", Kind.RESOURCE);
        }
        appendSuiteRoots(moduleDir, compact, seen, out);
        return List.copyOf(out);
    }

    /** On-disk roots including suites. Plugin-contributed roots: {@link ModuleLayoutPlugins}. */
    public static List<Root> roots(Path moduleDir) {
        return diskRoots(moduleDir);
    }

    private static void appendSuiteRoots(Path moduleDir, boolean compact, LinkedHashSet<String> seen, List<Root> out) {
        List<String> suites = TestSuites.discover(moduleDir, compact);
        if (!suites.contains(TestSuites.DEFAULT) && hasDefaultSuiteDir(moduleDir, compact)) {
            List<String> withDefault = new ArrayList<>();
            withDefault.add(TestSuites.DEFAULT);
            withDefault.addAll(suites);
            suites = withDefault;
        }
        LinkedHashSet<String> suiteNames = new LinkedHashSet<>(suites);
        suiteNames.add(TestSuites.DEFAULT);
        for (String suite : suiteNames) {
            for (Path root : TestSuites.javaRoots(moduleDir, compact, suite)) {
                addAbs(out, seen, moduleDir, root, Kind.TEST);
            }
            for (Path root : TestSuites.kotlinRoots(moduleDir, compact, suite)) {
                addAbs(out, seen, moduleDir, root, Kind.TEST);
            }
            for (Path root : TestSuites.groovyRoots(moduleDir, compact, suite)) {
                addAbs(out, seen, moduleDir, root, Kind.TEST);
            }
            addAbs(out, seen, moduleDir, suiteResourcesDir(moduleDir, compact, suite), Kind.TEST_RESOURCE);
        }
    }

    /**
     * Directories to walk for preflight dirty fingerprints: every root the build consumes,
     * deduplicated. When {@code skipTests}, omits test / test-resource roots.
     */
    public static List<Path> fingerprintDirs(Path moduleDir, boolean skipTests) {
        boolean compact = isCompact(moduleDir);
        LinkedHashSet<Path> dirs = new LinkedHashSet<>();
        if (compact) {
            addDir(dirs, moduleDir.resolve("src"));
            addDir(dirs, mainResourcesDir(moduleDir, true));
        } else {
            addDir(dirs, moduleDir.resolve("src"));
        }
        if (!skipTests) {
            if (compact) {
                LinkedHashSet<String> suites = new LinkedHashSet<>(TestSuites.discover(moduleDir, true));
                suites.add(TestSuites.DEFAULT);
                for (String suite : suites) {
                    for (Path r : TestSuites.javaRoots(moduleDir, true, suite)) addDir(dirs, r);
                    for (Path r : TestSuites.kotlinRoots(moduleDir, true, suite)) addDir(dirs, r);
                    for (Path r : TestSuites.groovyRoots(moduleDir, true, suite)) addDir(dirs, r);
                    addDir(dirs, suiteResourcesDir(moduleDir, true, suite));
                    // Also walk the suite module dir so new files under test/ are noticed even
                    // when only resources exist (test/resources).
                    if (TestSuites.DEFAULT.equals(suite)) {
                        addDir(dirs, moduleDir.resolve("test"));
                    } else {
                        addDir(dirs, moduleDir.resolve(suite));
                    }
                }
            }
        }
        return List.copyOf(dirs);
    }

    /** Discovered suite names (sources present). */
    public static List<String> discoveredSuites(Path moduleDir) {
        return TestSuites.discover(moduleDir, isCompact(moduleDir));
    }

    private static boolean hasDefaultSuiteDir(Path moduleDir, boolean compact) {
        for (Path r : TestSuites.javaRoots(moduleDir, compact, TestSuites.DEFAULT)) {
            if (Files.isDirectory(r)) return true;
        }
        for (Path r : TestSuites.kotlinRoots(moduleDir, compact, TestSuites.DEFAULT)) {
            if (Files.isDirectory(r)) return true;
        }
        for (Path r : TestSuites.groovyRoots(moduleDir, compact, TestSuites.DEFAULT)) {
            if (Files.isDirectory(r)) return true;
        }
        return false;
    }

    private static void addDir(LinkedHashSet<Path> dirs, Path p) {
        if (p != null && Files.isDirectory(p)) dirs.add(p.toAbsolutePath().normalize());
    }

    private static void addIfDir(List<Root> out, LinkedHashSet<String> seen, Path moduleDir, String rel, Kind kind) {
        if (!Files.isDirectory(moduleDir.resolve(rel))) return;
        if (!seen.add(rel)) return;
        out.add(new Root(rel, kind));
    }

    private static void addAbs(List<Root> out, LinkedHashSet<String> seen, Path moduleDir, Path abs, Kind kind) {
        if (!Files.isDirectory(abs)) return;
        Path relPath = moduleDir
                .toAbsolutePath()
                .normalize()
                .relativize(abs.toAbsolutePath().normalize());
        if (relPath.startsWith("..")) return;
        String rel = relPath.toString().replace('\\', '/');
        if (rel.isEmpty() || !seen.add(rel)) return;
        out.add(new Root(rel, kind));
    }
}
