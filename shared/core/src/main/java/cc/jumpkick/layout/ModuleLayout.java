// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.layout;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Canonical module input roots (JK-1145): one resolver for engine, IDE, BSP, and scaffold.
 *
 * <p><b>SIMPLE (flat-siblings, preferred):</b>
 *
 * <ul>
 *   <li>main sources {@code src/}
 *   <li>main resources {@code resources/}
 *   <li>default tests {@code test/}
 *   <li>test resources {@code test-resources/}
 *   <li>named suite {@code <name>/} (e.g. {@code integration/})
 * </ul>
 *
 * <p><b>TRADITIONAL (Maven import):</b> {@code src/main/{java,kotlin,resources}},
 * {@code src/test/…}, {@code src/<suite>/{java,kotlin}}.
 *
 * <p>Language is by file extension inside each source dir. Outputs remain under {@code target/}.
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

    /** Compact/SIMPLE layout for this module (parses {@code jk.toml} when present). */
    public static boolean isCompact(Path moduleDir) {
        Path toml = moduleDir.resolve("jk.toml");
        if (Files.isRegularFile(toml)) {
            try {
                JkBuild build = JkBuildParser.parse(toml);
                return SourceLayout.isSimpleLayout(build.project(), moduleDir);
            } catch (Exception ignored) {
                // fall through
            }
        }
        return !hasTraditionalDirs(moduleDir);
    }

    static boolean hasTraditionalDirs(Path moduleDir) {
        return Files.isDirectory(moduleDir.resolve("src/main/java"))
                || Files.isDirectory(moduleDir.resolve("src/main/kotlin"))
                || Files.isDirectory(moduleDir.resolve("src/test/java"))
                || Files.isDirectory(moduleDir.resolve("src/test/kotlin"));
    }

    /** Main resources directory (SIMPLE: {@code resources/}; TRADITIONAL: {@code src/main/resources}). */
    public static Path mainResourcesDir(Path moduleDir, boolean compact) {
        return compact ? moduleDir.resolve("resources") : moduleDir.resolve("src/main/resources");
    }

    /** Default-suite test resources. */
    public static Path testResourcesDir(Path moduleDir, boolean compact) {
        return compact ? moduleDir.resolve("test-resources") : moduleDir.resolve("src/test/resources");
    }

    /**
     * All input roots that exist on disk: main source/resource + every discovered test suite +
     * test-resources. Prefer this over hand-rolled path literals.
     */
    public static List<Root> roots(Path moduleDir) {
        boolean compact = isCompact(moduleDir);
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<Root> out = new ArrayList<>();

        if (compact) {
            addIfDir(out, seen, moduleDir, "src", Kind.SOURCE);
            addIfDir(out, seen, moduleDir, "resources", Kind.RESOURCE);
            addIfDir(out, seen, moduleDir, "test-resources", Kind.TEST_RESOURCE);
        } else {
            addIfDir(out, seen, moduleDir, "src/main/java", Kind.SOURCE);
            addIfDir(out, seen, moduleDir, "src/main/kotlin", Kind.SOURCE);
            addIfDir(out, seen, moduleDir, "src/main/resources", Kind.RESOURCE);
            addIfDir(out, seen, moduleDir, "src/test/resources", Kind.TEST_RESOURCE);
        }

        List<String> suites = TestSuites.discover(moduleDir, compact);
        if (!suites.contains(TestSuites.DEFAULT) && hasDefaultSuiteDir(moduleDir, compact)) {
            List<String> withDefault = new ArrayList<>();
            withDefault.add(TestSuites.DEFAULT);
            withDefault.addAll(suites);
            suites = withDefault;
        }
        for (String suite : suites) {
            for (Path root : TestSuites.javaRoots(moduleDir, compact, suite)) {
                addAbs(out, seen, moduleDir, root, Kind.TEST);
            }
            for (Path root : TestSuites.kotlinRoots(moduleDir, compact, suite)) {
                addAbs(out, seen, moduleDir, root, Kind.TEST);
            }
        }
        return List.copyOf(out);
    }

    /**
     * Directories to walk for preflight dirty fingerprints: every root the build consumes,
     * deduplicated (JK-1148). When {@code skipTests}, omits test / test-resource roots.
     */
    public static List<Path> fingerprintDirs(Path moduleDir, boolean skipTests) {
        boolean compact = isCompact(moduleDir);
        LinkedHashSet<Path> dirs = new LinkedHashSet<>();
        // Always fingerprint main sources + main resources + lock/toml are separate.
        if (compact) {
            addDir(dirs, moduleDir.resolve("src"));
            addDir(dirs, mainResourcesDir(moduleDir, true));
        } else {
            // Walking src/ covers main + traditional test + named suites under src/<name>
            addDir(dirs, moduleDir.resolve("src"));
        }
        if (!skipTests) {
            if (compact) {
                // Default suite + named suites + test-resources (not under src/)
                for (String suite : TestSuites.discover(moduleDir, true)) {
                    for (Path r : TestSuites.javaRoots(moduleDir, true, suite)) addDir(dirs, r);
                    for (Path r : TestSuites.kotlinRoots(moduleDir, true, suite)) addDir(dirs, r);
                }
                // Empty default suite dir still matters for future adds
                if (hasDefaultSuiteDir(moduleDir, true)) {
                    for (Path r : TestSuites.javaRoots(moduleDir, true, TestSuites.DEFAULT)) addDir(dirs, r);
                }
                addDir(dirs, testResourcesDir(moduleDir, true));
            } else {
                // src already covers traditional suites; test-resources path is under src/test
                // already when using src walk — no extra for traditional test resources
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
        Path relPath =
                moduleDir.toAbsolutePath().normalize().relativize(abs.toAbsolutePath().normalize());
        if (relPath.startsWith("..")) return;
        String rel = relPath.toString().replace('\\', '/');
        if (rel.isEmpty() || !seen.add(rel)) return;
        out.add(new Root(rel, kind));
    }
}
