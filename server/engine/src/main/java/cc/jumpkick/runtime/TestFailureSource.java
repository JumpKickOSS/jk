// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.layout.SourceLayout;
import cc.jumpkick.layout.TestSuites;
import cc.jumpkick.model.JkBuild;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
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
     * Owner + location after {@code at } and after a {@code module/} or {@code loader/module/}
     * prefix: {@code pkg.Class.method(File.java:42)} / {@code (File.kt:12)} /
     * {@code (Native Method)} / {@code (Unknown Source)}.
     */
    private static final Pattern FRAME = Pattern.compile("^([\\w.$]+)\\.([\\w$<>]+)\\(([^:)]+)(?::(\\d+))?\\)\\s*$");

    private TestFailureSource() {}

    /**
     * A resolved snippet for the CLI / wire. {@code relativePath} is module-relative when possible;
     * {@code errorLine} is 1-based in the file; {@code startLine} is the 1-based line number of
     * {@code lines.get(0)}.
     */
    public record Snippet(
            Path absolutePath, String relativePath, int errorLine, int startLine, List<String> lines, String language) {

        public Snippet {
            lines = List.copyOf(lines);
            if (relativePath == null) relativePath = "";
            if (language == null) language = "java";
        }
    }

    /**
     * Per-run cache: one snippet resolve and one suite-root walk per module. Share one instance
     * between {@code onFailure} diagnostics and {@code renderFailures}.
     */
    public static final class Cache {
        private final ConcurrentHashMap<SnipKey, Optional<Snippet>> snippets = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<FileKey, Optional<Path>> files = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Path, Layout> layouts = new ConcurrentHashMap<>();
        private final LongAdder walks = new LongAdder();

        public Optional<Snippet> resolve(Path moduleDir, String testClass, String stack) {
            return TestFailureSource.resolve(this, moduleDir, testClass, stack);
        }

        /** Times {@code Files.walk} ran (tests). */
        public int walkCount() {
            return walks.intValue();
        }

        Layout layout(Path moduleDir) {
            return layouts.computeIfAbsent(moduleDir.toAbsolutePath().normalize(), TestFailureSource::loadLayout);
        }
    }

    private record SnipKey(Path module, String testClass, String fileName, int line) {}

    private record FileKey(Path module, String testClass, String fileName) {}

    private record Layout(boolean simple, List<Path> roots) {}

    private static final Cache UNBOUNDED = new Cache();

    /** Best-effort: empty when module dir, stack, or file cannot be resolved. */
    public static Optional<Snippet> resolve(Path moduleDir, String testClass, String stack) {
        return resolve(UNBOUNDED, moduleDir, testClass, stack);
    }

    static Optional<Snippet> resolve(Cache cache, Path moduleDir, String testClass, String stack) {
        if (moduleDir == null || !Files.isDirectory(moduleDir)) return Optional.empty();
        if (stack == null || stack.isBlank()) return Optional.empty();
        Optional<Frame> frame = primaryFrame(stack, testClass);
        if (frame.isEmpty() || frame.get().line <= 0) return Optional.empty();
        Frame f = frame.get();
        Path mod = moduleDir.toAbsolutePath().normalize();
        SnipKey key = new SnipKey(mod, testClass == null ? "" : testClass, f.fileName, f.line);
        Cache c = cache == null ? UNBOUNDED : cache;
        return c.snippets.computeIfAbsent(key, k -> resolveUncached(c, mod, testClass, f));
    }

    private static Optional<Snippet> resolveUncached(Cache cache, Path moduleDir, String testClass, Frame f) {
        FileKey fk = new FileKey(moduleDir, testClass == null ? "" : testClass, f.fileName);
        Optional<Path> file = cache.files.computeIfAbsent(fk, k -> locateFile(cache, moduleDir, testClass, f.fileName));
        if (file.isEmpty()) return Optional.empty();
        try {
            int lineCount = countLines(file.get());
            if (lineCount <= 0 || f.line < 1 || f.line > lineCount) return Optional.empty();
            int[] w = window(lineCount, f.line, CONTEXT_LINES);
            List<String> slice = readWindow(file.get(), w[0], w[1]);
            if (slice.isEmpty()) return Optional.empty();
            Path abs = file.get();
            return Optional.of(
                    new Snippet(abs, relativize(moduleDir, abs), f.line, w[0] + 1, slice, languageOf(f.fileName)));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static int countLines(Path file) throws IOException {
        int n = 0;
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            while (r.readLine() != null) n++;
        }
        return n;
    }

    /** Lines {@code from}..{@code to} inclusive, 0-based. */
    static List<String> readWindow(Path file, int from, int to) throws IOException {
        if (to < from) return List.of();
        List<String> slice = new ArrayList<>(to - from + 1);
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            int n = 0;
            String line;
            while ((line = r.readLine()) != null) {
                if (n >= from && n <= to) slice.add(line);
                if (n >= to) break;
                n++;
            }
        }
        return slice;
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
        String s = raw.stripTrailing();
        int at = s.indexOf("at ");
        if (at < 0) return Optional.empty();
        s = s.substring(at + 3).stripLeading();
        int paren = s.lastIndexOf('(');
        if (paren <= 0) return Optional.empty();
        String owner = s.substring(0, paren);
        int slash = owner.lastIndexOf('/');
        if (slash >= 0) owner = owner.substring(slash + 1);
        String loc = s.substring(paren);
        Matcher m = FRAME.matcher(owner + loc);
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
        return locateFile(UNBOUNDED, moduleDir, testClass, fileName);
    }

    private static Optional<Path> locateFile(Cache cache, Path moduleDir, String testClass, String fileName) {
        if (fileName == null || fileName.isBlank() || fileName.indexOf('\0') >= 0) return Optional.empty();
        // Reject path-shaped names before resolve — stack file names are basenames.
        if (fileName.indexOf('/') >= 0 || fileName.indexOf('\\') >= 0) return Optional.empty();
        String pkgPath = packagePath(testClass);
        Layout layout = cache.layout(moduleDir);
        for (Path root : layout.roots()) {
            if (!pkgPath.isEmpty()) {
                Optional<Path> hit =
                        insideModuleFile(moduleDir, root.resolve(pkgPath).resolve(fileName));
                if (hit.isPresent()) return hit;
            }
            Optional<Path> hit = insideModuleFile(moduleDir, root.resolve(fileName));
            if (hit.isPresent()) return hit;
        }
        return scanByFileName(cache, moduleDir, pkgPath, fileName, layout);
    }

    private static Layout loadLayout(Path moduleDir) {
        boolean simple = isSimpleLayout(moduleDir);
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        addSuiteRoots(roots, moduleDir, simple);
        addSuiteRoots(roots, moduleDir, !simple);
        return new Layout(simple, List.copyOf(roots));
    }

    private static Optional<Path> scanByFileName(
            Cache cache, Path moduleDir, String pkgPath, String fileName, Layout layout) {
        Path preferSuffix = pkgPath.isEmpty()
                ? Path.of(fileName)
                : Path.of(pkgPath.replace('/', java.io.File.separatorChar), fileName);
        Path best = null;
        int seen = 0;
        for (Path root : layout.roots()) {
            if (!Files.isDirectory(root)) continue;
            if (!containedIn(moduleDir, root)) continue;
            cache.walks.increment();
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path p : (Iterable<Path>) walk::iterator) {
                    if (!Files.isRegularFile(p)) continue;
                    if (++seen > MAX_SCAN_FILES) return Optional.ofNullable(best);
                    if (!fileName.equals(p.getFileName().toString())) continue;
                    Optional<Path> ok = insideModuleFile(moduleDir, p);
                    if (ok.isEmpty()) continue;
                    if (p.endsWith(preferSuffix)) return ok;
                    if (best == null) best = ok.get();
                }
            } catch (IOException ignored) {
                // try next root
            }
        }
        return Optional.ofNullable(best);
    }

    private static void addSuiteRoots(java.util.Set<Path> roots, Path moduleDir, boolean compact) {
        List<String> suites = TestSuites.discover(moduleDir, compact);
        if (suites.isEmpty()) suites = List.of(TestSuites.DEFAULT);
        for (String suite : suites) {
            roots.addAll(TestSuites.javaRoots(moduleDir, compact, suite));
            roots.addAll(TestSuites.kotlinRoots(moduleDir, compact, suite));
            roots.addAll(TestSuites.groovyRoots(moduleDir, compact, suite));
        }
    }

    /** True when {@code candidate} normalizes to a path inside {@code moduleDir}. */
    static boolean containedIn(Path moduleDir, Path candidate) {
        if (moduleDir == null || candidate == null) return false;
        Path root = moduleDir.toAbsolutePath().normalize();
        Path abs = candidate.toAbsolutePath().normalize();
        return abs.startsWith(root);
    }

    /** Regular file under {@code moduleDir} after normalize; empty if missing or a path escape. */
    static Optional<Path> insideModule(Path moduleDir, Path candidate) {
        return insideModuleFile(moduleDir, candidate);
    }

    private static Optional<Path> insideModuleFile(Path moduleDir, Path candidate) {
        if (!containedIn(moduleDir, candidate)) return Optional.empty();
        Path abs = candidate.toAbsolutePath().normalize();
        if (!Files.isRegularFile(abs)) return Optional.empty();
        return Optional.of(abs);
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
