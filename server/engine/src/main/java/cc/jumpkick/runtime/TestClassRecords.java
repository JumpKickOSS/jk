// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.test.ClassReads;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * Each test class's record of its last run in a module: its outcome and wall, and what it was seen to
 * do (files and code it read, classes it loaded, programs it started, native libraries, the operating
 * system's own files, hosts, environment variables and system properties by name), attributed to it
 * by its window in the recording ({@link ClassReads}). Kept in jk's cache for the project, one file
 * per module, beside the suite's discovered inputs; a class that ran replaces its row, a class that
 * did not keeps the row of the run it last ran in.
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

    /**
     * What the run's JVMs looked up, by top-level test class: environment variables from {@code
     * <pid>.lookups}, system properties from {@code <pid>.props}. A lookup no class was running for
     * is filed under the empty name, the pool.
     */
    record Lookups(Map<String, Set<String>> env, Map<String, Set<String>> props) {
        static Lookups read(Path dir) {
            Map<String, Set<String>> env = new TreeMap<>();
            Map<String, Set<String>> props = new TreeMap<>();
            try {
                PathUtil.forEachRegularFile(dir, (file, attrs) -> {
                    String name = file.getFileName().toString();
                    boolean prop = name.endsWith(".props");
                    if (!prop && !name.endsWith(".lookups")) return;
                    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                        String[] f = line.split("\t", -1);
                        if (prop && f.length == 3) add(props, f[0], f[1]);
                        else if (!prop && f.length == 4) add(env, f[0], f[2]);
                    }
                });
            } catch (IOException | RuntimeException unreadable) {
                // what the probe could not finish is a record not given
            }
            return new Lookups(env, props);
        }

        private static void add(Map<String, Set<String>> into, String test, String name) {
            into.computeIfAbsent(TestClassLedger.topLevel(test), t -> new TreeSet<>())
                    .add(name);
        }

        Set<String> env(String testClass) {
            return env.getOrDefault(testClass, Set.of());
        }

        Set<String> props(String testClass) {
            return props.getOrDefault(testClass, Set.of());
        }
    }

    /** One top-level class's outcome in one launch, its nested classes folded in. */
    private record Outcome(
            String launch, String testClass, long tests, long failed, long skipped, long ranAt, long millis) {}

    /**
     * Record every class {@code recorded} holds (the class ledger's outcomes of this run, by launch and
     * class), with what {@code seen} attributes to it and what {@code lookups} says it looked up,
     * over the module's earlier rows. Best-effort: a record not written is a record not given.
     */
    static void write(
            Path moduleDir,
            String module,
            Map<String, TestClassLedger.Entry> recorded,
            ClassReads.Attributed seen,
            Lookups lookups,
            Path workspace,
            @Nullable String toolchain) {
        if (recorded.isEmpty()) return;
        Path file = file(moduleDir);
        Map<String, String> rows = new TreeMap<>(previousRows(file));
        Path root = workspace.toAbsolutePath().normalize();
        for (Outcome o : outcomes(recorded)) {
            ClassReads.Observation obs = seen.complete() ? seen.classes().get(o.testClass()) : null;
            rows.put(rowKey(o.launch(), o.testClass()), row(o, obs, lookups, root));
        }
        StringBuilder sb = new StringBuilder(header(module, seen, lookups, root, toolchain)).append('\n');
        for (String row : rows.values()) sb.append(row).append('\n');
        try {
            Files.createDirectories(file.getParent());
            AtomicWrites.replace(file, sb.toString());
        } catch (IOException | RuntimeException unwritable) {
            Log.debug("test class records: not kept", unwritable);
        }
    }

    /** The rows of the module's record file by launch and class; none when it is absent or unreadable. */
    static Map<String, String> previousRows(Path file) {
        Map<String, String> out = new TreeMap<>();
        if (!Files.isRegularFile(file)) return out;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank() || "suite".equals(Jsonl.str(line, "kind"))) continue;
                String launch = Jsonl.str(line, "launch");
                List<String> container = Jsonl.strArray(line, "container");
                if (launch == null || container.isEmpty()) continue;
                out.put(rowKey(launch, className(container)), line);
            }
        } catch (IOException | RuntimeException unreadable) {
            return new TreeMap<>();
        }
        return out;
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

    private static String rowKey(String launch, String testClass) {
        return launch + '|' + testClass;
    }

    /** {@code recorded}'s outcomes by top-level class and launch: counts summed, the latest run, the longest wall. */
    private static List<Outcome> outcomes(Map<String, TestClassLedger.Entry> recorded) {
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
        return List.copyOf(by.values());
    }

    private static String row(Outcome o, ClassReads.@Nullable Observation obs, Lookups lookups, Path root) {
        JsonFields f = JsonFields.object()
                .string("launch", o.launch())
                .array("container", container(o.testClass()))
                .string("outcome", o.failed() > 0 ? "failed" : "passed")
                .number("tests", o.tests())
                .number("failed", o.failed())
                .number("skipped", o.skipped())
                .number("ranAt", o.ranAt() > 0 ? o.ranAt() : Clock.SYSTEM.millis())
                .number("millis", o.millis())
                // A class whose recording was not read whole is attributed nothing.
                .bool("attributed", obs != null);
        if (obs != null) {
            f.array("reads", spelled(obs.reads(), root))
                    .array("code", spelled(obs.code(), root))
                    .array("processes", List.copyOf(obs.processes()))
                    .array("nativeCode", List.copyOf(obs.nativeCode()))
                    .array("osPaths", spelled(obs.osPaths(), root))
                    .array("network", List.copyOf(obs.hosts()));
        }
        f.array("env", List.copyOf(lookups.env(o.testClass())));
        JsonFields jvm = JsonFields.object().array("props", List.copyOf(lookups.props(o.testClass())));
        if (obs != null) jvm.array("loaded", List.copyOf(obs.loaded()));
        return f.token("jvm", jvm.finish()).finish();
    }

    /** The file's first line: the run it was written by, what no class's window held, and the toolchain. */
    private static String header(
            String module, ClassReads.Attributed seen, Lookups lookups, Path root, @Nullable String toolchain) {
        ClassReads.Observation pooled = seen.pooled();
        boolean bound = pooled.platformBound();
        for (ClassReads.Observation o : seen.classes().values()) bound |= o.platformBound();
        JsonFields f = JsonFields.object()
                .string("kind", "suite")
                .string("module", module)
                .number("ranAt", Clock.SYSTEM.millis())
                .optionalString("toolchain", toolchain)
                .bool("complete", seen.complete())
                .number("jvms", seen.jvms())
                .number("events", seen.events())
                .bool("platformBound", bound);
        JsonFields pool = JsonFields.object()
                .array("reads", spelled(pooled.reads(), root))
                .array("code", spelled(pooled.code(), root))
                .array("processes", List.copyOf(pooled.processes()))
                .array("nativeCode", List.copyOf(pooled.nativeCode()))
                .array("osPaths", spelled(pooled.osPaths(), root))
                .array("network", List.copyOf(pooled.hosts()))
                .array("env", List.copyOf(lookups.env("")))
                .token(
                        "jvm",
                        JsonFields.object()
                                .array("props", List.copyOf(lookups.props("")))
                                .array("loaded", List.copyOf(pooled.loaded()))
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
}
