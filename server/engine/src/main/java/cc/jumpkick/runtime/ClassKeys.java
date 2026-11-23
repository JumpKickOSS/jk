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
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * A test class's key: what its last outcome stands on, finer than its suite's stamp. Three parts
 * (docs/contributors/per-class-tests.md):
 *
 * <ul>
 *   <li>the suite part, what every class of the suite sees: the stamp's tokens less those a finer
 *       part replaces, every classpath entry outside the workspace by content, the resource roots;
 *   <li>the class part, its static cone ({@link ClassCones});
 *   <li>the observed part, what its last run read: data files by content (with the listing of a
 *       workspace directory it read in), code files loaded by path by content, and what no class's
 *       window held.
 * </ul>
 *
 * <p>A class whose cone cannot be trusted is keyed by its whole module, every workspace class on
 * its classpath in place of its cone: one that started a program, loaded a workspace class its cone
 * does not name, names a classpath-scanning entry point, or has no attributed record.
 */
final class ClassKeys {

    private ClassKeys() {}

    /**
     * What one class was seen to read, from its record or this run, bounded as a record holds it: a
     * class that read more data files than its record names ({@code readsMore}) is keyed on every
     * file its suite was found to read instead.
     */
    record Observed(
            boolean attributed,
            List<String> reads,
            boolean readsMore,
            List<String> code,
            boolean codeMore,
            List<String> processes) {
        static final Observed NONE = new Observed(false, List.of(), false, List.of(), false, List.of());
    }

    /**
     * A class's key, and why it is its module's when it is.
     *
     * @param key the class key; {@code module:}-prefixed for a class keyed by its module
     * @param fallback why the class is keyed by its module, or null
     */
    record Key(String key, @Nullable String fallback) {}

    /** The stamp tokens a finer part replaces: the selection, the test compiles, discovered reads. */
    static boolean replacedToken(String token) {
        return token.startsWith("sel:")
                || token.startsWith("suites-run:")
                || token.startsWith(TestStamp.COMPILE_TEST)
                || token.startsWith(TestStamp.COMPILE_TEST_KOTLIN)
                || token.startsWith(TestStamp.COMPILE_TEST_GROOVY)
                || SuiteReads.isReadsToken(token);
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
     * digest), which holds the class's own.
     */
    static Key key(
            String launch,
            ClassCones.Cone cone,
            String suitePart,
            Observed seen,
            Observed pooled,
            String module,
            String suiteReads,
            Path workspace,
            @Nullable String fallback) {
        MessageDigest md = Hashing.newSha256();
        feed(md, "suite:" + suitePart);
        feed(md, "launch:" + launch);
        feed(md, fallback == null ? "cone:" + cone.digest() : "module:" + module);
        if (seen.readsMore() || pooled.readsMore()) feed(md, "suite-reads:" + suiteReads);
        if (!seen.readsMore()) feedFiles(md, "read", seen.reads(), workspace, true);
        feedFiles(md, "code", seen.code(), workspace, false);
        if (!pooled.readsMore()) feedFiles(md, "pooled-read", pooled.reads(), workspace, true);
        feedFiles(md, "pooled-code", pooled.code(), workspace, false);
        return new Key((fallback == null ? "" : "module:") + Hashing.hex(md.digest()), fallback);
    }

    /**
     * Why a class's cone cannot be trusted, or null when it can: no attributed record, a program
     * started, more code files or loaded classes than are kept, a scanning entry point in the cone,
     * or a workspace class it loaded ({@code loaded}) that the cone does not name. Decided when the
     * class ran, from what it loaded then; its record keeps the answer.
     */
    static @Nullable String fallback(
            ClassCones.Cone cone,
            Observed seen,
            Collection<String> loaded,
            boolean loadedMore,
            String testClass,
            Predicate<String> indexed) {
        if (!seen.attributed()) return "unattributed";
        if (!seen.processes().isEmpty()) return "process " + seen.processes().getFirst();
        if (seen.codeMore()) return "code past the record's bound";
        if (loadedMore) return "loads past the record's bound";
        if (cone.scanner() != null) return "scanner " + cone.scanner();
        for (String l : loaded) {
            if (!cone.contains(l)
                    && indexed.test(l)
                    && !TestClassLedger.topLevel(l).equals(testClass)) {
                return "reflective " + l;
            }
        }
        return null;
    }

    /** Each file by content, or {@code absent}; for data, the listing of a workspace directory it lies in too. */
    private static void feedFiles(
            MessageDigest md, String kind, Collection<String> spelled, Path workspace, boolean listing) {
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
            String content;
            try {
                content = FileHashMemo.contentHash(f);
            } catch (IOException notHashed) {
                content = Files.isRegularFile(f) ? "unreadable" : "absent";
            }
            feed(md, kind + ":" + spell(workspace, f) + "=" + content);
            Path parent = f.getParent();
            if (listing && parent != null && SuiteReads.keysListing(parent, workspace)) dirs.add(parent);
        }
        for (Path dir : dirs) feed(md, "list:" + spell(workspace, dir) + "=" + listing(dir));
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

    private static String spell(Path root, Path p) {
        return p.startsWith(root) ? root.relativize(p).toString().replace('\\', '/') : p.toString();
    }

    private static void feed(MessageDigest md, String value) {
        md.update((value + "\n").getBytes(StandardCharsets.UTF_8));
    }
}
