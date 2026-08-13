// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.layout.SourceLayout;
import cc.jumpkick.model.JkBuild;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Locate the source file and a 7-line context window for a test failure from its stack trace.
 *
 * <p>Jupiter only knows FQCN + method; the stack's {@code (File.ext:line)} is the ground truth for
 * line number. Paths are resolved under the module dir using traditional
 * ({@code src/test/{java,kotlin,groovy}/…}) and simple ({@code test/src/…}) layouts, then a bounded
 * scan by file name.
 */
public final class TestFailureSource {

    /** Prefer 3 lines above + error + 3 below (= 7), expanding one side when clipped. */
    public static final int CONTEXT_LINES = 7;

    private static final int MAX_SCAN_FILES = 4_000;

    /**
     * Stack frame: {@code at pkg.Class.method(File.java:42)} or {@code (File.kt:12)} /
     * {@code (Native Method)} / {@code (Unknown Source)}.
     */
    private static final Pattern FRAME = Pattern.compile(
            "^\\s*at\\s+([\\w.$]+)\\.([\\w$<>]+)\\(([^:)]+)(?::(\\d+))?\\)\\s*$");

    private TestFailureSource() {}

    /**
     * A resolved snippet for the CLI / wire. {@code relativePath} is module-relative when possible;
     * {@code errorLine} is 1-based in the file; {@code startLine} is the 1-based line number of
     * {@code lines.get(0)}.
     */
    public record Snippet(
            Path absolutePath,
            String relativePath,
            int errorLine,
            int startLine,
            List<String> lines,
            String language) {

        public Snippet {
            lines = List.copyOf(lines);
            if (relativePath == null) relativePath = "";
            if (language == null) language = "java";
        }
    }

