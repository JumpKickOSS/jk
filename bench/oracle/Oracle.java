// SPDX-License-Identifier: Apache-2.0

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The clean-build oracle: for every edit class, an incremental {@code jk build} must produce what a
 * build from nothing produces, and a second incremental build must run nothing.
 *
 * <p>Run with the jk under test on {@code PATH}: {@code java bench/oracle/Oracle.java [work-dir]
 * [class,…]}. It writes a fixture workspace (Java, Kotlin and Groovy modules, a sibling chain, a
 * same-package split, resources, a test that reads a file nothing declares), then for each edit:
 *
 * <ol>
 *   <li>applies it and runs {@code jk build} in the working copy against a cache kept for the whole
 *       run;
 *   <li>runs {@code jk build} there again: no compile, package or test step may run;
 *   <li>copies the sources to a fresh directory and builds them against a fresh, empty cache;
 *   <li>compares: the exit codes, every jar and every file of every classes tree byte for byte, and
 *       the code graph the build refreshed against a from-scratch {@code jk graph -r}.
 * </ol>
 *
 * <p>Prints one line per class and exits non-zero when any fails. The one-heavy-run-per-machine rule
 * applies: it builds the fixture twice per class.
 */
public final class Oracle {

    private static final boolean WINDOWS =
            System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");

    /**
     * One edit class. {@code settled}: the edit itself leaves nothing to do, so the incremental build
     * after it must run nothing either.
     */
    private record Edit(
            String name,
            Applier apply,
            Applier undo,
            List<String> flags,
            Map<String, String> env,
            boolean posixOnly,
            boolean settled) {
        Edit(String name, Applier apply, Applier undo, List<String> flags, Map<String, String> env, boolean posixOnly) {
            this(name, apply, undo, flags, env, posixOnly, false);
        }
    }

    @FunctionalInterface
    private interface Applier {
        void apply(Path ws) throws Exception;
    }

    private record Run(int exit, String out) {}

