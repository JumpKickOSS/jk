// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.test.ClassReads;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import org.jspecify.annotations.Nullable;

/**
 * Each test class's record of its last run in a module: its outcome and wall, and what it was seen to
 * do (files and code it read, programs it started, native libraries, the operating system's own
 * files, hosts, environment variables and system properties by name), attributed to it by its window
 * in the recording ({@link ClassReads}). Kept in jk's cache for the project, one file per module,
 * beside the suite's discovered inputs; a class that ran replaces its row, a class that did not keeps
 * the row of the run it last ran in.
 *
 * <p>Every row is bounded, whatever the class did: at most {@link ClassReads#READS_KEPT} files read,
 * with {@code readsMore} when it read more (it is then keyed on every file its suite was found to
 * read, which the suite's discovered inputs hold exactly), a few of each kind of evidence, and the
 * count of workspace classes it loaded, never their names. The file is never held whole: it is
 * written and read a row at a time, and a row past {@link #MAX_ROW_CHARS} (none this writes) is
 * skipped as unreadable.
 *
 * <p>A record names a test language-neutrally, as a container path and a case: a Java class {@code
 * cc.acme.OrderTest} is the container {@code ["cc.acme", "OrderTest"]}. JVM-only data sits under
 * {@code jvm}. The native libraries, the programs and the system's own files are platform evidence:
 * kept for whoever decides whether a result is bound to the platform it ran on, never key material.
 */
final class TestClassRecords {

    private TestClassRecords() {}

    /** The record file's name, under the module's directory of jk's project cache. */
    static final String FILE = "test-classes.ndjson";

    /** The longest row a reader takes; a longer one is no record. */
    static final int MAX_ROW_CHARS = 256 * 1024;

    static {
        // The header names the pool's reads: at 300 characters a path they stay within one row.
        if (ClassReads.POOLED_READS_KEPT * 300 > MAX_ROW_CHARS / 2) {
            throw new IllegalStateException("the pool's reads must fit within a record row");
        }
    }

    /** The most environment variables and system properties a row names, each; the rest are counted. */
    static final int NAMES_KEPT = ClassReads.NAMES_KEPT;

    /** Where the module at {@code moduleDir} keeps its class records. */
    static Path file(Path moduleDir) {
        Path dir = moduleDir.toAbsolutePath().normalize();
        try {
            if (Files.exists(dir)) dir = dir.toRealPath();
        } catch (IOException keepNormalized) {
            // the normalized path names it
        }
        String module = Hashing.sha256Hex(dir.toString()).substring(0, 16);
        return CacheTree.PROJECTS
                .under(JkDirs.cache())
                .resolve(module)
                .resolve("suite-reads")
                .resolve(FILE);
    }

    /** At most {@link #NAMES_KEPT} names, and how many were looked up in all. */
    static final class Names {
        final Set<String> kept = new TreeSet<>();
        int count;

        void add(String name) {
            if (kept.contains(name)) return;
            count++;
            if (kept.size() < NAMES_KEPT) kept.add(name);
        }
    }

    /**
     * What the run's JVMs looked up, by top-level test class: environment variables from {@code
     * <pid>.lookups}, system properties from {@code <pid>.props}, read a line at a time, each class's
     * names bounded. A lookup no class was running for is filed under the empty name, the pool.
     */
    record Lookups(Map<String, Names> env, Map<String, Names> props) {
        static Lookups read(Path dir) {
            Map<String, Names> env = new HashMap<>();
            Map<String, Names> props = new HashMap<>();
            try {
                PathUtil.forEachRegularFile(dir, (file, attrs) -> {
                    String name = file.getFileName().toString();
                    boolean prop = name.endsWith(".props");
                    if (!prop && !name.endsWith(".lookups")) return;
                    try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                        for (String line = bounded(in); line != null; line = bounded(in)) {
                            String[] f = line.split("\t", -1);
                            if (prop && f.length == 3) add(props, f[0], f[1]);
                            else if (!prop && f.length == 4) add(env, f[0], f[2]);
                        }
                    }
                });
            } catch (IOException | RuntimeException unreadable) {
                // what the probe could not finish is a record not given
            }
            return new Lookups(env, props);
        }

