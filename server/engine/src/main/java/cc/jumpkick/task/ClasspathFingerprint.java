// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.Hashing;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Content (not path/mtime) fingerprint for cache keys: CAS path encodes the hash; local files use
 * raw content SHA ({@code file:…}); directories use a tree of the same. Packagers emit
 * byte-reproducible jars ({@code DeterministicZip}), so raw jar bytes are stable across no-op
 * rebuilds and match CAS digests seeded by {@link FileHashMemo#rememberContent} after clean→restore.
 * Missing entries become a distinct {@code missing:} token spelled with the entry's module-relative
 * path, so an absent jar is the same absence in every checkout and still distinct from its
 * neighbours.
 *
 * <p>Every walk here drops {@link BuildStamps#isStampFile stamp files}: build-host metadata that
 * lives inside the classes tree, is not code, and whose content changes every build.
 */
public final class ClasspathFingerprint {

    private ClasspathFingerprint() {}

    /** The read-only reader's memo of {@link #entry} tokens, bound only inside {@link #withEntryMemo}. */
    private static final ScopedValue<Map<Path, String>> ENTRY_MEMO = ScopedValue.newInstance();

    /**
     * Run {@code body} with {@link #entry} memoized per path.
     *
     * <p>Only for a reader that writes nothing while it runs. A forecast reads one classpath entry
     * once per module that names it — for this repository, 19,103 readings of 376 distinct entries
     * — and a directory entry is a whole tree walk, so the repetition is most of what the walk
     * costs. Nothing can move under the memo because the walk is read-only; a live build must
     * never bind it, since its steps rewrite the very trees they key on.
     *
     * <p>Bound as a {@link ScopedValue}, not a static: two jobs forecast at once in one engine, and
     * the binding reaches the threads the body structurally forks.
     */
    public static <T> T withEntryMemo(Callable<T> body) throws Exception {
        return ScopedValue.where(ENTRY_MEMO, new ConcurrentHashMap<Path, String>())
                .<T, Exception>call(body::call);
    }

    /**
     * How one classpath entry's content identity is read. {@link #ON_DISK} reads what is there;
     * a forecast supplies one that answers for a wiped tree or jar with the identity of the output
     * the build restores before it keys on it, so the two compute the same key.
     */
    @FunctionalInterface
    public interface EntryIdentity {
        String of(Path entry) throws IOException;
    }

    /** The identity of what is on disk: {@link #entry(Path)}. */
    public static final EntryIdentity ON_DISK = ClasspathFingerprint::entry;

    /** Order-independent content fingerprint of a list of classpath entries. */
    public static String of(List<Path> entries) throws IOException {
        return of(entries, ON_DISK);
    }

    /** As {@link #of(List)} with each entry's identity read through {@code identity}. */
    public static String of(List<Path> entries, EntryIdentity identity) throws IOException {
        List<String> parts = new ArrayList<>(entries.size());
        for (Path p : entries) parts.add(identity.of(p));
        parts.sort(Comparator.naturalOrder());
        return Hashing.sha256Hex(String.join("\n", parts));
    }

    /**
     * Directory fingerprint matching {@link #entry(Path)} for a classes tree that is not on disk —
     * typically the outputs map from a compile action record after {@code jk clean}. Keys are
     * forward-slash relative paths; values are content SHA-256 hex. Stamp / {@code .jk-*} scratch
     * paths are ignored like a live tree walk. {@link #entry(Path)} routes a live directory through
     * here too, so the two can never disagree about what a tree's token is.
     */
    public static String entryFromOutputDigests(Map<String, String> relPathToSha256) {
        if (relPathToSha256 == null || relPathToSha256.isEmpty()) {
            return "dir:" + Hashing.sha256Hex("");
        }
        List<String> files = new ArrayList<>();
        for (Map.Entry<String, String> e : relPathToSha256.entrySet()) {
            String rel = e.getKey().replace('\\', '/');
            if (rel.isEmpty()) continue;
            if (BuildStamps.isStampFile(rel)) continue;
            if (ActionCache.hasJkScratchSegment(Path.of(rel))) continue;
            if (e.getValue() == null || e.getValue().isBlank()) continue;
            files.add(rel + ":" + e.getValue());
        }
        files.sort(Comparator.naturalOrder());
        return "dir:" + Hashing.sha256Hex(String.join("\n", files));
    }

    /**
     * Merge compile outputs with resource-root files (as {@code copy-resources} would place them
     * under classes/) into the same {@code dir:…} token {@link #entry(Path)} produces for a live
     * tree. Resource paths overwrite compile paths on collision (copy order: compile then
     * resources).
     */
    public static String entryFromCompileAndResources(Map<String, String> compileOutputs, List<Path> resourceRoots)
            throws IOException {
        return entryFromCompileAndResources(compileOutputs, resourceRoots, Map.of());
    }

    /**
     * As above with {@code copiedFiles} — tree-relative path to content sha — laid over the result
     * last, as {@code copy-resources} places a module-root plugin manifest after the resource roots.
     */
    public static String entryFromCompileAndResources(
            Map<String, String> compileOutputs, List<Path> resourceRoots, Map<String, String> copiedFiles)
            throws IOException {
        Map<String, String> digests = new TreeMap<>();
        if (compileOutputs != null) {
            for (Map.Entry<String, String> e : compileOutputs.entrySet()) {
                String rel = e.getKey().replace('\\', '/');
                if (rel.isEmpty()) continue;
                if (BuildStamps.isStampFile(rel)) continue;
                if (ActionCache.hasJkScratchSegment(Path.of(rel))) continue;
                if (e.getValue() == null || e.getValue().isBlank()) continue;
                digests.put(rel, e.getValue());
            }
        }
        if (resourceRoots != null) {
            for (Path root : resourceRoots) {
                if (root == null || !Files.isDirectory(root)) continue;
                hashInto(root, digests, false);
            }
        }
        digests.putAll(copiedFiles);
        return entryFromOutputDigests(digests);
    }

    /**
     * Projected directory token for a live classes tree after {@code copy-resources} would merge
     * {@code resourceRoots} over it. Starts from on-disk class/non-resource files, then overlays
     * source resource roots — same content the live package step hashes once the copy has run.
     */
    public static String entryProjectedAfterResourceCopy(Path classesDir, List<Path> resourceRoots) throws IOException {
        Map<String, String> digests = new TreeMap<>();
        if (classesDir != null && Files.isDirectory(classesDir)) {
            hashInto(classesDir, digests, true);
        }
        return entryFromCompileAndResources(digests, resourceRoots);
    }

    /**
     * Memoized content digest of every regular file under {@code root}, keyed by forward-slash
     * relative path. {@code skipScratch} additionally drops {@code .jk-*} plugin scratch, which is
     * output content in a classes tree but never in a resource root.
     */
    private static void hashInto(Path root, Map<String, String> digests, boolean skipScratch) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                if (BuildStamps.isStampFile(f.getFileName().toString())) return FileVisitResult.CONTINUE;
                Path rel = root.relativize(f);
                if (skipScratch && ActionCache.hasJkScratchSegment(rel)) return FileVisitResult.CONTINUE;
                digests.put(
                        rel.toString().replace('\\', '/'),
                        FileHashMemo.contentHash(f.toAbsolutePath().normalize(), attrs));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Content identity of a single entry (CAS blob, jar, classes dir, or missing). */
    public static String entry(Path p) throws IOException {
        Path abs = p.toAbsolutePath().normalize();
        if (!ENTRY_MEMO.isBound()) return entryOf(abs);
        Map<Path, String> memo = ENTRY_MEMO.get();
        String hit = memo.get(abs);
        if (hit != null) return hit;
        String token = entryOf(abs);
        memo.put(abs, token);
        return token;
    }

    /** {@link #entry} without the memo; {@code abs} is already absolute and normalized. */
    private static String entryOf(Path abs) throws IOException {
        BasicFileAttributes attrs;
        try {
            // One attribute read answers directory / regular / missing, where three predicates
            // would each re-resolve the path.
            attrs = Files.readAttributes(abs, BasicFileAttributes.class);
        } catch (IOException absent) {
            return "missing:" + PortablePath.of(abs);
        }
        if (attrs.isDirectory()) {
            // Through entryFromOutputDigests so a live tree and the same tree read back out of an
            // action record cannot drift: one place decides what a directory token is.
            Map<String, String> digests = new TreeMap<>();
            hashInto(abs, digests, true);
            return entryFromOutputDigests(digests);
        }
        // Raw content for jars and non-jars alike: deterministic packaging makes raw stable, and
        // rememberContent can seed it for free after an action-cache restore. The memo is what
        // stops every build from re-hashing each non-CAS jar (repos/ deps, sibling module jars,
        // worker fat jars).
        if (attrs.isRegularFile()) return "file:" + FileHashMemo.contentHash(abs, attrs);
        return "missing:" + PortablePath.of(abs);
    }
}
