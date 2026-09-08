// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.layout;

import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.config.RequestScope;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.lock.ManifestPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Per-job input-tree snapshot. One covering {@code walkFileTree} of {@code src/} (compact: also
 * {@code test/} and sibling suite dirs) answers nested collects as prefix filters.
 *
 * <p>Retained bytes are live, not reserved: a job may grow until {@code min(vfs-max-mb, process
 * pool remaining)}. The process pool is 75% of {@code max-heap-mb}. A job that finds the pool
 * empty at first retain is sticky stream-only. Never {@code target/}, whose one carve-out is the
 * declared scratch root inside it ({@code tmp/}) — see {@link #isBuildOutput}.
 */
public final class InputTrees {

    private InputTrees() {}

    private static final Object TABLE = new Object();

    private static final AtomicLong POOL_USED = new AtomicLong();

    private static volatile LastJob LAST = LastJob.EMPTY;

    /** Override for tests; {@code null} means {@link JkEngineConfig#resolve()}. */
    private static volatile @Nullable JkEngineConfig testConfig;

    /** Test seam: when set, a covering walk fails as if the tree could not be listed. */
    static volatile @Nullable IOException loadFailureForTest;

    public static Snapshot of(Path root) {
        return of(root, WalkSkip.ID_NONE);
    }

    public static Snapshot of(Path root, String skipId) {
        Path abs = root.toAbsolutePath().normalize();
        // Off a request there is no finishJob to release retained bytes, so never retain or
        // charge — live-walk, which is what every pre-VFS caller did anyway.
        if (!RequestScope.hasRequest()) return live(abs, skipId);
        // Build output is never in the VFS (vfs.md): this job writes it, so a listing memoized at
        // one step is stale by the next. A generated-source root reached through the request
        // collectors (the groovyc compile set walks KSP and plugin-contributed roots under target/)
        // is answered live on every ask — never retained, never charged, never memoized.
        if (isBuildOutput(abs)) return new BuildOutput(abs, skipId);
        Table table = table();
        Key key = new Key(abs, skipId);
        Snapshot hit = table.byKey.get(key);
        if (hit != null) {
            table.hits.incrementAndGet();
            return hit;
        }
        // The whole miss path holds the table's monitor: one covering root is one walk and one
        // pool charge no matter how many of a job's threads ask, and bytes/nodes/streamOnly need
        // no finer discipline. Hits above stay lock-free.
        synchronized (table) {
            hit = table.byKey.get(key);
            if (hit != null) {
                table.hits.incrementAndGet();
                return hit;
            }
            decideStream(table);
            // covering holds Listing keys only, so an ancestor is a Listing or nothing.
            Snapshot ancestor = ancestor(table, abs, skipId);
            if (ancestor instanceof Listing listing) {
                Listing prefix = listing.prefix(abs);
                table.byKey.put(key, prefix);
                table.hits.incrementAndGet();
                return prefix;
            }
            table.misses.incrementAndGet();
            Snapshot loaded = load(table, abs, skipId);
            table.byKey.put(key, loaded);
            if (loaded instanceof Listing) {
                table.covering.add(key);
            }
            return loaded;
        }
    }

    /**
     * Snapshot layout covering roots for {@code moduleDir}. Traditional: {@code src/} only.
     * Compact: {@code src/}, {@code test/}, and sibling suite dirs without {@code TestSuites.discover}.
     */
    public static void coverModule(Path moduleDir) {
        Path mod = moduleDir.toAbsolutePath().normalize();
        of(mod.resolve("src"));
        if (!ModuleLayout.isCompact(mod)) return;
        of(mod.resolve("test"));
        try {
            PathUtil.forEachChild(mod, (child, attrs) -> {
                if (!attrs.isDirectory()) return true;
                Path name = child.getFileName();
                if (name == null) return true;
                String n = name.toString();
                // Exactly the sibling filter TestSuites.discover applies. Anything else —
                // .github/, Demo/, website assets — is never queried as a suite, so covering it
                // would spend retain budget (and possibly flip the job stream-only) on a tree no
                // collector reads.
                if (!TestSuites.isSuiteName(n) || TestSuites.SIMPLE_RESERVED.contains(n.toLowerCase(Locale.ROOT))) {
                    return true;
                }
                // Discovery reads <suite>/src and nothing else of the sibling, so that is what is
                // covered: a workspace root's children (clients/, server/ — 70k files of Gradle output
                // and node_modules between them on jk's own tree) are suite names too, and covering
                // the whole child listed every one of those files into the job's snapshot.
                of(child.resolve("src"));
                return true;
            });
        } catch (IOException ignored) {
            // cover is best-effort; collectors still live-walk
        }
    }

    /** Copy this job's counters to last-job and return its bytes to the process pool. */
    public static void finishJob() {
        if (!RequestScope.hasRequest()) return;
        Table table = table();
        long released;
        synchronized (table) {
            LAST = new LastJob(
                    config().vfsMaxMb(),
                    poolMaxBytes(),
                    POOL_USED.get(),
                    table.nodes,
                    table.bytes,
                    table.walks.get(),
                    table.hits.get(),
                    table.misses.get(),
                    table.streamOnly);
            released = table.bytes;
            // The listings die with the job whether or not the scope object does: a pooled thread that
            // inherited this request's ledger would otherwise keep every FileRef alive for the engine's life.
            table.byKey.clear();
            table.covering.clear();
            table.bytes = 0;
            // Nothing may retain against a released table: a straggler still holding the
            // ambient ledger (a lane outliving the runner on cancel) would charge the pool with
            // no finishJob left to give it back.
            table.streamOnly = true;
        }
        if (released > 0) POOL_USED.addAndGet(-released);
    }

    /** Last finished job's VFS JSON object body, or empty. */
    public static String lastStatusJson() {
        LastJob j = LAST;
        if (j == LastJob.EMPTY) return "";
        return "{\"maxMb\":"
                + j.maxMb
                + ",\"poolMaxMb\":"
                + (j.poolMaxBytes / (1024 * 1024))
                + ",\"poolUsedMb\":"
                + (j.poolUsedBytes / (1024 * 1024))
                + ",\"nodes\":"
                + j.nodes
                + ",\"bytes\":"
                + j.bytes
                + ",\"walks\":"
                + j.walks
                + ",\"hits\":"
                + j.hits
                + ",\"misses\":"
                + j.misses
                + ",\"streamOnly\":"
                + j.streamOnly
                + "}";
    }

    /** Additive proto-1: splice last-job {@code vfs} into a {@code status-ack} JSON object. */
    public static String appendToStatusAck(String ack) {
        String vfs = lastStatusJson();
        if (vfs.isEmpty()) return ack;
        return Jsonl.append(ack, "\"vfs\":" + vfs);
    }

    public static void configureForTest(JkEngineConfig config) {
        testConfig = config;
        POOL_USED.set(0);
        LAST = LastJob.EMPTY;
    }

    public static void resetForTest() {
        testConfig = null;
        loadFailureForTest = null;
        POOL_USED.set(0);
        LAST = LastJob.EMPTY;
        RequestScope.clearAll();
    }

    public static long poolUsedBytes() {
        return POOL_USED.get();
    }

    /** Process pool ceiling: 75% of {@code max-heap-mb}. */
    public static long poolMaxBytes() {
        int heapMb = config().maxHeapMb();
        if (heapMb <= 0) heapMb = JkEngineConfig.DEFAULT_MAX_HEAP_MB;
        return (heapMb * 1024L * 1024L) * 3 / 4;
    }

    /** Remaining bytes this job may still retain ({@code min(per-job remaining, pool remaining)}). */
    public static long growLimitBytes() {
        if (!RequestScope.hasRequest()) return 0;
        Table table = table();
        synchronized (table) {
            decideStream(table);
            if (table.streamOnly) return 0;
            long jobCap = config().vfsMaxMb() * 1024L * 1024L;
            return Math.max(0L, Math.min(jobCap - table.bytes, poolMaxBytes() - POOL_USED.get()));
        }
    }

    /** Test seam: pretend the process pool is exhausted. */
    public static void fillPoolForTest() {
        POOL_USED.set(poolMaxBytes());
    }

    public sealed interface Snapshot {
        Path root();

        String skipId();

        List<FileRef> files();

        List<Path> withExtension(String ext);

        /** Files whose name ends with any of {@code exts}, in walk order — one pass, always. */
        List<Path> withExtensions(String... exts);

        boolean anyExtension(String ext);

        boolean overflow();
    }

    public record FileRef(Path path, String name, long size, long mtimeMillis, long mtimeNanos) {
        static FileRef from(Path file, BasicFileAttributes attrs) {
            return new FileRef(
                    file.toAbsolutePath().normalize(),
                    file.getFileName().toString(),
                    attrs.size(),
                    attrs.lastModifiedTime().toMillis(),
                    attrs.lastModifiedTime().to(TimeUnit.NANOSECONDS));
        }

        long estimateBytes() {
            return 48L + (long) path.toString().length() * 2L;
        }
    }

    public record Listing(Path root, String skipId, List<FileRef> files) implements Snapshot {
        Listing prefix(Path subRoot) {
            Path abs = subRoot.toAbsolutePath().normalize();
            List<FileRef> sub = new ArrayList<>();
            for (FileRef f : files) {
                if (f.path.startsWith(abs)) sub.add(f);
            }
            return new Listing(abs, skipId, List.copyOf(sub));
        }

        @Override
        public List<Path> withExtension(String ext) {
            @SuppressWarnings("unchecked")
            List<Path> cached = (List<Path>) RequestScope.current().get(new ExtMemo(root, skipId, List.of(ext)), k -> {
                List<Path> out = new ArrayList<>();
                for (FileRef f : files) {
                    if (f.name.endsWith(ext)) out.add(f.path);
                }
                return List.copyOf(out);
            });
            return cached;
        }

        @Override
        public List<Path> withExtensions(String... exts) {
            List<String> key = List.of(exts);
            @SuppressWarnings("unchecked")
            List<Path> cached = (List<Path>) RequestScope.current().get(new ExtMemo(root, skipId, key), k -> {
                List<Path> out = new ArrayList<>();
                for (FileRef f : files) {
                    if (endsWithAny(f.name, exts)) out.add(f.path);
                }
                return List.copyOf(out);
            });
            return cached;
        }

        @Override
        public boolean anyExtension(String ext) {
            for (FileRef f : files) {
                if (f.name.endsWith(ext)) return true;
            }
            return false;
        }

        @Override
        public boolean overflow() {
            return false;
        }
    }

    /**
     * The streaming shape. Queries walk live, but each distinct question is still answered once
     * per request — a memoized path list is exactly what the pre-VFS collectors kept, and it is
     * the attribute tree the retain budget bounds, not these.
     */
    public record Overflow(Path root, String skipId) implements Snapshot {
        @Override
        public List<FileRef> files() {
            return List.of();
        }

        @Override
        public List<Path> withExtension(String ext) {
            @SuppressWarnings("unchecked")
            List<Path> cached = (List<Path>) RequestScope.current()
                    .get(
                            new ExtMemo(root, skipId, List.of(ext)),
                            k -> livePaths(root, skipId, p -> p.getFileName()
                                    .toString()
                                    .endsWith(ext)));
            return cached;
        }

        @Override
        public List<Path> withExtensions(String... exts) {
            @SuppressWarnings("unchecked")
            List<Path> cached = (List<Path>) RequestScope.current()
                    .get(
                            new ExtMemo(root, skipId, List.of(exts)),
                            k -> livePaths(
                                    root,
                                    skipId,
                                    p -> endsWithAny(p.getFileName().toString(), exts)));
            return cached;
        }

        @Override
        public boolean anyExtension(String ext) {
            return RequestScope.current().get(new AnyMemo(root, skipId, ext), k -> {
                try {
                    return PathUtil.anyRegularFile(root, skipPred(skipId), p -> p.getFileName()
                            .toString()
                            .endsWith(ext));
                } catch (IOException e) {
                    return false;
                }
            });
        }

        @Override
        public boolean overflow() {
            return true;
        }
    }

    /**
     * The build-output shape: every query walks live and nothing is memoized, because the job
     * asking is the job writing. {@link Overflow} memoizes each question per request; here the
     * same question at two steps legitimately has two answers.
     */
    private record BuildOutput(Path root, String skipId) implements Snapshot {
        @Override
        public List<FileRef> files() {
            return List.of();
        }

        @Override
        public List<Path> withExtension(String ext) {
            return livePaths(root, skipId, p -> p.getFileName().toString().endsWith(ext));
        }

        @Override
        public List<Path> withExtensions(String... exts) {
            return livePaths(root, skipId, p -> endsWithAny(p.getFileName().toString(), exts));
        }

        @Override
        public boolean anyExtension(String ext) {
            try {
                return PathUtil.anyRegularFile(
                        root, skipPred(skipId), p -> p.getFileName().toString().endsWith(ext));
            } catch (IOException e) {
                return false;
            }
        }

        @Override
        public boolean overflow() {
            return true;
        }
    }

    static final class WalkLimitExceeded extends IOException {
        WalkLimitExceeded() {
            super("input tree exceeded vfs-max-mb");
        }
    }

    private record Key(Path root, String skipId) {}

    /** Memo key for a per-request extension query ({@code exts} in ask order). */
    private record ExtMemo(Path root, String skipId, List<String> exts) {}

    /** Memo key for a per-request existence probe. */
    private record AnyMemo(Path root, String skipId, String ext) {}

    private static boolean endsWithAny(String name, String... exts) {
        for (String ext : exts) {
            if (name.endsWith(ext)) return true;
        }
        return false;
    }

    /**
     * One job's memo. {@code byKey} reads are lock-free; everything else — including all writes to
     * {@code byKey} — is guarded by this table's monitor, held across the whole miss path so one
     * covering root is one walk and one pool charge regardless of thread interleaving.
     */
    private static final class Table {
        final ConcurrentHashMap<Key, Snapshot> byKey = new ConcurrentHashMap<>();
        final List<Key> covering = new ArrayList<>();
        long bytes;
        int nodes;
        final AtomicLong hits = new AtomicLong();
        final AtomicLong misses = new AtomicLong();
        final AtomicLong walks = new AtomicLong();
        boolean streamOnly;
        boolean decided;
    }

    private record LastJob(
            int maxMb,
            long poolMaxBytes,
            long poolUsedBytes,
            int nodes,
            long bytes,
            long walks,
            long hits,
            long misses,
            boolean streamOnly) {
        static final LastJob EMPTY = new LastJob(0, 0, 0, 0, 0, 0, 0, 0, false);
    }

    private static Table table() {
        return RequestScope.current().get(TABLE, k -> new Table());
    }

    private static JkEngineConfig config() {
        JkEngineConfig t = testConfig;
        if (t != null) return t;
        // Resolved once for the life of the process, as the docs promise for [engine] policy:
        // resolve() scans config.toml, and this is called from walk-saving hot paths. A frozen
        // config also keeps poolMaxBytes constant — the -Xmx this process booted with cannot
        // change, so neither may the pool ceiling derived from it. Benign race: resolve() is
        // deterministic within a process.
        JkEngineConfig r = resolved;
        if (r == null) {
            r = JkEngineConfig.resolve();
            resolved = r;
        }
        return r;
    }

    private static volatile @Nullable JkEngineConfig resolved;

    /** Caller holds the table's monitor. */
    private static void decideStream(Table table) {
        if (table.decided) return;
        table.decided = true;
        int vfsMb = config().vfsMaxMb();
        if (vfsMb == 0 || POOL_USED.get() >= poolMaxBytes()) table.streamOnly = true;
    }

    /** Caller holds the table's monitor. */
    private static @Nullable Snapshot ancestor(Table table, Path abs, String skipId) {
        for (Key cov : table.covering) {
            if (cov.skipId.equals(skipId) && abs.startsWith(cov.root) && !abs.equals(cov.root)) {
                return table.byKey.get(cov);
            }
        }
        return null;
    }

    /** Caller holds the table's monitor. */
    private static Snapshot load(Table table, Path root, String skipId) {
        if (table.streamOnly) return live(root, skipId);
        long jobCap = config().vfsMaxMb() * 1024L * 1024L;
        long room = Math.min(jobCap - table.bytes, poolMaxBytes() - POOL_USED.get());
        if (room <= 0) {
            table.streamOnly = true;
            return live(root, skipId);
        }
        List<FileRef> files = new ArrayList<>();
        long[] used = {0};
        table.walks.incrementAndGet();
        try {
            IOException fail = loadFailureForTest;
            if (fail != null) throw fail;
            PathUtil.forEachRegularFile(root, skipPred(skipId), (file, attrs) -> {
                FileRef ref = FileRef.from(file, attrs);
                long add = ref.estimateBytes();
                if (used[0] + add > room) throw new WalkLimitExceeded();
                files.add(ref);
                used[0] += add;
            });
        } catch (WalkLimitExceeded limit) {
            files.clear();
            table.streamOnly = true;
            return new Overflow(root, skipId);
        } catch (IOException unreadable) {
            // A tree that failed to list is unknown, not empty: an authoritative empty Listing
            // would cover every nested query with "no files" and give the module fingerprint a
            // stable digest of nothing — a build skipped as fresh. Stream instead; the job may
            // still retain other roots (this is not a budget event).
            return new Overflow(root, skipId);
        }
        if (!tryCharge(used[0])) {
            table.streamOnly = true;
            return live(root, skipId);
        }
        table.bytes += used[0];
        table.nodes += files.size();
        return new Listing(root, skipId, List.copyOf(files));
    }

    /**
     * True when {@code abs} is a tree this job writes — a {@link BuildLayout#TARGET} segment whose
     * parent holds the {@code jk.toml} that owns it, with {@link BuildLayout#TMP} carved out.
     *
     * <p>The manifest is the anchor. Build output is {@code <module>/target/} standalone and
     * {@code <workspace>/target/<rel>/} for a member, and in both layouts the directory holding
     * {@code target} is the one holding {@code jk.toml}. A directory merely named {@code target}
     * elsewhere — a package spelled {@code com.acme.target} under {@code src/main/java}, a resource
     * folder — is the user's input, and a name-only rule refused the whole module's snapshot for
     * it: every preflight walked the tree live, forever, with nothing saying why. The check is one
     * stat per {@code target} segment, on a path asked about once per root per step.
     *
     * <p>{@link BuildLayout#TMP} is declared scratch ({@link BuildLayout#tmpDir}), not the job's
     * own writing: nothing in a build plan compiles or generates into it. It is inside
     * {@code target/} only so {@code jk clean} can reach it, and it is where a forked test JVM's
     * temp root lives — so under {@code jk test} every {@code @TempDir} fixture acquires a
     * {@code target} ancestor, and a name-only rule answers "build output" for a tree the build
     * never touched. That is the same defect {@link BuildLayout#isBuildOutput} anchors away from:
     * a textual ancestor is not a structural one.
     *
     * <p>The scratch root is matched as <em>any</em> {@code tmp} segment inside the target tree
     * rather than a fixed depth, because its depth is a layout decision:
     * {@code <module>/target/tmp/} standalone, {@code <workspace>/target/<rel>/tmp/} for a member,
     * and the worker pool splits it again per worker. {@code tmp} is a name jk reserves under
     * {@code target/} for exactly this ({@link BuildLayout#TMP}), so a segment spelling it inside
     * the build output <em>is</em> the scratch root.
     *
     * <p>Scanning resumes past the scratch root rather than stopping, so a {@code target/} tree
     * <em>inside</em> a scratch tree is build output again — which is exactly what a fixture that
     * builds one is asserting about.
     */
    static boolean isBuildOutput(Path abs) {
        int n = abs.getNameCount();
        for (int i = 0; i < n; i++) {
            if (!BuildLayout.TARGET.equals(abs.getName(i).toString())) continue;
            if (!ownedByManifest(abs, i)) continue; // a directory merely named target
            int scratch = segmentIndex(abs, BuildLayout.TMP, i + 1, n);
            if (scratch < 0) return true;
            // Resume past the scratch root, not at it: the tail is judged on its own, so a
            // target/ tree a scratch tree contains is build output again.
            i = scratch;
        }
        return false;
    }

    /** True when the segment at {@code i} sits in a directory that holds a {@code jk.toml}. */
    private static boolean ownedByManifest(Path abs, int i) {
        if (i == 0) return false;
        Path parent = abs.subpath(0, i);
        Path root = abs.getRoot();
        if (root != null) parent = root.resolve(parent);
        return Files.isRegularFile(parent.resolve(ManifestPaths.MANIFEST));
    }

    /** First index in {@code [from, to)} whose segment is {@code name}, or {@code -1}. */
    private static int segmentIndex(Path abs, String name, int from, int to) {
        for (int i = from; i < to; i++) {
            if (name.equals(abs.getName(i).toString())) return i;
        }
        return -1;
    }

    private static boolean tryCharge(long n) {
        if (n <= 0) return true;
        while (true) {
            long cur = POOL_USED.get();
            if (cur + n > poolMaxBytes()) return false;
            if (POOL_USED.compareAndSet(cur, cur + n)) return true;
        }
    }

    private static Predicate<Path> skipPred(String skipId) {
        return switch (skipId) {
            case WalkSkip.ID_WORKSPACE_KEY -> WalkSkip::workspaceKey;
            case WalkSkip.ID_PATH_SOURCE -> WalkSkip::pathSource;
            case WalkSkip.ID_FORMAT -> WalkSkip::formatSkip;
            default -> dir -> false;
        };
    }

    private static Snapshot live(Path root, String skipId) {
        return new Overflow(root, skipId);
    }

    private static List<Path> livePaths(Path root, String skipId, Predicate<Path> pred) {
        List<Path> out = new ArrayList<>();
        try {
            PathUtil.forEachRegularFile(root, skipPred(skipId), (file, attrs) -> {
                if (pred.test(file)) out.add(file);
            });
        } catch (IOException ignored) {
            return List.of();
        }
        return List.copyOf(out);
    }
}