        /** The lookups {@code env} and {@code props} name, by test class. */
        static Lookups of(Map<String, Set<String>> env, Map<String, Set<String>> props) {
            Map<String, Names> e = new HashMap<>();
            Map<String, Names> p = new HashMap<>();
            env.forEach((cls, names) -> names.forEach(n -> add(e, cls, n)));
            props.forEach((cls, names) -> names.forEach(n -> add(p, cls, n)));
            return new Lookups(e, p);
        }

        static Lookups none() {
            return new Lookups(Map.of(), Map.of());
        }

        private static void add(Map<String, Names> into, String test, String name) {
            into.computeIfAbsent(TestClassLedger.topLevel(test), t -> new Names())
                    .add(name);
        }

        Names env(String testClass) {
            return env.getOrDefault(testClass, new Names());
        }

        Names props(String testClass) {
            return props.getOrDefault(testClass, new Names());
        }
    }

    /** One top-level class's outcome in one launch, its nested classes folded in. */
    record Outcome(String launch, String testClass, long tests, long failed, long skipped, long ranAt, long millis) {
        String rowKey() {
            return TestClassRecords.rowKey(launch, testClass);
        }

        boolean failedNow() {
            return failed > 0;
        }
    }

    /** {@code recorded}'s outcomes by top-level class and launch: counts summed, the latest run, the longest wall. */
    static Map<String, Outcome> outcomes(Map<String, TestClassLedger.Entry> recorded) {
        Map<String, Outcome> by = new TreeMap<>();
        recorded.forEach((key, e) -> {
            int at = key.indexOf('|');
            String launch = at < 0 ? "" : key.substring(0, at);
            String cls = TestClassLedger.topLevel(at < 0 ? key : key.substring(at + 1));
            by.merge(
                    rowKey(launch, cls),
                    new Outcome(launch, cls, e.tests(), e.failed(), e.skipped(), e.ranAt(), e.millis()),
                    (a, b) -> new Outcome(
                            launch,
                            cls,
                            a.tests() + b.tests(),
                            a.failed() + b.failed(),
                            a.skipped() + b.skipped(),
                            Math.max(a.ranAt(), b.ranAt()),
                            Math.max(a.millis(), b.millis())));
        });
        return by;
    }

    /** Makes the row of a class that ran, from the row its last run left, if any. */
    @FunctionalInterface
    interface RowMaker {
        String row(Outcome ran, @Nullable String previous);
    }

    /**
     * Rewrite the record file of {@code moduleDir} a row at a time: {@code header} first, then every
     * earlier row, a class of {@code ran} replaced by what {@code maker} makes of it, then the classes
     * of {@code ran} that had no row. Nothing but the row in hand is held. Best-effort: a file that
     * cannot be written leaves the old one.
     */
    static void merge(Path moduleDir, String header, Map<String, Outcome> ran, RowMaker maker) throws IOException {
        Path file = file(moduleDir);
        Files.createDirectories(file.getParent());
        Set<String> done = new HashSet<>();
        Path staging = AtomicWrites.staging(file);
        try (BufferedWriter out = Files.newBufferedWriter(staging, StandardCharsets.UTF_8)) {
            out.write(header);
            out.write('\n');
            if (Files.isRegularFile(file)) {
                try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    for (String line = bounded(in); line != null; line = bounded(in)) {
                        String key = rowKeyOf(line);
                        if (key == null || done.contains(key)) continue;
                        Outcome o = ran.get(key);
                        out.write(o == null ? line : maker.row(o, line));
                        out.write('\n');
                        done.add(key);
                    }
                }
            }
            for (Outcome o : ran.values()) {
                if (done.contains(o.rowKey())) continue;
                out.write(maker.row(o, null));
                out.write('\n');
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(staging);
            throw e;
        }
        AtomicWrites.moveInto(staging, file);
    }

    /** The record file's header line, or null when there is none or it is past a row's bound. */
    static @Nullable String header(Path file) {
        if (!Files.isRegularFile(file)) return null;
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String first = bounded(in);
            return first != null && "suite".equals(Jsonl.str(first, "kind")) ? first : null;
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /** {@code consumer} given each row of the record file by launch and class, one at a time. */
    static void forEachRow(Path file, BiConsumer<String, String> consumer) {
        if (!Files.isRegularFile(file)) return;
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            for (String line = bounded(in); line != null; line = bounded(in)) {
                String key = rowKeyOf(line);
                if (key != null) consumer.accept(key, line);
            }
        } catch (IOException | RuntimeException unreadable) {
            // what cannot be read is no record
        }
    }

    /** The launch and class a row is of, or null for the header, an empty line or a torn row. */
    private static @Nullable String rowKeyOf(String line) {
        if (line.isEmpty() || Jsonl.has(line, "kind")) return null;
        String launch = Jsonl.str(line, "launch");
        List<String> container = Jsonl.strArray(line, "container");
        if (launch == null || container.isEmpty()) return null;
        return rowKey(launch, className(container));
    }

    /**
     * The next line of {@code in} at most {@link #MAX_ROW_CHARS} long, the empty string for a longer
     * one (skipped to its end), null at the end.
     */
    static @Nullable String bounded(Reader in) throws IOException {
        StringBuilder sb = new StringBuilder();
        boolean over = false;
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') return over ? "" : sb.toString();
            if (over) continue;
            if (sb.length() >= MAX_ROW_CHARS) {
                over = true;
                sb.setLength(0);
                continue;
            }
            sb.append((char) c);
        }
        return sb.isEmpty() && !over ? null : over ? "" : sb.toString();
    }

    /** The binary name a Java container path spells: its package, then each class. */
    static String className(List<String> container) {
        if (container.size() == 1) return container.getFirst();
        String pkg = container.getFirst();
        String cls = String.join("$", container.subList(1, container.size()));
        return pkg.isEmpty() ? cls : pkg + "." + cls;
    }

    /** A Java binary name as a container path: its package, then each class, outermost first. */
    static List<String> container(String className) {
        int dot = className.lastIndexOf('.');
        List<String> out = new ArrayList<>();
        out.add(dot < 0 ? "" : className.substring(0, dot));
        for (String part : className.substring(dot + 1).split("\\$")) out.add(part);
        return out;
    }

    static String rowKey(String launch, String testClass) {
        return launch + '|' + testClass;
    }

    /**
     * One class's row: identity and outcome, what its window held (when its recording was read whole),
     * what it looked up, and with the shadow its key, its cone, why it is keyed by its module and why
     * per-class selection would have run it.
     */
    static String row(
            Outcome o, ClassReads.@Nullable Observation obs, Lookups lookups, Path root, @Nullable Shadowed shadow) {
        JsonFields f = JsonFields.object()
                .string("launch", o.launch())
                .array("container", container(o.testClass()))
                .string("outcome", o.failedNow() ? "failed" : "passed")
                .number("tests", o.tests())
                .number("failed", o.failed())
                .number("skipped", o.skipped())
                .number("ranAt", o.ranAt() > 0 ? o.ranAt() : Clock.SYSTEM.millis())
                .number("millis", o.millis())
                // A class whose recording was not read whole is attributed nothing.
                .bool("attributed", obs != null);
        if (shadow != null) {
            f.string("classKey", shadow.key())
                    .optionalString("fallback", shadow.fallback())
                    .optionalString("wouldRun", shadow.why());
        }
        if (obs != null) {
            f.array("reads", spelled(obs.reads(), root))
                    .optionalTrue("readsMore", obs.readsMore())
                    .array("code", spelled(obs.code(), root))
                    .optionalTrue("codeMore", obs.codeMore())
                    .array("processes", List.copyOf(obs.processes()))
                    .array("nativeCode", List.copyOf(obs.nativeCode()))
                    .array("osPaths", spelled(obs.osPaths(), root))
                    .array("network", List.copyOf(obs.hosts()))
                    .optionalTrue("platformBound", obs.platformBound());
        }
        Names env = lookups.env(o.testClass());
        Names props = lookups.props(o.testClass());
        f.array("env", List.copyOf(env.kept)).optionalNumber("envCount", env.count, NAMES_KEPT);
        JsonFields jvm = JsonFields.object()
                .array("props", List.copyOf(props.kept))
                .optionalNumber("propsCount", props.count, NAMES_KEPT);
        if (obs != null) jvm.number("loaded", obs.loaded().size()).optionalTrue("loadedMore", obs.loadedMore());
        if (shadow != null && shadow.cone() != null) jvm.string("cone", shadow.cone());
        return f.token("jvm", jvm.finish()).finish();
    }

    /**
     * What the shadow says of one class: its key over this run, why it is keyed by its module, its
     * cone ({@code <digest> <size>}), and why per-class selection would have run it (null: replayed).
     */
    record Shadowed(
            String key,
            @Nullable String fallback,
            @Nullable String cone,
            @Nullable String why) {}

    /** Record the classes of {@code recorded} with what {@code seen} and {@code lookups} say, no shadow. */
    static void write(
            Path moduleDir,
            String module,
            Map<String, TestClassLedger.Entry> recorded,
            ClassReads.Attributed seen,
            Lookups lookups,
            Path workspace,
            @Nullable String toolchain)
            throws IOException {
        Path root = workspace.toAbsolutePath().normalize();
        merge(
                moduleDir,
                header(module, seen, lookups, root, toolchain),
                outcomes(recorded),
                (o, prev) -> row(o, seen.complete() ? seen.classes().get(o.testClass()) : null, lookups, root, null));
    }

    /**
     * The file's first line: the run it was written by, what no class's window held, the toolchain,
     * and counts; never any class's own content.
     */
    static String header(
            String module, ClassReads.Attributed seen, Lookups lookups, Path root, @Nullable String toolchain) {
        ClassReads.Observation pooled = seen.pooled();
        boolean bound = pooled.platformBound();
        int boundClasses = 0;
        for (ClassReads.Observation o : seen.classes().values()) {
            if (o.platformBound()) boundClasses++;
        }
        Names env = lookups.env("");
        Names props = lookups.props("");
        JsonFields f = JsonFields.object()
                .string("kind", "suite")
                .string("module", module)
                .number("ranAt", Clock.SYSTEM.millis())
                .optionalString("toolchain", toolchain)
                .bool("complete", seen.complete())
                .number("jvms", seen.jvms())
                .number("events", seen.events())
                .number("classes", seen.classes().size())
                .bool("platformBound", bound || boundClasses > 0)
                .number("platformBoundClasses", boundClasses);
        JsonFields pool = JsonFields.object()
                .array("reads", spelled(pooled.reads(), root))
                .optionalTrue("readsMore", pooled.readsMore())
                .array("code", spelled(pooled.code(), root))
                .optionalTrue("codeMore", pooled.codeMore())
                .array("processes", List.copyOf(pooled.processes()))
                .array("nativeCode", List.copyOf(pooled.nativeCode()))
                .array("osPaths", spelled(pooled.osPaths(), root))
                .array("network", List.copyOf(pooled.hosts()))
                .array("env", List.copyOf(env.kept))
                .token(
                        "jvm",
                        JsonFields.object()
                                .array("props", List.copyOf(props.kept))
                                .finish());
        return f.token("pooled", pool.finish()).finish();
    }

    /** Paths in the workspace relative to it, with forward slashes; others as they are. */
    static List<String> spelled(Collection<Path> paths, Path root) {
        List<String> out = new ArrayList<>();
        for (Path p : paths) {
            out.add(p.startsWith(root) ? root.relativize(p).toString().replace('\\', '/') : p.toString());
        }
        return out;
    }

    /** The rows of the module's record file by launch and class, for tests and small files only. */
    static Map<String, String> rows(Path file) {
        Map<String, String> out = new LinkedHashMap<>();
        forEachRow(file, out::put);
        return out;
    }
}