    public static void main(String[] args) throws Exception {
        Path work = args.length > 0 ? Path.of(args[0]).toAbsolutePath() : Files.createTempDirectory("jk-oracle");
        Set<String> only = args.length > 1 ? new TreeSet<>(List.of(args[1].split(","))) : Set.of();
        Path ws = work.resolve("ws");
        Path incCache = work.resolve("cache-inc");
        deleteTree(work);
        Files.createDirectories(ws);
        fixture(ws);
        git(ws, "init", "-q");
        git(ws, "config", "user.email", "oracle@example.invalid");
        git(ws, "config", "user.name", "oracle");
        git(ws, "config", "core.autocrlf", "false");
        git(ws, "add", "-A");
        git(ws, "commit", "-qm", "fixture");
        Run lock = jk(ws, incCache, List.of("lock"), Map.of());
        must(lock, "jk lock");
        must(jk(ws, incCache, List.of("build"), Map.of()), "first build");
        must(jk(ws, incCache, List.of("graph"), Map.of()), "first graph");
        git(ws, "add", "-A");
        git(ws, "commit", "-qm", "lock");

        int failed = 0;
        for (Edit e : edits()) {
            if (!only.isEmpty() && !only.contains(e.name())) continue;
            if (e.posixOnly() && WINDOWS) {
                System.out.printf("%-26s skip (posix only)%n", e.name());
                continue;
            }
            String verdict;
            long started = System.nanoTime();
            try {
                verdict = check(work, ws, incCache, e);
            } catch (Exception ex) {
                verdict = "error: " + ex;
            }
            boolean ok = verdict.equals("ok") || verdict.startsWith("ok ");
            try {
                e.undo().apply(ws);
            } catch (Exception ex) {
                verdict = verdict + "; undo failed: " + ex;
                ok = false;
            }
            if (!ok) failed++;
            System.out.printf("%-26s %5.1fs  %s%n", e.name(), (System.nanoTime() - started) / 1e9, verdict);
            // The next edit starts from what the build just made of this one; a broken edit is
            // undone so the classes after it test what they name.
            if (!ok) {
                git(ws, "checkout", "-q", "--", ".");
                git(ws, "clean", "-qfd", "--", "lib", "app", "kt", "gv", "data");
                jk(ws, incCache, List.of("build"), Map.of());
            }
        }
        System.out.println(failed == 0 ? "oracle: every class green" : "oracle: " + failed + " class(es) failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    /** Apply {@code e}, then hold the incremental build to a clean one; "ok" or what differed. */
    private static String check(Path work, Path ws, Path incCache, Edit e) throws Exception {
        e.apply().apply(ws);
        List<String> build = new ArrayList<>(List.of("build", "-O", "jsonl"));
        build.addAll(e.flags());
        Run inc = jk(ws, incCache, build, e.env());
        if (e.settled()) {
            List<String> ran = ran(inc.out());
            if (!ran.isEmpty()) return "the build after it ran " + ran;
        }
        if (inc.exit() == 0) {
            // A build that succeeded left nothing to do: the next one runs no step. (One that
            // failed must run its failed suite again, so it is held to the clean build alone.)
            Run again = jk(ws, incCache, build, e.env());
            List<String> rerun = ran(again.out());
            if (!rerun.isEmpty()) return "second build ran " + rerun;
        }

        Path clean = work.resolve("clean");
        Path cleanCache = work.resolve("cache-clean");
        deleteTree(clean);
        deleteTree(cleanCache);
        copySources(ws, clean);
        Run fresh = jk(clean, cleanCache, build, e.env());
        if (inc.exit() != fresh.exit()) return "exit " + inc.exit() + " incremental, " + fresh.exit() + " clean";
        if (inc.exit() != 0) return "ok (both builds fail, exit " + inc.exit() + ")";
        Map<String, String> a = outputs(ws);
        Map<String, String> b = outputs(clean);
        if (!a.equals(b)) return "outputs differ: " + diff(a, b);
        must(jk(clean, cleanCache, List.of("graph", "-r"), Map.of()), "clean graph");
        byte[] ga = snapshot(ws);
        byte[] gb = snapshot(clean);
        if (!Arrays.equals(ga, gb)) return "graph differs: the refreshed graph.bin is not the from-scratch one";
        if (a.isEmpty() || ga.length == 0) return "nothing compared: " + a.size() + " outputs, " + ga.length + " graph bytes";
        return "ok";
    }

    /** Compile, package and test steps a build ran rather than skipped. */
    private static List<String> ran(String jsonl) {
        Pattern finish = Pattern.compile(
                "\"type\":\"task-finish\",\"task\":\"([a-z-]+)\".*?\"status\":\"([A-Z]+)\"(?:.*?\"module\":\"([^\"]+)\")?");
        List<String> out = new ArrayList<>();
        for (String line : jsonl.split("\n")) {
            Matcher m = finish.matcher(line);
            if (!m.find() || !m.group(2).equals("SUCCESS")) continue;
            String task = m.group(1);
            if (task.startsWith("compile-") || task.startsWith("package-") || task.equals("run-tests")) {
                if (line.contains("no tests to run")) continue;
                out.add((m.group(3) == null ? "" : m.group(3) + " ") + task);
            }
        }
        return out;
    }

    /** Every jar under a module's target dir and every file of its classes trees, by digest. */
    private static Map<String, String> outputs(Path ws) throws IOException {
        Map<String, String> out = new TreeMap<>();
        for (String module : List.of("lib", "app", "kt", "gv")) {
            Path target = ws.resolve("target").resolve(module);
            if (!Files.isDirectory(target)) target = ws.resolve(module).resolve("target");
            if (!Files.isDirectory(target)) continue;
            try (Stream<Path> files = Files.list(target)) {
                for (Path jar : files.filter(p -> p.toString().endsWith(".jar")).toList()) {
                    out.put(module + "/" + jar.getFileName(), sha(jar));
                }
            }
            for (String tree : List.of("classes", "test-classes")) {
                Path root = target.resolve(tree);
                if (!Files.isDirectory(root)) continue;
                try (Stream<Path> walk = Files.walk(root)) {
                    for (Path f : walk.filter(Files::isRegularFile).toList()) {
                        String name = f.getFileName().toString();
                        if (name.endsWith("stamp")) continue;
                        out.put(
                                module + "/" + tree + "/"
                                        + root.relativize(f).toString().replace('\\', '/'),
                                sha(f));
                    }
                }
            }
        }
        return out;
    }

    /** The graph snapshot a refresh wrote: the same tree gives the same bytes, however it was built. */
    private static byte[] snapshot(Path root) throws IOException {
        Path bin = root.resolve("target/graph/graph.bin");
        return Files.isRegularFile(bin) ? Files.readAllBytes(bin) : new byte[0];
    }

    private static String diff(Map<String, String> a, Map<String, String> b) {
        List<String> d = new ArrayList<>();
        Set<String> keys = new TreeSet<>(a.keySet());
        keys.addAll(b.keySet());
        for (String k : keys) {
            if (!Objects.equals(a.get(k), b.get(k))) {
                d.add(k + (a.get(k) == null ? " (clean only)" : b.get(k) == null ? " (incremental only)" : ""));
            }
            if (d.size() == 5) break;
        }
        return String.join(", ", d);
    }

    // ------------------------------------------------------------------ the edits

    private static List<Edit> edits() {
        List<Edit> e = new ArrayList<>();
        Path greet = Path.of("lib/src/main/java/com/example/lib/Greet.java");
        Path limits = Path.of("lib/src/main/java/com/example/lib/Limits.java");
        e.add(edit("body", ws -> replace(ws.resolve(greet), "return PREFIX + name;", "return PREFIX + name.strip();")));
        e.add(edit(
                "signature",
                ws -> replace(
                        ws.resolve(greet),
                        "    private Greet() {}",
                        "    private Greet() {}\n\n    public static int width() {\n        return 3;\n    }")));
        e.add(edit("public-constant", ws -> replace(ws.resolve(greet), "PREFIX = \"hello \"", "PREFIX = \"howdy \"")));
        e.add(edit("package-private-constant", ws -> replace(ws.resolve(limits), "MAX = 3", "MAX = 4")));
        e.add(edit(
                "add-class",
                ws -> Files.writeString(
                        ws.resolve("lib/src/main/java/com/example/lib/Extra.java"),
                        "package com.example.lib;\n\npublic final class Extra {\n    private Extra() {}\n}\n")));
        e.add(edit("delete-class", ws -> Files.delete(ws.resolve("lib/src/main/java/com/example/lib/Extra.java"))));
        e.add(edit("rename-class", ws -> {
            Path from = ws.resolve("lib/src/main/java/com/example/lib/Tool.java");
            Files.writeString(
                    ws.resolve("lib/src/main/java/com/example/lib/Tools.java"),
                    Files.readString(from)
                            .replace("class Tool ", "class Tools ")
                            .replace("private Tool()", "private Tools()"));
            Files.delete(from);
        }));
        e.add(edit("case-only-rename", ws -> {
            Path from = ws.resolve("lib/src/main/java/com/example/lib/Tools.java");
            Path mid = ws.resolve("lib/src/main/java/com/example/lib/Tools.tmp");
            Files.move(from, mid);
            Files.writeString(
                    ws.resolve("lib/src/main/java/com/example/lib/TOOLS.java"),
                    Files.readString(mid)
                            .replace("class Tools ", "class TOOLS ")
                            .replace("private Tools()", "private TOOLS()"));
            Files.delete(mid);
        }));
        e.add(edit("move-package", ws -> {
            Path from = ws.resolve("lib/src/main/java/com/example/lib/TOOLS.java");
            Path dir = Files.createDirectories(ws.resolve("lib/src/main/java/com/example/lib/util"));
            Files.writeString(
                    dir.resolve("TOOLS.java"),
                    Files.readString(from).replace("package com.example.lib;", "package com.example.lib.util;"));
            Files.delete(from);
        }));
        e.add(edit(
                "resource-add",
                ws -> Files.writeString(ws.resolve("lib/src/main/resources/added.properties"), "added=yes\n")));
        e.add(edit(
                "resource-edit",
                ws -> replace(ws.resolve("lib/src/main/resources/greet.properties"), "greeting=hello", "greeting=hi")));
        e.add(edit("resource-delete", ws -> Files.delete(ws.resolve("lib/src/main/resources/added.properties"))));
        e.add(edit("touch-r-same-size", ws -> {
            Path f = ws.resolve(greet);
            FileTime was = Files.getLastModifiedTime(f);
            replace(f, "PREFIX = \"howdy \"", "PREFIX = \"HOWDY \"");
            Files.setLastModifiedTime(f, was);
        }));
        e.add(edit("older-copy", ws -> {
            Path f = ws.resolve(greet);
            String older = Files.readString(f).replace("\"HOWDY \"", "\"hello \"");
            FileTime past = FileTime.fromMillis(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30));
            Files.writeString(f, older);
            Files.setLastModifiedTime(f, past);
        }));
        e.add(edit("crlf", ws -> {
            Path f = ws.resolve(greet);
            Files.writeString(f, Files.readString(f).replace("\r\n", "\n").replace("\n", "\r\n"));
        }));
        e.add(edit("new-test-input", ws -> {
            Files.writeString(ws.resolve("data/extra.txt"), "x\n");
            replace(
                    ws.resolve("app/src/test/java/com/example/app/AppTest.java"),
                    "        assertNotEquals(\"two\"",
                    "        assertFalse(Files.readString(Path.of(\"../data/extra.txt\")).isEmpty());\n"
                            + "        assertNotEquals(\"two\"");
        }));
        e.add(new Edit(
                "undeclared-test-input",
                ws -> Files.writeString(ws.resolve("data/expected.txt"), "two\n"),
                ws -> {
                    Files.writeString(ws.resolve("data/expected.txt"), "one\n");
                    jk(ws, ws.resolveSibling("cache-inc"), List.of("build"), Map.of());
                },
                List.of(),
                Map.of(),
                false));
        e.add(new Edit("jvm-arg", ws -> {}, ws -> {}, List.of("--jvm-arg=-Doracle.probe=fail"), Map.of(), false));
        e.add(new Edit("test-env", ws -> {}, ws -> {}, List.of(), Map.of("ORACLE_ENV", "fail"), false));
        e.add(edit("javac-args", ws -> append(ws.resolve("lib/jk.toml"), "\n[javac]\nargs = [\"-parameters\"]\n")));
        e.add(edit(
                "java-release",
                ws -> replace(ws.resolve("lib/jk.toml"), "name = \"lib\"", "name = \"lib\"\njava = 21")));
        e.add(edit(
                "kotlin-const",
                ws -> replace(
                        ws.resolve("kt/src/main/kotlin/com/example/kt/Kt.kt"),
                        "const val LIMIT = 5",
                        "const val LIMIT = 6")));
        e.add(edit(
                "groovy-body",
                ws -> replace(ws.resolve("gv/src/main/groovy/com/example/gv/Gv.groovy"), "'gv ' + n", "'GV ' + n")));
        e.add(new Edit(
                "symlink",
                ws -> {
                    Path target = Files.writeString(
                            ws.resolve("data/Linked.java"),
                            "package com.example.app;\n\npublic final class Linked {\n    private Linked() {}\n}\n");
                    Files.createSymbolicLink(ws.resolve("app/src/main/java/com/example/app/Linked.java"), target);
                },
                ws -> {},
                List.of(),
                Map.of(),
                true));
        e.add(new Edit(
                "clean",
                ws -> {
                    must(jk(ws, ws.resolveSibling("cache-inc"), List.of("clean"), Map.of()), "jk clean");
                    must(jk(ws, ws.resolveSibling("cache-inc"), List.of("build"), Map.of()), "build after clean");
                    // The clean took the graph with it; a build keeps a graph current, never starts one.
                    must(jk(ws, ws.resolveSibling("cache-inc"), List.of("graph"), Map.of()), "graph after clean");
                },
                ws -> {},
                List.of(),
                Map.of(),
                false,
                true));
        e.add(new Edit(
                "redo",
                ws -> must(jk(ws, ws.resolveSibling("cache-inc"), List.of("build", "-r"), Map.of()), "jk build -r"),
                ws -> {},
                List.of(),
                Map.of(),
                false,
                true));
        e.add(edit("edit-during-force", ws -> race(ws, List.of("build", "--force"), greet, "return \"<\" + PREFIX +")));
        e.add(edit("edit-during-build", ws -> race(ws, List.of("build"), greet, "return \">\" + PREFIX +")));
        e.add(edit("branch-switch", ws -> {
            git(ws, "add", "-A");
            git(ws, "commit", "-qm", "before switch");
            git(ws, "checkout", "-qb", "other");
            prefixReturn(ws.resolve(greet), "!");
            git(ws, "commit", "-qam", "other");
            git(ws, "checkout", "-q", "-");
        }));
        e.add(edit("stash-pop", ws -> {
            prefixReturn(ws.resolve(greet), "[");
            git(ws, "stash", "-q");
            git(ws, "stash", "pop", "-q");
        }));
        return e;
    }

    /**
     * Start {@code args} with the slow suite on, rewrite {@code file}'s return while it runs, and wait
     * for it: the next build must see the edit, whatever the racing one recorded.
     */
    private static void race(Path ws, List<String> args, Path file, String to) throws Exception {
        List<String> slow = new ArrayList<>(args);
        slow.add("--jvm-arg=-Doracle.slow=1");
        Run[] result = new Run[1];
        Thread build = Thread.ofPlatform().start(() -> {
            try {
                result[0] = jk(ws, ws.resolveSibling("cache-inc"), slow, Map.of());
            } catch (Exception e) {
                result[0] = new Run(-1, String.valueOf(e));
            }
        });
        Thread.sleep(2_500);
        String s = Files.readString(ws.resolve(file));
        Files.writeString(ws.resolve(file), s.replaceFirst("return [^;]*PREFIX \\+", Matcher.quoteReplacement(to)));
        build.join();
    }

    /** Put {@code mark} in front of the value {@code hello} returns, whatever earlier classes made of it. */
    private static void prefixReturn(Path f, String mark) throws IOException {
        String s = Files.readString(f);
        Matcher m = Pattern.compile("return [^;]*PREFIX").matcher(s);
        if (!m.find()) throw new IOException(f.getFileName() + " has no return of PREFIX");
        int at = m.start() + "return ".length();
        Files.writeString(f, s.substring(0, at) + "\"" + mark + "\" + " + s.substring(at));
    }

    private static Edit edit(String name, Applier apply) {
        return new Edit(name, apply, ws -> {}, List.of(), Map.of(), false);
    }

    // ------------------------------------------------------------------ the fixture

    private static void fixture(Path ws) throws IOException {
        // Outputs stay out of git, as in a real project: a branch switch moves sources only.
        write(ws, ".gitignore", "target/\n.jk/\n");
        write(ws, "jk.toml", """
                group   = "com.example"
                name    = "oracle"
                version = "1.0.0"
                java    = 25
                kotlin  = "2.4.21"
                groovy  = "5.1.3"

                [workspace]
                modules = ["lib", "app", "kt", "gv"]

                [workspace.dependencies]
                junit-jupiter = "6.1.3"
                """);
        write(ws, "lib/jk.toml", """
                name = "lib"

                [test-dependencies]
                junit-jupiter.workspace = true
                """);
        write(ws, "lib/src/main/java/com/example/lib/Greet.java", """
                package com.example.lib;

                public final class Greet {
                    public static final String PREFIX = "hello ";

                    private Greet() {}

                    public static String hello(String name) {
                        return PREFIX + name;
                    }
                }
                """);
        write(ws, "lib/src/main/java/com/example/lib/Limits.java", """
                package com.example.lib;

                final class Limits {
                    static final int MAX = 3;

                    private Limits() {}
                }
                """);
        write(ws, "lib/src/main/java/com/example/lib/Tool.java", """
                package com.example.lib;

                public final class Tool {
                    private Tool() {}
                }
                """);
        write(ws, "lib/src/main/resources/greet.properties", "greeting=hello\n");
        write(ws, "lib/src/test/java/com/example/lib/SlowTest.java", """
                package com.example.lib;

                import org.junit.jupiter.api.Test;

                class SlowTest {
                    @Test
                    void slowWhenAsked() throws Exception {
                        if (System.getProperty("oracle.slow") != null) Thread.sleep(6_000);
                    }
                }
                """);
        write(ws, "lib/src/test/java/com/example/lib/LimitsTest.java", """
                package com.example.lib;

                import static org.junit.jupiter.api.Assertions.assertTrue;

                import org.junit.jupiter.api.Test;

                class LimitsTest {
                    @Test
                    void positive() {
                        assertTrue(Limits.MAX > 0);
                    }
                }
                """);
        write(ws, "app/jk.toml", """
                name = "app"

                [dependencies]
                lib.workspace = true

                [test-dependencies]
                junit-jupiter.workspace = true

                [test]
                env = ["ORACLE_ENV"]
                """);
        write(ws, "app/src/main/java/com/example/app/App.java", """
                package com.example.app;

                import com.example.lib.Greet;

                public final class App {
                    private App() {}

                    public static String greeting() {
                        return Greet.hello("app") + Greet.PREFIX.length();
                    }
                }
                """);
        write(ws, "app/src/main/java/com/example/lib/SamePackage.java", """
                package com.example.lib;

                public final class SamePackage {
                    private SamePackage() {}

                    public static int max() {
                        return Limits.MAX;
                    }
                }
                """);
        write(ws, "app/src/test/java/com/example/app/AppTest.java", """
                package com.example.app;

                import static org.junit.jupiter.api.Assertions.assertFalse;
                import static org.junit.jupiter.api.Assertions.assertNotEquals;

                import java.nio.file.Files;
                import java.nio.file.Path;
                import org.junit.jupiter.api.Test;

                class AppTest {
                    @Test
                    void greets() throws Exception {
                        assertFalse(App.greeting().isEmpty());
                        assertNotEquals("fail", System.getProperty("oracle.probe"));
                        assertNotEquals("fail", System.getenv("ORACLE_ENV"));
                        assertNotEquals("two", Files.readString(Path.of("../data/expected.txt")).strip());
                    }
                }
                """);
        write(ws, "data/expected.txt", "one\n");
        write(ws, "kt/jk.toml", """
                name = "kt"

                [dependencies]
                lib.workspace = true
                """);
        write(ws, "kt/src/main/kotlin/com/example/kt/Kt.kt", """
                package com.example.kt

                import com.example.lib.Greet

                const val LIMIT = 5

                inline fun twice(f: () -> Int): Int = f() + f()

                fun greet(): String = Greet.hello("kt") + LIMIT
                """);
        write(ws, "gv/jk.toml", """
                name = "gv"

                [dependencies]
                lib.workspace = true
                """);
        write(ws, "gv/src/main/groovy/com/example/gv/Gv.groovy", """
                package com.example.gv

                class Gv {
                    static String gv(String n) {
                        'gv ' + n
                    }
                }
                """);
    }

    // ------------------------------------------------------------------ plumbing

    private static Run jk(Path dir, Path cache, List<String> args, Map<String, String> env) throws Exception {
        List<String> cmd = new ArrayList<>();
        if (WINDOWS) {
            cmd.add("cmd");
            cmd.add("/c");
        }
        cmd.add("jk");
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
        pb.environment().put("JK_CACHE_DIR", cache.toString());
        pb.environment().put("JK_NONINTERACTIVE", "1");
        pb.environment().putAll(env);
        Process p = pb.start();
        String out = read(p.getInputStream());
        return new Run(p.waitFor(), out);
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        String out = read(p.getInputStream());
        if (p.waitFor() != 0) throw new IOException("git " + String.join(" ", args) + ": " + out);
    }

    private static void must(Run r, String what) {
        if (r.exit() != 0) throw new IllegalStateException(what + " failed (" + r.exit() + "):\n" + r.out());
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        in.transferTo(out);
        return out.toString(StandardCharsets.UTF_8);
    }

    private static void write(Path root, String rel, String content) throws IOException {
        Path f = root.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, content);
    }

    private static void append(Path f, String more) throws IOException {
        Files.writeString(f, Files.readString(f) + more);
    }

    private static void replace(Path f, String from, String to) throws IOException {
        String s = Files.readString(f);
        if (!s.contains(from)) throw new IOException(f.getFileName() + " has no `" + from + "`");
        Files.writeString(f, s.replace(from, to));
    }

    /** The working copy's sources, lock and data: everything but outputs and git. */
    private static void copySources(Path from, Path to) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                String name = dir.getFileName().toString();
                if (!dir.equals(from) && (name.equals("target") || name.equals(".git") || name.equals(".jk"))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(to.resolve(from.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path dest = to.resolve(from.relativize(file));
                if (attrs.isSymbolicLink()) {
                    Path target = Files.readSymbolicLink(file);
                    Path resolved = file.getParent().resolve(target).normalize();
                    // A link into the working copy points at the same file of the copy.
                    Files.createSymbolicLink(
                            dest, resolved.startsWith(from) ? to.resolve(from.relativize(resolved)) : target);
                } else {
                    Files.copy(file, dest, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                p.toFile().setWritable(true);
                Files.deleteIfExists(p);
            }
        }
    }

    private static String sha(Path f) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(Files.readAllBytes(f)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Oracle() {}
}
