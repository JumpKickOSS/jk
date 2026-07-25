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
                || Files.isDirectory(moduleDir.resolve("src/main/groovy"))
                || Files.isDirectory(moduleDir.resolve("src/test/java"))
                || Files.isDirectory(moduleDir.resolve("src/test/kotlin"))
                || Files.isDirectory(moduleDir.resolve("src/test/groovy"));
    }

    /**
     * Main Groovy source roots (JK-1165). SIMPLE shares {@code src/} by extension; TRADITIONAL is
     * {@code src/main/groovy} plus {@code src/main/java} (stray {@code .groovy} under the Java
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
     * Test-resource root for a suite (JK-1149).
     *
     * <ul>
     *   <li>SIMPLE default: {@code test-resources/}
     *   <li>SIMPLE named {@code integration}: {@code integration-resources/}
     *   <li>TRADITIONAL default: {@code src/test/resources}
     *   <li>TRADITIONAL named: {@code src/<name>/resources}
     * </ul>
     */
    public static Path suiteResourcesDir(Path moduleDir, boolean compact, String suite) {
        String s = suite == null || suite.isBlank() ? TestSuites.DEFAULT : suite;
        if (compact) {
            if (TestSuites.DEFAULT.equals(s)) return moduleDir.resolve("test-resources");
            return moduleDir.resolve(s + "-resources");
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
     * Plugin-contributed module roots (JK-1166): the active manifests' {@code
     * [[contribute.source-roots]]} entries (Grails' {@code grails-app} tree), relative dirs
     * regardless of on-disk presence. Empty when {@code jk.toml} is absent or unparseable.
     */
    public static List<Root> pluginContributedRoots(Path moduleDir) {
        Path toml = moduleDir.resolve("jk.toml");
        if (!Files.isRegularFile(toml)) return List.of();
        try {
            JkBuild build = JkBuildParser.parse(toml);
            List<Root> out = new ArrayList<>();
            for (cc.jumpkick.plugin.manifest.PluginContributions.SourceRoot root :
                    cc.jumpkick.plugin.manifest.PluginContributions.sourceRoots(build, moduleDir)) {
                out.add(new Root(root.dir(), root.resource() ? Kind.RESOURCE : Kind.SOURCE));
            }
            return List.copyOf(out);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    /**
     * All input roots that exist on disk: main source/resource + plugin-contributed roots +
     * every discovered test suite + suite resource dirs. Prefer this over hand-rolled path
     * literals.
     */
    public static List<Root> roots(Path moduleDir) {
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
        for (Root root : pluginContributedRoots(moduleDir)) {
            addIfDir(out, seen, moduleDir, root.relative(), root.kind());
        }

        List<String> suites = TestSuites.discover(moduleDir, compact);
        if (!suites.contains(TestSuites.DEFAULT) && hasDefaultSuiteDir(moduleDir, compact)) {
            List<String> withDefault = new ArrayList<>();
            withDefault.add(TestSuites.DEFAULT);
            withDefault.addAll(suites);
            suites = withDefault;
        }
        // Always surface default suite resource dir if present, even when suite has no sources yet.
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
        // Plugin-contributed roots (grails-app/…) are main inputs — always fingerprinted.
        for (Root root : pluginContributedRoots(moduleDir)) {
            addDir(dirs, moduleDir.resolve(root.relative()));
        }
        if (!skipTests) {
            if (compact) {
                // Default suite + named suites + per-suite resource dirs (not under src/).
                // Always include DEFAULT so test-resources/ is fingerprinted even when test/
                // sources are not yet present (memo correctness for fixture-only changes).
                LinkedHashSet<String> suites = new LinkedHashSet<>(TestSuites.discover(moduleDir, true));
                suites.add(TestSuites.DEFAULT);
                for (String suite : suites) {
                    for (Path r : TestSuites.javaRoots(moduleDir, true, suite)) addDir(dirs, r);
                    for (Path r : TestSuites.kotlinRoots(moduleDir, true, suite)) addDir(dirs, r);
                    for (Path r : TestSuites.groovyRoots(moduleDir, true, suite)) addDir(dirs, r);
                    addDir(dirs, suiteResourcesDir(moduleDir, true, suite));
                }
            } else {
                // src covers suite sources; still fingerprint any named-suite resource dirs
                // that live under src/<name>/resources (already under src walk). Default
                // test-resources is src/test/resources — under src.
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
        Path relPath =
                moduleDir.toAbsolutePath().normalize().relativize(abs.toAbsolutePath().normalize());
        if (relPath.startsWith("..")) return;
        String rel = relPath.toString().replace('\\', '/');
        if (rel.isEmpty() || !seen.add(rel)) return;
        out.add(new Root(rel, kind));
    }
}