    /** Best-effort: empty when module dir, stack, or file cannot be resolved. */
    public static Optional<Snippet> resolve(Path moduleDir, String testClass, String stack) {
        if (moduleDir == null || !Files.isDirectory(moduleDir)) return Optional.empty();
        if (stack == null || stack.isBlank()) return Optional.empty();
        Optional<Frame> frame = primaryFrame(stack, testClass);
        if (frame.isEmpty() || frame.get().line <= 0) return Optional.empty();
        Frame f = frame.get();
        Optional<Path> file = locateFile(moduleDir, testClass, f.fileName);
        if (file.isEmpty()) return Optional.empty();
        try {
            List<String> all = Files.readAllLines(file.get(), StandardCharsets.UTF_8);
            if (all.isEmpty()) return Optional.empty();
            int errorLine = Math.min(f.line, all.size());
            int[] window = window(all.size(), errorLine, CONTEXT_LINES);
            List<String> slice = new ArrayList<>(window[1] - window[0] + 1);
            for (int i = window[0]; i <= window[1]; i++) slice.add(all.get(i));
            Path abs = file.get().toAbsolutePath().normalize();
            String rel = relativize(moduleDir, abs);
            return Optional.of(new Snippet(abs, rel, errorLine, window[0] + 1, slice, languageOf(f.fileName)));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Plain-text markers for {@link TestSupport#renderFailures} / CLI paint. */
    public static List<String> encodeMarkers(Snippet s) {
        List<String> out = new ArrayList<>(s.lines().size() + 2);
        out.add("@@source path="
                + s.relativePath()
                + " line="
                + s.errorLine()
                + " start="
                + s.startLine()
                + " lang="
                + s.language());
        int n = s.startLine();
        for (String line : s.lines()) {
            boolean err = n == s.errorLine();
            out.add("@@src " + n + (err ? "*" : "") + "|" + line);
            n++;
        }
        out.add("@@src-end");
        return out;
    }

    // ---- stack frame selection ------------------------------------------------

    record Frame(String className, String method, String fileName, int line) {}

    static Optional<Frame> primaryFrame(String stack, String testClass) {
        List<Frame> frames = new ArrayList<>();
        for (String raw : stack.split("\n", -1)) {
            parseFrame(raw).ifPresent(frames::add);
        }
        if (frames.isEmpty()) return Optional.empty();
        String cls = testClass == null ? "" : testClass;
        if (!cls.isBlank()) {
            for (Frame f : frames) {
                if (f.className.equals(cls) || f.className.startsWith(cls + "$")) {
                    if (f.line > 0 && isSourceFile(f.fileName)) return Optional.of(f);
                }
            }
        }
        for (Frame f : frames) {
            if (f.line > 0 && isSourceFile(f.fileName) && !isFramework(f.className)) return Optional.of(f);
        }
        for (Frame f : frames) {
            if (f.line > 0 && isSourceFile(f.fileName)) return Optional.of(f);
        }
        return Optional.empty();
    }

    static Optional<Frame> parseFrame(String raw) {
        if (raw == null) return Optional.empty();
        Matcher m = FRAME.matcher(raw.stripTrailing());
        if (!m.matches()) return Optional.empty();
        String file = m.group(3);
        int line = 0;
        if (m.group(4) != null) {
            try {
                line = Integer.parseInt(m.group(4));
            } catch (NumberFormatException ignored) {
                line = 0;
            }
        }
        return Optional.of(new Frame(m.group(1), m.group(2), file, line));
    }

    private static boolean isSourceFile(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        return n.endsWith(".java") || n.endsWith(".kt") || n.endsWith(".kts") || n.endsWith(".groovy");
    }

    private static boolean isFramework(String className) {
        if (className == null) return true;
        return className.startsWith("java.")
                || className.startsWith("javax.")
                || className.startsWith("jdk.")
                || className.startsWith("sun.")
                || className.startsWith("org.junit.")
                || className.startsWith("org.opentest4j.")
                || className.startsWith("org.assertj.")
                || className.startsWith("org.mockito.")
                || className.startsWith("worker.org.")
                || className.startsWith("org.gradle.");
    }

    // ---- file locate ----------------------------------------------------------

    static Optional<Path> locateFile(Path moduleDir, String testClass, String fileName) {
        if (fileName == null || fileName.isBlank()) return Optional.empty();
        String pkgPath = packagePath(testClass);
        List<Path> candidates = new ArrayList<>();

        // Prefer layout from jk.toml when parseable; still try both trees.
        boolean simplePreferred = isSimpleLayout(moduleDir);
        addLayoutCandidates(candidates, moduleDir, pkgPath, fileName, simplePreferred);
        addLayoutCandidates(candidates, moduleDir, pkgPath, fileName, !simplePreferred);

        for (Path p : candidates) {
            if (Files.isRegularFile(p)) return Optional.of(p);
        }
        return scanByFileName(moduleDir, pkgPath, fileName, simplePreferred);
    }

    private static void addLayoutCandidates(
            List<Path> out, Path moduleDir, String pkgPath, String fileName, boolean simple) {
        if (simple) {
            // Simple Mill-like: test/src[/package]/File.ext
            if (!pkgPath.isEmpty()) out.add(moduleDir.resolve("test/src").resolve(pkgPath).resolve(fileName));
            out.add(moduleDir.resolve("test/src").resolve(fileName));
        } else {
            for (String lang : List.of("java", "kotlin", "groovy")) {
                Path root = moduleDir.resolve("src/test").resolve(lang);
                if (!pkgPath.isEmpty()) out.add(root.resolve(pkgPath).resolve(fileName));
                out.add(root.resolve(fileName));
            }
        }
    }

    private static Optional<Path> scanByFileName(
            Path moduleDir, String pkgPath, String fileName, boolean simplePreferred) {
        List<Path> roots = new ArrayList<>();
        if (simplePreferred) {
            roots.add(moduleDir.resolve("test/src"));
            roots.add(moduleDir.resolve("src/test"));
        } else {
            roots.add(moduleDir.resolve("src/test"));
            roots.add(moduleDir.resolve("test/src"));
        }
        Path preferSuffix =
                pkgPath.isEmpty() ? Path.of(fileName) : Path.of(pkgPath.replace('/', java.io.File.separatorChar), fileName);
        Path best = null;
        int seen = 0;
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path p : (Iterable<Path>) walk::iterator) {
                    if (!Files.isRegularFile(p)) continue;
                    if (++seen > MAX_SCAN_FILES) return Optional.ofNullable(best);
                    if (!fileName.equals(p.getFileName().toString())) continue;
                    if (p.endsWith(preferSuffix)) return Optional.of(p);
                    if (best == null) best = p;
                }
            } catch (IOException ignored) {
                // try next root
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean isSimpleLayout(Path moduleDir) {
        JkBuild.Project auto = JkBuild.Project.builder("x", "x", "0")
                .layout(JkBuild.Layout.AUTO)
                .build();
        try {
            Path toml = moduleDir.resolve("jk.toml");
            if (!Files.isRegularFile(toml)) return SourceLayout.isSimpleLayout(auto, moduleDir);
            JkBuild b = JkBuildParser.parse(toml);
            return SourceLayout.isSimpleLayout(b.project(), moduleDir);
        } catch (Exception e) {
            return SourceLayout.isSimpleLayout(auto, moduleDir);
        }
    }

    private static String packagePath(String testClass) {
        if (testClass == null || testClass.isBlank()) return "";
        // Strip nested $Inner for directory purposes (Outer$Inner lives in Outer.java typically,
        // but Kotlin may use Outer.kt with nested — file name from stack is authoritative).
        String cls = testClass;
        int dollar = cls.indexOf('$');
        if (dollar >= 0) cls = cls.substring(0, dollar);
        int dot = cls.lastIndexOf('.');
        if (dot <= 0) return "";
        return cls.substring(0, dot).replace('.', '/');
    }

    private static String languageOf(String fileName) {
        if (fileName == null) return "java";
        String n = fileName.toLowerCase();
        if (n.endsWith(".kt") || n.endsWith(".kts")) return "kotlin";
        if (n.endsWith(".groovy")) return "groovy";
        return "java";
    }

    private static String relativize(Path moduleDir, Path abs) {
        try {
            Path mod = moduleDir.toAbsolutePath().normalize();
            Path a = abs.toAbsolutePath().normalize();
            if (a.startsWith(mod)) return mod.relativize(a).toString().replace('\\', '/');
            // Workspace-style: parent of module
            Path parent = mod.getParent();
            if (parent != null && a.startsWith(parent)) {
                return parent.relativize(a).toString().replace('\\', '/');
            }
            return a.getFileName().toString();
        } catch (Exception e) {
            return abs.getFileName().toString();
        }
    }

    /**
     * 0-based inclusive window of size up to {@code want} centered on {@code errorLine} (1-based),
     * expanding toward the longer side when clipped by EOF/BOF.
     */
    static int[] window(int fileLen, int errorLine1, int want) {
        if (fileLen <= 0) return new int[] {0, -1};
        if (fileLen <= want) return new int[] {0, fileLen - 1};
        int err = Math.max(0, Math.min(fileLen - 1, errorLine1 - 1));
        int before = (want - 1) / 2; // 3 when want=7
        int after = want - 1 - before; // 3
        int start = err - before;
        int end = err + after;
        if (start < 0) {
            end = Math.min(fileLen - 1, end - start);
            start = 0;
        }
        if (end >= fileLen) {
            int over = end - (fileLen - 1);
            start = Math.max(0, start - over);
            end = fileLen - 1;
        }
        // Grow if still short (tiny files already handled)
        while (end - start + 1 < want && start > 0) start--;
        while (end - start + 1 < want && end < fileLen - 1) end++;
        return new int[] {start, end};
    }
}
