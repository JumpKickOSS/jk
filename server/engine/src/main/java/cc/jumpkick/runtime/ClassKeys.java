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

    /** What one class was seen to read, from its record or this run. */
    record Observed(
            boolean attributed, List<String> reads, List<String> code, List<String> processes, Set<String> loaded) {
        static final Observed NONE = new Observed(false, List.of(), List.of(), List.of(), Set.of());
    }

    /**
     * A class's key, and why it is its module's when it is.
     *
     * @param key the class key; {@code module:}-prefixed for a class keyed by its module
     * @param fallback why the class is keyed by its module, or null
     */
    record Key(String key, @Nullable String fallback) {}

    /** The stamp tokens a finer part replaces: the selection, the test compiles, discovered reads, worker jars. */
    static boolean replacedToken(String token) {
        return token.startsWith("sel:")
                || token.startsWith("suites-run:")
                || token.startsWith(TestStamp.COMPILE_TEST)
                || token.startsWith(TestStamp.COMPILE_TEST_KOTLIN)
                || token.startsWith(TestStamp.COMPILE_TEST_GROOVY)
                || token.startsWith("worker:")
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
     * The key of {@code testClass} in {@code launch}, over {@code cone}, what it was seen to do and
     * what no class's window held ({@code pooled}); over every workspace class ({@code module}, the
     * digest of them all) with the reason when the cone cannot be trusted.
     */
    static Key key(
            String testClass,
            String launch,
            ClassCones.Cone cone,
            String suitePart,
            Observed seen,
            Observed pooled,
            String module,
            Predicate<String> indexed,
            Path workspace) {
        return key(
                launch,
                cone,
                suitePart,
                seen,
                pooled,
                module,
                indexed,
                workspace,
                fallback(cone, seen, testClass, indexed));
    }

    /**
     * As above with {@code fallback} decided: the key a class's record was stored under is taken again
     * with the fallback that record says, since whether a class it loaded lay in its cone is a fact of
     * the run that loaded it, and a cone that has moved since moves the key anyway.
     */
    static Key key(
            String launch,
            ClassCones.Cone cone,
            String suitePart,
            Observed seen,
            Observed pooled,
            String module,
            Predicate<String> indexed,
            Path workspace,
            @Nullable String fallback) {
        MessageDigest md = Hashing.newSha256();
        feed(md, "suite:" + suitePart);
        feed(md, "launch:" + launch);
        feed(md, fallback == null ? "cone:" + cone.digest() : "module:" + module);
        // A class it loaded that is gone (renamed, deleted) moved under it, whatever the cone says.
        for (String loaded : seen.loaded()) if (!indexed.test(loaded)) feed(md, "gone:" + loaded);
        feedFiles(md, "read", seen.reads(), workspace, true);
        feedFiles(md, "code", seen.code(), workspace, false);
        feedFiles(md, "pooled-read", pooled.reads(), workspace, true);
        feedFiles(md, "pooled-code", pooled.code(), workspace, false);
        return new Key((fallback == null ? "" : "module:") + Hashing.hex(md.digest()), fallback);
    }

    /**
     * Why a class's cone cannot be trusted, or null when it can. A class it loaded outside its cone
     * says the cone missed something, unless the class is gone from the workspace ({@code indexed}),
     * which moves the key by itself.
     */
    static @Nullable String fallback(ClassCones.Cone cone, Observed seen, String testClass, Predicate<String> indexed) {
        if (!seen.attributed()) return "unattributed";
        if (!seen.processes().isEmpty()) return "process " + seen.processes().getFirst();
        if (cone.scanner() != null) return "scanner " + cone.scanner();
        for (String loaded : seen.loaded()) {
            if (!cone.classes().contains(loaded)
                    && indexed.test(loaded)
                    && !TestClassLedger.topLevel(loaded).equals(testClass)) {
                return "reflective " + loaded;
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
