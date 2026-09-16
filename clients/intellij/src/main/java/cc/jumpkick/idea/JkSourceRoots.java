// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jetbrains.annotations.Nullable;

/**
 * A module's source roots as they lie on disk, the same reading the {@code jk ide} generators
 * make (see {@code docs/user/projects.md} for the two layouts). The wire model carries no roots:
 * they are a function of the tree, so the resolver discovers them here.
 *
 * <p>SIMPLE (Mill-shaped): {@code src/}, {@code resources/}, {@code test/src}, {@code
 * test/resources}, and {@code <suite>/src} + {@code <suite>/resources} per named suite.
 * TRADITIONAL (Maven-shaped): {@code src/main/{java,kotlin,groovy,scala,resources}}, {@code
 * src/test/…}, {@code src/<suite>/…}, plus {@code src/fixtures/java}. Every discovered test suite
 * and the guard suite are test roots.
 */
public final class JkSourceRoots {

    public enum Kind {
        SOURCE,
        RESOURCE,
        TEST,
        TEST_RESOURCE
    }

    /** A root relative to the module dir, forward slashes. */
    public record Root(String relative, Kind kind) {}

    private static final List<String> LANG_DIRS = List.of("java", "kotlin", "groovy", "scala");
    private static final List<String> TRADITIONAL_MARKERS = List.of("java", "kotlin", "scala", "groovy", "resources");
    private static final List<String> SOURCE_EXTS = List.of(".java", ".kt", ".groovy", ".scala");
    private static final String DEFAULT_SUITE = "test";
    private static final String GUARD_SUITE = "guard";
    private static final String FIXTURES_DIR = "fixtures";
    private static final String FIXTURES_ROOT = "src/fixtures/java";
    private static final Pattern SUITE_NAME = Pattern.compile("[a-z][a-z0-9_-]*");
    private static final Pattern LAYOUT_KEY = Pattern.compile("^\\s*layout\\s*=\\s*\"([^\"]*)\"");

    /** Top-level directories a SIMPLE module never treats as suites. */
    private static final Set<String> SIMPLE_RESERVED = Set.of(
            "src",
            "test",
            "resources",
            "target",
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

    private JkSourceRoots() {}

    /** Every root of {@code moduleDir} that exists on disk, main roots first, deduplicated. */
    public static List<Root> of(Path moduleDir, Path wsRoot) {
        boolean compact = isCompact(moduleDir, wsRoot);
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<Root> out = new ArrayList<>();
        if (compact) {
            add(out, seen, moduleDir, "src", Kind.SOURCE);
            add(out, seen, moduleDir, "resources", Kind.RESOURCE);
        } else {
            for (String lang : LANG_DIRS) add(out, seen, moduleDir, "src/main/" + lang, Kind.SOURCE);
            add(out, seen, moduleDir, "src/main/resources", Kind.RESOURCE);
            add(out, seen, moduleDir, FIXTURES_ROOT, Kind.TEST);
        }
        LinkedHashSet<String> suites = new LinkedHashSet<>();
        suites.add(DEFAULT_SUITE);
        suites.addAll(discoverSuites(moduleDir, compact));
        for (String suite : suites) {
            for (String rel : suiteSourceDirs(compact, suite)) add(out, seen, moduleDir, rel, Kind.TEST);
            add(out, seen, moduleDir, suiteResourcesDir(compact, suite), Kind.TEST_RESOURCE);
        }
        for (String rel : suiteSourceDirs(compact, GUARD_SUITE)) add(out, seen, moduleDir, rel, Kind.TEST);
        return List.copyOf(out);
    }

    /**
     * SIMPLE layout? An explicit {@code layout =} in the module's {@code jk.toml} wins, then the
     * workspace root's, else the tree: Maven marker dirs under {@code src/main} or {@code src/test}
     * make it TRADITIONAL.
     */
    static boolean isCompact(Path moduleDir, @Nullable Path wsRoot) {
        Boolean explicit = explicitLayout(moduleDir.resolve("jk.toml"));
        if (explicit == null && wsRoot != null && !wsRoot.equals(moduleDir)) {
            explicit = explicitLayout(wsRoot.resolve("jk.toml"));
        }
        if (explicit != null) return explicit;
        for (String parent : List.of("main", "test")) {
            for (String marker : TRADITIONAL_MARKERS) {
                if (Files.isDirectory(moduleDir.resolve("src").resolve(parent).resolve(marker))) return false;
            }
        }
        return true;
    }

    /** {@code TRUE} = simple, {@code FALSE} = traditional, {@code null} = no top-level key or auto. */
    private static @Nullable Boolean explicitLayout(Path toml) {
        if (!Files.isRegularFile(toml)) return null;
        try {
            for (String line : Files.readAllLines(toml)) {
                String t = line.strip();
                if (t.startsWith("[")) break; // top-level keys only
                var m = LAYOUT_KEY.matcher(line);
                if (!m.find()) continue;
                return switch (m.group(1).toLowerCase(Locale.ROOT)) {
                    case "simple" -> Boolean.TRUE;
                    case "traditional" -> Boolean.FALSE;
                    default -> null;
                };
            }
        } catch (IOException ignored) {
            // unreadable manifest: let the tree decide
        }
        return null;
    }

    /** Named suites with at least one source file, sorted; the default suite is never listed. */
    static List<String> discoverSuites(Path moduleDir, boolean compact) {
        Path parent = compact ? moduleDir : moduleDir.resolve("src");
        if (!Files.isDirectory(parent)) return List.of();
        List<String> out = new ArrayList<>();
        try (Stream<Path> children = Files.list(parent)) {
            children.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> !n.startsWith("."))
                    .filter(n -> !DEFAULT_SUITE.equals(n) && !"main".equals(n))
                    .filter(n -> !FIXTURES_DIR.equals(n) && !GUARD_SUITE.equals(n))
                    .filter(n -> !compact || !SIMPLE_RESERVED.contains(n.toLowerCase(Locale.ROOT)))
                    .filter(n -> SUITE_NAME.matcher(n).matches())
                    .sorted()
                    .forEach(n -> {
                        for (String rel : suiteSourceDirs(compact, n)) {
                            if (hasSources(moduleDir.resolve(rel))) {
                                out.add(n);
                                return;
                            }
                        }
                    });
        } catch (IOException ignored) {
            // discovery is best-effort
        }
        return out;
    }

    private static List<String> suiteSourceDirs(boolean compact, String suite) {
        if (compact) return List.of((DEFAULT_SUITE.equals(suite) ? "test" : suite) + "/src");
        List<String> out = new ArrayList<>(LANG_DIRS.size());
        for (String lang : LANG_DIRS) out.add("src/" + suite + "/" + lang);
        return out;
    }

    private static String suiteResourcesDir(boolean compact, String suite) {
        if (compact) return (DEFAULT_SUITE.equals(suite) ? "test" : suite) + "/resources";
        return "src/" + suite + "/resources";
    }

    private static boolean hasSources(Path root) {
        if (!Files.isDirectory(root)) return false;
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.anyMatch(p -> {
                String n = p.getFileName().toString();
                for (String ext : SOURCE_EXTS) if (n.endsWith(ext)) return true;
                return false;
            });
        } catch (IOException e) {
            return false;
        }
    }

    private static void add(List<Root> out, Set<String> seen, Path moduleDir, String rel, Kind kind) {
        if (!Files.isDirectory(moduleDir.resolve(rel))) return;
        if (seen.add(rel)) out.add(new Root(rel, kind));
    }
}
