// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.TreeContent;
import cc.jumpkick.run.ContextPropagator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Content (not path/mtime) fingerprint for cache keys: CAS path encodes the hash; local files use
 * raw content SHA ({@code file:…}); directories use a tree of the same. Packagers emit
 * byte-reproducible jars ({@code DeterministicZip}), so raw jar bytes are stable across no-op
 * rebuilds and match CAS digests seeded by {@link FileHashMemo#rememberContent} after clean→restore.
 * Missing entries become a distinct {@code missing:} token spelled with the entry's module-relative
 * path, so an absent jar is the same absence in every checkout and still distinct from its
 * neighbours.
 *
 * <p>Every walk here takes what {@link TreeContent} calls content, the same file set the packagers
 * write and the action cache stores: stamps and {@code .jk-*} scratch directories are left out,
 * every other file — dot-files included — is in.
 */
public final class ClasspathFingerprint {

    private ClasspathFingerprint() {}

    /** The memo of {@link #entry} tokens, bound only inside {@link #withEntryMemo}. */
    private static final ScopedValue<EntryMemo> ENTRY_MEMO = ScopedValue.newInstance();

    static {
        ContextPropagator.carry(ENTRY_MEMO);
    }

    /**
     * Run {@code body} with {@link #entry} read through {@code memo}.
     *
     * <p>A forecast reads one classpath entry once per module that names it — for this repository,
     * 19,103 readings of 376 distinct entries — and a live build reads each sibling classes tree
     * once per compile that keys on it; a directory entry is a whole tree walk, so the repetition
     * is most of what the reading costs.
     *
     * <p>Bound as a {@link ScopedValue}, not a static, because two jobs can run at once in one
     * engine and must not share one. {@link ContextPropagator Carried}, so the pool lanes share it.
     */
    public static <T extends @Nullable Object> T withEntryMemo(EntryMemo memo, Callable<T> body) throws Exception {
        return ScopedValue.where(ENTRY_MEMO, memo).<T, Exception>call(body::call);
    }

    /**
     * {@link #entry} tokens remembered per path, under a policy for which entries may be.
     *
     * <p>{@link #everything} is for a reader that writes nothing while it runs, a forecast: nothing
     * can move under it. A live build rewrites the very trees it keys on, so its memo is {@link
     * #onceSealed}: an entry is remembered only when, before it is read, nothing the build still
     * runs will write it. Either way the token is the one an unmemoized read gives.
     */
    public static final class EntryMemo {
        private final Map<Path, String> tokens = new ConcurrentHashMap<>();
        private final Predicate<Path> remember;
        private final AtomicLong walks = new AtomicLong();
        private final AtomicLong dirReadings = new AtomicLong();

        private EntryMemo(Predicate<Path> remember) {
            this.remember = remember;
        }

        /** Remembers every entry it reads. */
        public static EntryMemo everything() {
            return new EntryMemo(entry -> true);
        }

        /**
         * Remembers an entry only when {@code sealed} answers true for its absolute, normalized
         * path before it is read: a reading that began while the tree could still move is not
         * kept.
         */
        public static EntryMemo onceSealed(Predicate<Path> sealed) {
            return new EntryMemo(sealed);
        }

        /** Directory trees this memo has walked; a remembered one is walked once. */
        public long walks() {
            return walks.get();
        }

        /** Directory trees read through this memo, walked or remembered: what an unmemoized reader walks. */
        public long dirReadings() {
            return dirReadings.get();
        }

        /** Entries this memo holds a token for. */
        public int remembered() {
            return tokens.size();
        }

        private String entry(Path abs) throws IOException {
            String hit = tokens.get(abs);
            if (hit != null) {
                if (hit.startsWith(DIR)) dirReadings.incrementAndGet();
                return hit;
            }
            boolean keep = remember.test(abs);
            String token = entryOf(abs);
            if (token.startsWith(DIR)) {
                walks.incrementAndGet();
                dirReadings.incrementAndGet();
            }
            if (keep) tokens.put(abs, token);
            return token;
        }
    }

    private static final String DIR = "dir:";

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
            return DIR + Hashing.sha256Hex("");
        }
        List<String> files = new ArrayList<>();
        for (Map.Entry<String, String> e : relPathToSha256.entrySet()) {
            String rel = e.getKey().replace('\\', '/');
            if (rel.isEmpty() || !TreeContent.isContent(rel)) continue;
            if (e.getValue() == null || e.getValue().isBlank()) continue;
            files.add(rel + ":" + e.getValue());
        }
        files.sort(Comparator.naturalOrder());
        return DIR + Hashing.sha256Hex(String.join("\n", files));
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
                if (rel.isEmpty() || !TreeContent.isContent(rel)) continue;
                if (e.getValue() == null || e.getValue().isBlank()) continue;
                digests.put(rel, e.getValue());
            }
        }
        if (resourceRoots != null) {
            for (Path root : resourceRoots) {
                if (root == null || !ProjectedFiles.isDirectory(root)) continue;
                hashInto(root, digests);
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
        return entryProjectedAfterResourceCopy(classesDir, resourceRoots, Set.of());
    }

    /**
     * As {@link #entryProjectedAfterResourceCopy(Path, List)}, less the tree-relative {@code
     * removed} paths: the copies of deleted resources the mirror takes out first.
     */
    public static String entryProjectedAfterResourceCopy(Path classesDir, List<Path> resourceRoots, Set<String> removed)
            throws IOException {
        Map<String, String> digests = new TreeMap<>();
        if (classesDir != null && Files.isDirectory(classesDir)) {
            hashInto(classesDir, digests);
        }
        digests.keySet().removeAll(removed);
        return entryFromCompileAndResources(digests, resourceRoots);
    }

    /**
     * Memoized content digest of every {@link TreeContent} file under {@code root}, keyed by
     * forward-slash relative path: the walk the packagers read the same tree with.
     */
    private static void hashInto(Path root, Map<String, String> digests) throws IOException {
        Map<String, String> projected = ProjectedFiles.at(root);
        if (projected != null) {
            digests.putAll(projected);
            return;
        }
        // Through PathUtil (under TreeContent) rather than a hand-rolled walkFileTree: a directory
        // read is a native call the virtual-thread scheduler does not compensate for, so a walk holds its carrier
        // until it ends. This is the largest walk a forecast runs, and the forecast now runs a
        // wave of them at once — without the helper's periodic yield they would leave every other
        // virtual thread in the engine unscheduled for as long as the wave lasts.
        TreeContent.forEach(root, (rel, f, attrs) -> {
            digests.put(rel, FileHashMemo.contentHash(f.toAbsolutePath().normalize(), attrs));
        });
    }

    /**
     * Content identity of a single entry (CAS blob, jar, classes dir, or missing). A {@link
     * ProjectedFiles} root is the tree the build lays there.
     */
    public static String entry(Path p) throws IOException {
        Path abs = p.toAbsolutePath().normalize();
        Map<String, String> projected = ProjectedFiles.at(abs);
        if (projected != null) {
            return projected.isEmpty() ? "missing:" + PortablePath.of(abs) : entryFromOutputDigests(projected);
        }
        EntryMemo memo = ContextPropagator.get(ENTRY_MEMO);
        return memo == null ? entryOf(abs) : memo.entry(abs);
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
            hashInto(abs, digests);
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
