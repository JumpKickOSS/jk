// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.layout;

import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.config.RequestScope;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.jsonl.Jsonl;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * Per-job input-tree snapshot. One covering {@code walkFileTree} of {@code src/} (compact: also
 * {@code test/} and sibling suite dirs) answers nested collects as prefix filters.
 *
 * <p>Retained bytes are live, not reserved: a job may grow until {@code min(vfs-max-mb, process
 * pool remaining)}. The process pool is 75% of {@code max-heap-mb}. A job that finds the pool
 * empty at first retain is sticky stream-only. Never {@code target/} / CAS / scratch.
 */
public final class InputTrees {

    private InputTrees() {}

    private static final Object TABLE = new Object();

    private static final AtomicLong POOL_USED = new AtomicLong();

    private static volatile LastJob LAST = LastJob.EMPTY;

    /** Override for tests; {@code null} means {@link JkEngineConfig#resolve()}. */
    private static volatile JkEngineConfig testConfig;

    public static Snapshot of(Path root) {
        return of(root, WalkSkip.ID_NONE);
    }

    public static Snapshot of(Path root, String skipId) {
        Path abs = root.toAbsolutePath().normalize();
        Table table = table();
        decideStream(table);
        Key key = new Key(abs, skipId);
        Snapshot hit = table.byKey.get(key);
        if (hit != null) {
            table.hits.incrementAndGet();
            return hit;
        }
        Snapshot ancestor = ancestor(table, abs, skipId);
        if (ancestor instanceof Overflow) {
            table.misses.incrementAndGet();
            return live(abs, skipId);
        }
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
            synchronized (table.covering) {
                table.covering.add(key);
            }
        }
        return loaded;
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
                if (TestSuites.SIMPLE_RESERVED.contains(n) || n.equals("src") || n.equals("test")) {
                    return true;
                }
                of(child);
                return true;
            });
        } catch (IOException ignored) {
            // cover is best-effort; collectors still live-walk
        }
    }

    /** Copy this job's counters to last-job and return its bytes to the process pool. */
    public static void finishJob() {
        Table table = table();
        LAST = new LastJob(
                config().vfsMaxMb(),
                poolMaxBytes(),
                POOL_USED.get(),
                table.nodes,
                table.bytes,
                table.walks.get(),
                table.hits.get(),
                table.streamOnly);
        long released = table.bytes;
        table.bytes = 0;
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
        Table table = table();
        decideStream(table);
        if (table.streamOnly) return 0;
        long jobCap = config().vfsMaxMb() * 1024L * 1024L;
        return Math.max(0L, Math.min(jobCap - table.bytes, poolMaxBytes() - POOL_USED.get()));
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

        boolean anyExtension(String ext);

        int countExtension(String ext);

        Optional<FileRef> get(Path file);

        boolean overflow();
    }

    public record FileRef(
            Path path, String name, long size, long mtimeMillis, long mtimeNanos, boolean regular) {
        static FileRef from(Path file, BasicFileAttributes attrs) {
            return new FileRef(
                    file.toAbsolutePath().normalize(),
                    file.getFileName().toString(),
                    attrs.size(),
                    attrs.lastModifiedTime().toMillis(),
                    attrs.lastModifiedTime().to(TimeUnit.NANOSECONDS),
                    attrs.isRegularFile());
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
            List<Path> cached = (List<Path>) RequestScope.current()
                    .get(new ExtMemo(root, skipId, ext), k -> {
                        List<Path> out = new ArrayList<>();
                        for (FileRef f : files) {
                            if (f.name.endsWith(ext)) out.add(f.path);
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
        public int countExtension(String ext) {
            int n = 0;
            for (FileRef f : files) {
                if (f.name.endsWith(ext)) n++;
            }
            return n;
        }

        @Override
        public Optional<FileRef> get(Path file) {
            Path abs = file.toAbsolutePath().normalize();
            for (FileRef f : files) {
                if (f.path.equals(abs)) return Optional.of(f);
            }
            return Optional.empty();
        }

        @Override
        public boolean overflow() {
            return false;
        }

        private record ExtMemo(Path root, String skipId, String ext) {}
    }

    public record Overflow(Path root, String skipId) implements Snapshot {
        @Override
        public List<FileRef> files() {
            return List.of();
        }

        @Override
        public List<Path> withExtension(String ext) {
            return livePaths(root, skipId, p -> p.getFileName().toString().endsWith(ext));
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
        public int countExtension(String ext) {
            return withExtension(ext).size();
        }

        @Override
        public Optional<FileRef> get(Path file) {
            return Optional.empty();
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
            boolean streamOnly) {
        static final LastJob EMPTY = new LastJob(0, 0, 0, 0, 0, 0, 0, false);
    }

    private static Table table() {
        return RequestScope.current().get(TABLE, k -> new Table());
    }

    private static JkEngineConfig config() {
        JkEngineConfig t = testConfig;
        return t != null ? t : JkEngineConfig.resolve();
    }

    private static void decideStream(Table table) {
        if (table.decided) return;
        table.decided = true;
        int vfsMb = config().vfsMaxMb();
        if (vfsMb == 0 || POOL_USED.get() >= poolMaxBytes()) table.streamOnly = true;
    }

    private static Snapshot ancestor(Table table, Path abs, String skipId) {
        synchronized (table.covering) {
            for (Key cov : table.covering) {
                if (cov.skipId.equals(skipId) && abs.startsWith(cov.root) && !abs.equals(cov.root)) {
                    return table.byKey.get(cov);
                }
            }
        }
        return null;
    }

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
            return new Listing(root, skipId, List.of());
        }
        if (!tryCharge(used[0])) {
            table.streamOnly = true;
            return live(root, skipId);
        }
        table.bytes += used[0];
        table.nodes += files.size();
        return new Listing(root, skipId, List.copyOf(files));
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
            case WalkSkip.ID_FORMAT -> dir -> {
                Path name = dir.getFileName();
                return name != null && WalkSkip.formatSegment(name.toString());
            };
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
