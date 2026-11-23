// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.task.ClasspathFingerprint;
import cc.jumpkick.task.FileHashMemo;
import cc.jumpkick.task.TestStamp;
import cc.jumpkick.test.ClassCones;
import cc.jumpkick.test.ClassReads;
import cc.jumpkick.test.SuiteEnvReads;
import cc.jumpkick.test.SuiteReads;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * A test class's key: what its last outcome stands on, finer than its suite's stamp. Three parts
 * (docs/contributors/per-class-tests.md):
 *
 * <ul>
 *   <li>the suite part, what every class of the suite sees: the stamp's tokens less those a finer
 *       part replaces (what the suite read of the caller's environment among those kept), every
 *       classpath entry outside the workspace by content, the resource roots;
 *   <li>the class part, its static cone ({@link ClassCones});
 *   <li>the observed part, what its last run read: data files by content (with the listing of a
 *       workspace directory it read in), code files loaded by path by content, the identity of each
 *       program it started that read nothing else ({@link ClassReads.Observation#tools}), and what no
 *       class's window held.
 * </ul>
 *
 * <p>A workspace class the class loaded that its static cone does not name (one loaded by name, a
 * JUnit extension a properties file names) joins its cone with what it reaches, up to {@link
 * #REFLECTIVE_KEPT} of them. A class whose cone cannot be trusted is keyed by its whole module,
 * every workspace class on its classpath in place of its cone, and by every program the suite's
 * stamp keys: one that started a program that could read what no recording sees, loaded more
 * workspace classes outside its cone than are kept, names a classpath-scanning entry point, or has
 * no attributed record. A class that read more code files than its record names stands on every
 * code file its suite read instead (the suite code files of its record file's header), and on its
 * module only when the suite read more than that list holds.
 */
final class ClassKeys {

    private ClassKeys() {}

    /**
     * What one class was seen to read, from its record or this run, bounded as a record holds it: a
     * class that read more data files than its record names ({@code readsMore}) is keyed on every
     * file its suite was found to read instead. {@code tools} are the programs it started that read
     * nothing but their own identity: by name as a record holds them, by identity once {@link
     * #identified}.
     */
    record Observed(
            boolean attributed,
            List<String> reads,
            boolean readsMore,
            List<String> code,
            boolean codeMore,
            List<String> processes,
            List<String> tools) {
        static final Observed NONE = new Observed(false, List.of(), false, List.of(), false, List.of());

        /** A class that started no program keyed by its identity. */
        Observed(
                boolean attributed,
                List<String> reads,
                boolean readsMore,
                List<String> code,
                boolean codeMore,
                List<String> processes) {
            this(attributed, reads, readsMore, code, codeMore, processes, List.of());
        }
    }

    /** What a tool's identity reads when the suite's stamp names no program of its name. */
    static final String UNSTAMPED = "unstamped";

    /**
     * Each program the suite's stamp keys, by name: its {@code proc:<name>=<identity>} token. The
     * stamp asks each program the suite was seen to start what it is ({@code SuitePrograms}); a
     * class's tools stand on that answer.
     */
    static Map<String, String> programs(List<String> stampExtras) {
        Map<String, String> out = new TreeMap<>();
        for (String t : stampExtras) {
            if (!t.startsWith(PROGRAM)) continue;
            int eq = t.indexOf('=');
            if (eq > PROGRAM.length()) out.put(t.substring(PROGRAM.length(), eq), t);
        }
        return out;
    }

    private static final String PROGRAM = "proc:";

    /**
     * {@code seen} with each tool named by its identity in {@code programs}: a tool the stamp names
     * no program for reads as {@link #UNSTAMPED}, which a stamp that names it later moves, so the
     * class runs once more rather than replay over an identity nobody asked.
     */
    static Observed identified(Observed seen, Map<String, String> programs) {
        if (seen.tools().isEmpty()) return seen;
        List<String> named = new ArrayList<>();
        for (String tool : seen.tools()) {
            // A program the test wrote is its own output: the class's cone holds what it is.
            if (tool.equals(ClassReads.WRITTEN_PROGRAM)) named.add(PROGRAM + tool);
            else named.add(programs.getOrDefault(tool, PROGRAM + tool + "=" + UNSTAMPED));
        }
        named.sort(null);
        return new Observed(
                seen.attributed(),
                seen.reads(),
                seen.readsMore(),
                seen.code(),
                seen.codeMore(),
                seen.processes(),
                named);
    }

    /**
     * A class's key, and why it is its module's when it is.
     *
     * @param key the class key; {@code module:}-prefixed for a class keyed by its module
     * @param fallback why the class is keyed by its module, or null
     */
    record Key(String key, @Nullable String fallback) {}

    /**
     * The stamp tokens a finer part replaces: the test compiles (the cone), the files and listings the
     * suite read and its use of the network (the observed part), each program's identity (which {@link
     * #key} keys a class by as a tool, or when it is its module's). The selection stays: which suites
     * and tags run decides which of a class's methods run, so a class that passed in a run of the fast
     * tier says nothing of its methods a wider run selects. What the suite read of the caller's
     * environment stays too: no record says which class read it.
     */
    static boolean replacedToken(String token) {
        return token.startsWith(TestStamp.COMPILE_TEST)
                || token.startsWith(TestStamp.COMPILE_TEST_KOTLIN)
                || token.startsWith(TestStamp.COMPILE_TEST_GROOVY)
                || (SuiteReads.isReadsToken(token) && !SuiteEnvReads.isToken(token));
    }

    /** The suite part: the kept stamp tokens, the external classpath and the resource roots, as one digest. */
    static String suitePart(List<String> stampExtras, List<Path> externalClasspath, List<Path> resourceRoots)
            throws IOException {
        List<String> kept = new ArrayList<>();
        for (String t : stampExtras) if (!replacedToken(t)) kept.add(t);
        kept.sort(null);
        MessageDigest md = Hashing.newSha256();
        for (String t : kept) feed(md, "x:" + t);
        feed(md, "cp:" + ClasspathFingerprint.of(externalClasspath));
        List<Path> res = new ArrayList<>();
        for (Path r : resourceRoots) if (Files.isDirectory(r)) res.add(r);
        res.sort(null);
        feed(md, "res:" + ClasspathFingerprint.of(res));
        return Hashing.hex(md.digest());
    }

    /**
     * The key of a class in {@code launch}: the suite part, {@code cone}'s digest, or with a {@code
     * fallback} every workspace class ({@code module}, the digest of them all), and what it and the
     * pool ({@code pooled}) were seen to read. A read set past its record's bound is keyed as every
     * file the suite was found to read ({@code suiteReads}, the suite's discovered inputs as one
     * digest), which holds the class's own. A class keyed by its module stands on every program the
     * suite's stamp keys ({@code programs}, from {@link #programs}): no record says what a program it
     * started read, nor which. {@code listings} holds each directory's listing once it is taken, for
     * the caller to share across the classes of one pass.
     */
    static Key key(
            String launch,
            ClassCones.Cone cone,
            String suitePart,
            Observed seen,
            Map<String, String> programs,
            Observed pooled,
            String module,
            String suiteReads,
            Path workspace,
            @Nullable String fallback,
            Map<Path, String> listings) {
        MessageDigest md = Hashing.newSha256();
        feed(md, "suite:" + suitePart);
        feed(md, "launch:" + launch);
        feed(md, fallback == null ? "cone:" + cone.digest() : "module:" + module);
        if (fallback != null) for (String program : programs.values()) feed(md, "program:" + program);
        if (seen.readsMore() || pooled.readsMore()) feed(md, "suite-reads:" + suiteReads);
        if (!seen.readsMore()) feedFiles(md, "read", seen.reads(), workspace, listings);
        feedFiles(md, "code", seen.code(), workspace, null);
        for (String tool : seen.tools()) feed(md, "tool:" + tool);
        if (!pooled.readsMore()) feedFiles(md, "pooled-read", pooled.reads(), workspace, listings);
        feedFiles(md, "pooled-code", pooled.code(), workspace, null);
        return new Key((fallback == null ? "" : "module:") + Hashing.hex(md.digest()), fallback);
    }

    /** The most workspace classes outside its static cone a class may load and keep its cone. */
    static final int REFLECTIVE_KEPT = 8;

    /**
     * The workspace classes {@code testClass} loaded ({@code loaded}) that its static cone does not
     * reach, fewest first: each one the cone grown so far does not hold joins it with what it reaches
     * ({@link ClassCones.Index#cone(String, Collection)}). Null past {@link #REFLECTIVE_KEPT}.
     */
    static @Nullable List<String> reflective(ClassCones.Index index, String testClass, Collection<String> loaded) {
        List<String> roots = new ArrayList<>();
        ClassCones.Cone cone = index.cone(testClass);
        for (String l : new TreeSet<>(loaded)) {
            if (cone.contains(l)
                    || !index.contains(l)
                    || TestClassLedger.topLevel(l).equals(testClass)) continue;
            if (roots.size() == REFLECTIVE_KEPT) return null;
            roots.add(l);
            cone = index.cone(testClass, roots);
        }
        return roots;
    }

    /**
     * Why a class's cone cannot be trusted, or null when it can: no attributed record, a program
     * started, more code files than its suite's list holds ({@code suiteCodeMore}), more loaded
     * classes than are kept, more workspace classes loaded outside its static cone than {@link
     * #reflective} keeps ({@code reflectiveMore}), or a scanning entry point in the cone. Decided
     * when the class ran, from what it loaded then; its record keeps the answer.
     */
    static @Nullable String fallback(
            ClassCones.Cone cone, Observed seen, boolean loadedMore, boolean reflectiveMore, boolean suiteCodeMore) {
        if (!seen.attributed()) return "unattributed";
        if (!seen.processes().isEmpty()) return "process " + seen.processes().getFirst();
        if (seen.codeMore() && suiteCodeMore) return "code past the suite's bound";
        if (loadedMore) return "loads past the record's bound";
        if (reflectiveMore) return "reflective loads past the record's bound";
        if (cone.scanner() != null) return "scanner " + cone.scanner();
        return null;
    }

    /**
     * Each file by content, or {@code absent}; for data ({@code listings} not null), the listing of a
     * workspace directory it lies in too, taken once into {@code listings}.
     */
    private static void feedFiles(
            MessageDigest md,
            String kind,
            Collection<String> spelled,
            Path workspace,
            @Nullable Map<Path, String> listings) {
        Set<Path> files = new TreeSet<>();
        for (String s : spelled) {
            try {
                files.add(workspace.resolve(s).toAbsolutePath().normalize());
            } catch (InvalidPathException odd) {
                feed(md, kind + ":" + s + "=odd");
            }
        }
        Set<Path> dirs = new TreeSet<>();
        for (Path f : files) {
            feed(md, kind + ":" + spell(workspace, f) + "=" + content(f));
            Path parent = f.getParent();
            if (listings != null && parent != null && SuiteReads.keysListing(parent, workspace)) dirs.add(parent);
        }
        if (listings == null) return;
        for (Path dir : dirs)
            feed(md, "list:" + spell(workspace, dir) + "=" + listings.computeIfAbsent(dir, ClassKeys::listing));
    }

    /** A file's content as a key holds it: its digest, or {@code absent} or {@code unreadable}. */
    static String content(Path file) {
        try {
            return FileHashMemo.contentHash(file);
        } catch (IOException notHashed) {
            return Files.isRegularFile(file) ? "unreadable" : "absent";
        }
    }

    private static String listing(Path dir) {
        if (!Files.isDirectory(dir)) return "absent";
        Set<String> names = new TreeSet<>();
        // A module directory's build output comes and goes with the build, as the suite's listing has it.
        String output = Files.isRegularFile(ManifestPaths.manifestIn(dir)) ? BuildLayout.TARGET : "";
        try {
            PathUtil.forEachChild(dir, (child, attrs) -> {
                String name = String.valueOf(child.getFileName());
                if (!name.equals(output)) names.add(name);
                return true;
            });
        } catch (IOException unreadable) {
            return "unreadable";
        }
        return Hashing.sha256Hex(String.join("\n", names));
    }

    /** {@code p} relative to {@code root} with forward slashes when it lies in it, else as it is. */
    static String spell(Path root, Path p) {
        return p.startsWith(root) ? root.relativize(p).toString().replace('\\', '/') : p.toString();
    }

    private static void feed(MessageDigest md, String value) {
        md.update((value + "\n").getBytes(StandardCharsets.UTF_8));
    }
}
