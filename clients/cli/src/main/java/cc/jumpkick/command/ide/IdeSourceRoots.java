// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.ide;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.layout.SourceLayout;
import cc.jumpkick.layout.TestSuites;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Module source/resource folders for IDE export (JK-1139). Main roots plus every discovered
 * {@link TestSuites} root marked as test — one IDE module, all suites visible.
 */
public final class IdeSourceRoots {

    public enum Kind {
        SOURCE,
        TEST,
        RESOURCE,
        TEST_RESOURCE
    }

    /** Relative path under the module dir + kind for generators / BSP. */
    public record Root(String relative, Kind kind) {
        public boolean test() {
            return kind == Kind.TEST || kind == Kind.TEST_RESOURCE;
        }

        public boolean resource() {
            return kind == Kind.RESOURCE || kind == Kind.TEST_RESOURCE;
        }
    }

    private IdeSourceRoots() {}

    /**
     * Ordered roots that exist on disk. Uses {@link SourceLayout} when {@code jk.toml} parses;
     * otherwise falls back to a traditional-vs-simple directory probe (same idea as the old
     * generators).
     */
    public static List<Root> of(Path moduleDir) {
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

        // All discovered suites (including default) as test source roots.
        List<String> suites = TestSuites.discover(moduleDir, compact);
        // Always try default suite dirs even when empty of sources, so empty test/ still
        // appears if the directory exists (navigation / "create test" UX).
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

    /** Suite names discovered for run-config generation (non-empty sources only). */
    public static List<String> discoveredSuites(Path moduleDir) {
        return TestSuites.discover(moduleDir, isCompact(moduleDir));
    }

    /** Compact/simple layout for this module dir. */
    public static boolean isCompact(Path moduleDir) {
        Path toml = moduleDir.resolve("jk.toml");
        if (Files.isRegularFile(toml)) {
            try {
                JkBuild build = JkBuildParser.parse(toml);
                return SourceLayout.isSimpleLayout(build.project(), moduleDir);
            } catch (Exception ignored) {
                // fall through to probe
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

    private static boolean hasDefaultSuiteDir(Path moduleDir, boolean compact) {
        for (Path r : TestSuites.javaRoots(moduleDir, compact, TestSuites.DEFAULT)) {
            if (Files.isDirectory(r)) return true;
        }
        for (Path r : TestSuites.kotlinRoots(moduleDir, compact, TestSuites.DEFAULT)) {
            if (Files.isDirectory(r)) return true;
        }
        return false;
    }

    private static void addIfDir(List<Root> out, LinkedHashSet<String> seen, Path moduleDir, String rel, Kind kind) {
        if (!Files.isDirectory(moduleDir.resolve(rel))) return;
        if (!seen.add(rel)) return;
        out.add(new Root(rel, kind));
    }

    private static void addAbs(List<Root> out, LinkedHashSet<String> seen, Path moduleDir, Path abs, Kind kind) {
        if (!Files.isDirectory(abs)) return;
        Path relPath = moduleDir.toAbsolutePath().normalize().relativize(abs.toAbsolutePath().normalize());
        if (relPath.startsWith("..")) return;
        String rel = relPath.toString().replace('\\', '/');
        if (rel.isEmpty() || !seen.add(rel)) return;
        out.add(new Root(rel, kind));
    }
}
