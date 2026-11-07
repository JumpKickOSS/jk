// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkCacheConfig;
import cc.jumpkick.host.ActionTree;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.util.FileLocks;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Retention for the three things under {@code actions/}, each with its own shelf life and its own
 * denominator, because they fail differently when they go:
 *
 * <ul>
 *   <li><b>Action</b> — key records that name CAS outputs, plus those outputs. Losing one costs a
 *       recompute or a re-package. 30-day window, bounded by {@code [cache] max-cache-size-gb}.
 *   <li><b>Memo</b> — key records that name no output at all ({@code run-tests} green stamps and
 *       friends: the record <em>is</em> the result). A few hundred bytes each, so evicting them for
 *       space is nearly free of benefit and expensive in re-run time. 90-day window, capped by
 *       <em>count</em>, never by bytes.
 *   <li><b>Incremental</b> — Zinc analysis under {@code incremental-java/}, {@code
 *       incremental-kotlin/}. Pure speed, worth the most during a hot edit loop and worthless a week
 *       later; a full recompile is the fallback. 7-day window, bounded by {@code
 *       [cache] incremental-max-size-gb}.
 * </ul>
 *
 * <p>Each tier gets an unconditional window pass — stale entries go whether or not the cache is
 * near its budget — and then a budget pass that short-circuits the window when the tier is over.
 * Splitting the denominators is what makes that possible: with one budget over all of {@code
 * actions/}, a large workspace's Zinc state could push the total over the line and the prune would
 * evict every action key and still be over.
 *
 * <p><b>Order within a tier.</b> Oldest last use first, and nothing else. A key record's mtime is
 * its last use: every reader marks a hit ({@link cc.jumpkick.cache.ActionRecordText#markRead}, at
 * most once per half grace), so a stable module that hits daily sorts young and a churning module's dead
 * keys sort old. The code graph's fragments are action-tier entries like any other and take their
 * turn in the same order: they are small, so taking them first would free little and cost a
 * re-extraction of what graph questions read every day. The blob mtimes are deliberately not
 * consulted: the CAS is write-once, so a blob's mtime is its <em>first</em> store time, and an old
 * blob under a young key means "still current, first seen long ago".
 *
 * <p>Runs after {@link CasSweep} in the same pass so reclaimed garbage covers the shortfall before
 * anything live is evicted.
 */
public final class ActionCachePrune {

    private static final Pattern TASK = Pattern.compile("^TASK (\\S+)", Pattern.MULTILINE);
    private static final Pattern OUTPUT_SHA = Pattern.compile("^OUTPUT ([0-9a-fA-F]{64}) ", Pattern.MULTILINE);
    private static final int[] NO_BLOBS = new int[0];

    private ActionCachePrune() {}

    /**
     * Shelf lives and budgets, one set per tier. {@code JkCacheConfig} supplies the two configurable
     * sizes; the windows are policy, not configuration — they exist so a cache that never reaches
     * its budget still sheds work nobody will ask for again.
     *
     * @param actionBudgetBytes byte budget for key records plus the cache CAS; {@code <= 0} disables
     *     the budget pass (the window pass still runs)
     * @param actionWindow age after which a blob-bearing key goes regardless of budget
     * @param memoWindow age after which an output-less key record goes
     * @param memoMaxEntries how many output-less key records survive; {@code <= 0} disables the cap
     * @param incrementalBudgetBytes byte budget for the Zinc analysis trees
     * @param incrementalWindow age after which one task's Zinc analysis goes
     */
    public record Policy(
            long actionBudgetBytes,
            Duration actionWindow,
            Duration memoWindow,
            int memoMaxEntries,
            long incrementalBudgetBytes,
            Duration incrementalWindow) {

        /** Action keys outlive a month of not being used. */
        public static final Duration ACTION_WINDOW = Duration.ofDays(30);

        /** Memo records are tiny, so they get a quarter before they are assumed dead. */
        public static final Duration MEMO_WINDOW = Duration.ofDays(90);

        /**
         * Ceiling on output-less key records. Reached only by a machine minting new task identities
         * (branch-per-ticket workspaces, throwaway checkouts) — a steady workspace holds a few
         * thousand.
         */
        public static final int MEMO_MAX_ENTRIES = 20_000;

        /** Zinc state is worth the most inside an edit loop and nothing at all a week later. */
        public static final Duration INCREMENTAL_WINDOW = Duration.ofDays(7);

        /** Windows as specified, sizes from {@code [cache]}. */
        public static Policy of(JkCacheConfig config) {
            return new Policy(
                    config.maxCacheSizeBytes(),
                    ACTION_WINDOW,
                    MEMO_WINDOW,
                    MEMO_MAX_ENTRIES,
                    config.incrementalMaxSizeBytes(),
                    INCREMENTAL_WINDOW);
        }
    }

    /**
     * @param deletedKeys action and memo key records removed, each with its task pointer and
     *     generation entry
     * @param deletedBlobs cache-CAS blobs unlinked with their last referencing key
     * @param freedBytes key-record plus blob bytes removed from the action tier (dry run: that would
     *     be). Task pointers and generation lists go with the key but are not counted — roughly 64
     *     bytes an entry, so the pass errs towards evicting one entry too many rather than one too
     *     few
     * @param finalBytes action-tier bytes after the pass — key records, task pointers and the cache
     *     CAS, <em>not</em> the incremental trees. May exceed the budget when every remaining entry
     *     is inside the write grace window
     * @param deletedIncrementalFiles files removed from the Zinc analysis trees
     * @param incrementalFreedBytes bytes removed from the Zinc analysis trees
     * @param incrementalFinalBytes Zinc analysis bytes after the pass
     */
    public record Report(
            int deletedKeys,
            int deletedBlobs,
            long freedBytes,
            long finalBytes,
            int deletedIncrementalFiles,
            long incrementalFreedBytes,
            long incrementalFinalBytes) {

        static final Report EMPTY = new Report(0, 0, 0L, 0L, 0, 0L, 0L);

        /** Every file this pass unlinked, across tiers — the number {@code jk cache clean} reports. */
        public long totalDeletedFiles() {
            return (long) deletedKeys + deletedBlobs + deletedIncrementalFiles;
        }

        /** Every byte this pass reclaimed, across tiers. */
        public long totalFreedBytes() {
            return freedBytes + incrementalFreedBytes;
        }
    }

    /**
     * Apply {@code policy} to the three tiers under {@code cacheRoot}. {@code alreadyFreedShas} are
     * blobs a same-pass {@link CasSweep} claimed — excluded from both the measured total and the
     * candidate blobs so a dry run reports the same numbers as a real one.
     *
     * <p>Entries and blobs touched within {@link Cas#MIN_AGE_FOR_SWEEP} are never taken: the
     * caller's locks keep this engine's builds out of the way, but a second engine sharing the cache
     * root holds none of them and may be mid-{@link ActionCache#restore}. Every budget here is
     * therefore soft — under continuous write pressure a final size above the budget is a legitimate
     * outcome.
     */
    public static Report run(Path cacheRoot, Cas cacheCas, Policy policy, Set<String> alreadyFreedShas, boolean dryRun)
            throws IOException {
        return run(cacheRoot, cacheCas, policy, alreadyFreedShas, dryRun, PruneYield.NEVER);
    }

    /**
     * As {@link #run(Path, Cas, Policy, Set, boolean)}, stopping at {@code yield}'s say-so between
     * Zinc trees, between files of the scans, and before each entry the evictor takes, never
     * between a key and the blobs it frees.
     */
    public static Report run(
            Path cacheRoot, Cas cacheCas, Policy policy, Set<String> alreadyFreedShas, boolean dryRun, PruneYield yield)
            throws IOException {
        long now = Clock.SYSTEM.millis();
        long grace = Cas.MIN_AGE_FOR_SWEEP.toMillis();
        Path actionsDir = CacheTree.ACTIONS.under(cacheRoot);
        if (!Files.isDirectory(actionsDir)) return Report.EMPTY;

        // Each Zinc tree goes whole and alone; the tier is a few dozen trees, so it runs unbroken.
        yield.check();
        Incremental incremental = pruneIncremental(actionsDir, policy, now, grace, dryRun);

        Blobs blobs = new Blobs();
        long used = scanCas(cacheCas, alreadyFreedShas, blobs, yield);

        Path keysDir = ActionTree.KEYS.under(actionsDir);
        Path tasksDir = ActionTree.TASKS.under(actionsDir);
        Scan scan = scanActions(actionsDir, keysDir, tasksDir, blobs, yield);
        used += scan.bytes();

        Evictor evictor = new Evictor(keysDir, tasksDir, cacheCas, blobs, now, grace, dryRun, yield);

        List<Entry> actions = new ArrayList<>();
        List<Entry> memos = new ArrayList<>();
        for (Entry entry : scan.entries()) {
            (entry.memo() ? memos : actions).add(entry);
        }
        actions.sort(EVICTION_ORDER);
        memos.sort(EVICTION_ORDER);

        // Window first, in both tiers: a cache that never approaches its budget must still shed work
        // nobody will ask for again, and the bytes it frees are bytes the budget pass need not take
        // from something live.
        used -= expire(actions, policy.actionWindow(), now, evictor);
        used -= expire(memos, policy.memoWindow(), now, evictor);

        // Memos are capped by count, never by bytes: a run-tests stamp is a few hundred bytes, so
        // evicting one to make room buys a rounding error and costs a whole test run.
        used -= capMemos(memos, policy.memoMaxEntries(), evictor);

        if (policy.actionBudgetBytes() > 0) {
            for (Entry entry : actions) {
                if (used <= policy.actionBudgetBytes()) break;
                if (entry.deleted()) continue;
                used -= evictor.evict(entry);
            }
        }

        return new Report(
                evictor.deletedKeys,
                evictor.deletedBlobs,
                evictor.freed,
                used,
                incremental.deletedFiles(),
                incremental.freedBytes(),
                incremental.finalBytes());
    }

    /**
     * Oldest last use first, and nothing else. A key the task pointer no longer names is not dead:
     * its inputs come back with a revert, a branch switch or a sibling worktree, and the pointer is
     * shared by every checkout of the project, so "superseded" only says which checkout stored last.
     */
    private static final Comparator<Entry> EVICTION_ORDER = Comparator.comparingLong(Entry::mtime);

    /** Evict everything in {@code candidates} older than {@code window}; returns the bytes freed. */
    private static long expire(List<Entry> candidates, Duration window, long now, Evictor evictor) throws IOException {
        if (window == null || window.isZero() || window.isNegative()) return 0L;
        long cutoff = window.toMillis();
        long freed = 0L;
        for (Entry entry : candidates) {
            if (now - entry.mtime() < cutoff) continue;
            freed += evictor.evict(entry);
        }
        return freed;
    }

    /** Trim the memo tier to {@code max} surviving records, least recently used first. */
    private static long capMemos(List<Entry> memos, int max, Evictor evictor) throws IOException {
        if (max <= 0) return 0L;
        long alive = memos.stream().filter(e -> !e.deleted()).count();
        long freed = 0L;
        for (Entry entry : memos) {
            if (alive <= max) break;
            if (entry.deleted()) continue;
            freed += evictor.evict(entry);
            if (entry.deleted()) alive--;
        }
        return freed;
    }

    /**
     * Deletes one entry at a time and keeps the running blob refcount honest across the passes that
     * share it — a blob is unlinked only with the last key that names it.
     */
    private static final class Evictor {
        private final Path keysDir;
        private final Path tasksDir;
        private final Cas cacheCas;
        private final Blobs blobs;
        private final long now;
        private final long grace;
        private final boolean dryRun;
        private final PruneYield yield;

        int deletedKeys;
        int deletedBlobs;
        long freed;

        Evictor(
                Path keysDir,
                Path tasksDir,
                Cas cacheCas,
                Blobs blobs,
                long now,
                long grace,
                boolean dryRun,
                PruneYield yield) {
            this.keysDir = keysDir;
            this.tasksDir = tasksDir;
            this.cacheCas = cacheCas;
            this.blobs = blobs;
            this.now = now;
            this.grace = grace;
            this.dryRun = dryRun;
            this.yield = yield;
        }

        /** Bytes freed by taking {@code entry}, or {@code 0} if the grace window protects it. */
        long evict(Entry entry) throws IOException {
            if (entry.deleted()) return 0L;
            // Before the key, never after it: the blobs only this key names go with it or not at all.
            yield.check();
            long live = liveMtime(keysDir.resolve(entry.actionKey()), entry.mtime());
            if (now - live < grace) return 0L;
            if (!dryRun && !deleteKey(keysDir, tasksDir, entry.actionKey(), entry.taskId(), entry.superseded(), grace))
                return 0L;
            entry.markDeleted();
            deletedKeys++;
            long bytes = entry.bytes();

            for (int blob : entry.blobs()) {
                // Key records are the whole root set for this pool, so a refcount of zero really is
                // unreferenced: the cache CAS holds action outputs only, and CacheRoots' other roots
                // (synced manifests, tool envs, jk-local memos) name store-CAS blobs.
                if (blobs.release(blob) > 0) continue;
                long size = blobs.size(blob);
                if (size < 0) continue; // swept this pass, or never in this CAS
                if (now - blobs.mtime(blob) < grace) continue;
                if (!dryRun) Files.deleteIfExists(cacheCas.pathFor(blobs.sha(blob)));
                deletedBlobs++;
                bytes += size;
            }
            freed += bytes;
            return bytes;
        }
    }

    /**
     * Every output sha one pass meets, each held once: an entry names its outputs by index here.
     * Successive generations of a key name mostly the same outputs, so a cache holds tens of
     * references per blob, and a string per reference would cost the engine heap a few hundred
     * bytes for each one.
     */
    private static final class Blobs {
        private final Map<String, Integer> index = new HashMap<>();
        private final List<String> shas = new ArrayList<>();
        private long[] sizes = new long[1024];
        private long[] mtimes = new long[1024];
        private int[] refs = new int[1024];

        /** The index of {@code sha}, added with no size (not in this CAS) the first time it is met. */
        int intern(String sha) {
            Integer known = index.get(sha);
            if (known != null) return known;
            int id = shas.size();
            if (id == sizes.length) {
                sizes = Arrays.copyOf(sizes, id * 2);
                mtimes = Arrays.copyOf(mtimes, id * 2);
                refs = Arrays.copyOf(refs, id * 2);
            }
            index.put(sha, id);
            shas.add(sha);
            sizes[id] = -1L;
            return id;
        }

        /** Records a live blob of this CAS. */
        void stored(String sha, long size, long mtime) {
            int id = intern(sha);
            sizes[id] = size;
            mtimes[id] = mtime;
        }

        void retain(int id) {
            refs[id]++;
        }

        /** Drops one reference and returns how many remain. */
        int release(int id) {
            return --refs[id];
        }

        String sha(int id) {
            return shas.get(id);
        }

        /** Bytes of the blob in this CAS, or {@code -1} when it is not there. */
        long size(int id) {
            return sizes[id];
        }

        long mtime(int id) {
            return mtimes[id];
        }
    }

    /** One action-cache entry: the key record and the outputs it names, as {@link Blobs} indices. */
    private static final class Entry {
        private final String actionKey;
        private final String taskId;
        private final long bytes;
        private final long mtime;
        private final int[] blobs;
        private final boolean superseded;
        private boolean deleted;

        Entry(String actionKey, String taskId, long bytes, long mtime, int[] blobs, boolean superseded) {
            this.actionKey = actionKey;
            this.taskId = taskId;
            this.bytes = bytes;
            this.mtime = mtime;
            this.blobs = blobs;
            this.superseded = superseded;
        }

        String actionKey() {
            return actionKey;
        }

        String taskId() {
            return taskId;
        }

        long bytes() {
            return bytes;
        }

        long mtime() {
            return mtime;
        }

        int[] blobs() {
            return blobs;
        }

        boolean superseded() {
            return superseded;
        }

        boolean deleted() {
            return deleted;
        }

        void markDeleted() {
            deleted = true;
        }

        /**
         * A record that names no output <em>is</em> the result — a {@code run-tests} green stamp,
         * a check that only had to pass. Freeing one returns less than a filesystem block, so the
         * byte budget must never spend a test run to reclaim it.
         */
        boolean memo() {
            return blobs.length == 0;
        }
    }

    /** What one pass over {@code actions/} found, minus the incremental trees. */
    private record Scan(long bytes, List<Entry> entries) {}

    /** What the incremental pass reclaimed, and what it left. */
    private record Incremental(int deletedFiles, long freedBytes, long finalBytes) {}

    /** Records size and mtime per live cache-CAS blob in {@code blobs} and returns their total bytes. */
    private static long scanCas(Cas cacheCas, Set<String> alreadyFreedShas, Blobs blobs, PruneYield yield)
            throws IOException {
        Path shaRoot = cacheCas.root().resolve("sha256");
        if (!Files.isDirectory(shaRoot)) return 0L;
        long[] bytes = {0L};
        PathUtil.forEachRegularFile(shaRoot, (file, attrs) -> {
            yield.check();
            if (file.getFileName().toString().startsWith(".put-")) return;
            var hex = cacheCas.hashFromPath(file);
            if (hex.isEmpty() || alreadyFreedShas.contains(hex.get())) return;
            blobs.stored(hex.get(), attrs.size(), attrs.lastModifiedTime().toMillis());
            bytes[0] += attrs.size();
        });
        return bytes[0];
    }

    /**
     * Read every key record under {@code keysDir}, count in {@code blobs} how many name each output
     * sha, and total the action tier's bytes — key records plus task pointers plus generation lists,
     * the denominator {@code max-cache-size-gb} bounds. The incremental trees are excluded: they are
     * measured and bounded separately, and folding them in here is what let a large workspace's Zinc
     * state push the action budget over a line no amount of key eviction could bring back. A record
     * is parsed as the walk meets it, so the pass holds one record's text at a time.
     */
    private static Scan scanActions(Path actionsDir, Path keysDir, Path tasksDir, Blobs blobs, PruneYield yield)
            throws IOException {
        // A null pointer set means "could not tell": every key then counts as current.
        Set<String> live = livePointers(tasksDir);
        List<Entry> entries = new ArrayList<>();
        long[] bytes = {0L};
        PathUtil.forEachRegularFile(actionsDir, dir -> underIncremental(actionsDir, dir), (file, attrs) -> {
            yield.check();
            bytes[0] += attrs.size();
            if (!keysDir.equals(file.getParent())) return;
            String body;
            try {
                body = Files.readString(file, StandardCharsets.UTF_8);
            } catch (NoSuchFileException vanished) {
                // A concurrent ActionCache.trimGenerations dropped it between the walk and here. Any
                // other read fault propagates: without the record we cannot tell which blobs it
                // roots, and guessing "none" would let another entry's eviction unlink them.
                return;
            }
            int[] named = outputs(body, blobs);
            for (int blob : named) blobs.retain(blob);

            Matcher task = TASK.matcher(body);
            // ActionCache always renders TASK first, so a record without one is foreign content, not
            // a jk entry: there is no pointer or generation list to unlink with it. Its shas stay
            // counted above, which is what CacheRoots.collect does with the same text — the prune
            // must not unlink a blob the same pass's sweep considers rooted.
            if (!task.find()) return;
            String actionKey = file.getFileName().toString();
            boolean superseded = live != null && !live.contains(actionKey);
            entries.add(new Entry(
                    actionKey,
                    task.group(1),
                    attrs.size(),
                    attrs.lastModifiedTime().toMillis(),
                    named,
                    superseded));
        });
        return new Scan(bytes[0], entries);
    }

    /** The distinct output shas one key record names, as {@link Blobs} indices. */
    private static int[] outputs(String body, Blobs blobs) {
        Matcher outputs = OUTPUT_SHA.matcher(body);
        int[] named = new int[8];
        int count = 0;
        while (outputs.find()) {
            if (count == named.length) named = Arrays.copyOf(named, count * 2);
            named[count++] = blobs.intern(outputs.group(1).toLowerCase(Locale.ROOT));
        }
        if (count == 0) return NO_BLOBS;
        int[] sorted = Arrays.copyOf(named, count);
        Arrays.sort(sorted);
        int distinct = 1;
        for (int i = 1; i < count; i++) {
            if (sorted[i] != sorted[distinct - 1]) sorted[distinct++] = sorted[i];
        }
        return distinct == count ? sorted : Arrays.copyOf(sorted, distinct);
    }

    /** True when {@code path} is or sits inside one of the separately-budgeted Zinc analysis trees. */
    private static boolean underIncremental(Path actionsDir, Path path) {
        Path relative = actionsDir.relativize(path);
        return relative.getNameCount() > 0
                && ActionTree.incremental().stream()
                        .anyMatch(t -> t.entry().equals(relative.getName(0).toString()));
    }

    /**
     * Every action key some task still points at: the current {@code tasks/<taskId>} pointer plus
     * the generations a Class-C task retains. Anything else is superseded, which does not move it in
     * the eviction order; it only tells {@link #deleteKey} that a pointer naming it now was
     * republished by a concurrent store. {@code null} when the pointers could not be read, which the
     * caller reads as "assume nothing is superseded".
     */
    private static @Nullable Set<String> livePointers(Path tasksDir) throws IOException {
        Set<String> live = new HashSet<>();
        if (!Files.isDirectory(tasksDir)) return live;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(tasksDir)) {
            for (Path file : stream) {
                if (!Files.isRegularFile(file)) continue;
                List<String> lines;
                try {
                    lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                } catch (IOException unreadable) {
                    // Supersession is unprovable without the pointers, and guessing "superseded"
                    // would move the whole cache to the front of the eviction queue.
                    return null;
                }
                // A pointer is one key; a generation-list line is `<checkout> <key>`.
                for (String line : lines) {
                    String l = line.strip();
                    if (!l.isEmpty()) live.add(l.substring(l.lastIndexOf(' ') + 1));
                }
            }
        }
        return live;
    }

    /**
     * Window then budget over the Zinc analysis trees, a task directory at a time. A tree's age is
     * the newest mtime anywhere in it, its directories included: a compile stamps its tree as it
     * starts ({@link #markInUse}) and creates directories before it writes a file, so a tree in use
     * by any engine on the machine is inside the grace window. The prune holds only this engine's
     * locks; that stamp is what keeps it off another engine's compile.
     */
    private static Incremental pruneIncremental(Path actionsDir, Policy policy, long now, long grace, boolean dryRun)
            throws IOException {
        record Tree(Path dir, long bytes, long mtime) {}
        List<Tree> trees = new ArrayList<>();
        long used = 0L;
        for (Path root : ActionTree.incrementalUnder(actionsDir)) {
            if (!Files.isDirectory(root)) continue;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
                for (Path dir : stream) {
                    if (!Files.isDirectory(dir)) continue;
                    long[] size = {0L};
                    long[] newest = {0L};
                    try (Stream<Path> walk = Files.walk(dir)) {
                        for (Path entry : (Iterable<Path>) walk::iterator) {
                            newest[0] = Math.max(
                                    newest[0], Files.getLastModifiedTime(entry).toMillis());
                            if (Files.isRegularFile(entry)) size[0] += Files.size(entry);
                        }
                    } catch (NoSuchFileException vanished) {
                        continue;
                    }
                    trees.add(new Tree(dir, size[0], newest[0]));
                    used += size[0];
                }
            }
        }
        if (trees.isEmpty()) return new Incremental(0, 0L, 0L);

        trees.sort(Comparator.comparingLong(Tree::mtime));
        long window = policy.incrementalWindow() == null
                ? 0L
                : policy.incrementalWindow().toMillis();
        long budget = policy.incrementalBudgetBytes();
        int deletedFiles = 0;
        long freed = 0L;
        for (Tree tree : trees) {
            boolean stale = window > 0 && now - tree.mtime() >= window;
            boolean overBudget = budget > 0 && used > budget;
            if (!stale && !overBudget) continue;
            if (now - tree.mtime() < grace) continue;
            deletedFiles += dryRun ? countFiles(tree.dir()) : deleteTree(tree.dir());
            freed += tree.bytes();
            used -= tree.bytes();
        }
        return new Incremental(deletedFiles, freed, used);
    }

    /**
     * Stamp an incremental tree as used now, before a compile reads or rewrites it. A prune — this
     * engine's or another's sharing the cache — takes no tree younger than {@link
     * Cas#MIN_AGE_FOR_SWEEP}, so the stamp holds the tree for the compile's duration. A tree not
     * there yet needs none: the directories the compile creates are its first stamp.
     */
    static void markInUse(Path stateDir) throws IOException {
        if (!Files.isDirectory(stateDir)) return;
        try {
            Files.setLastModifiedTime(stateDir, FileTime.fromMillis(Clock.SYSTEM.millis()));
        } catch (NoSuchFileException vanished) {
            // Taken between the check and the stamp; the compile recreates what it writes.
        }
    }

    private static int countFiles(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            return (int) walk.filter(Files::isRegularFile).count();
        } catch (NoSuchFileException vanished) {
            return 0;
        }
    }

    /** Depth-first delete of one task's analysis directory; returns the regular files unlinked. */
    private static int deleteTree(Path dir) throws IOException {
        var removed = new PathUtil.Removed();
        PathUtil.deleteRecursivelyOrThrow(dir, removed);
        return (int) removed.files();
    }

    /**
     * Unlink one entry after re-reading the key and its pointer. A key marked read or replaced
     * inside the grace window stays, and so does a pointer that was republished onto a key the scan
     * had classified as superseded. A cold key the budget chose — the pointer still
     * names it, and the scan already knew that — is removed with its pointer. Returns false when
     * the re-read says a concurrent store won.
     *
     * <p>The generation lock is taken only for a task that has a generation list. That is the lock
     * the store side takes, and only for those tasks; taking it for every key would create a
     * {@code <taskId>.gens.lock} beside every pointer this pass removes, in the directory the
     * pass exists to shrink.
     */
    static boolean deleteKey(
            Path keysDir, Path tasksDir, String actionKey, String taskId, boolean superseded, long grace)
            throws IOException {
        Path keyFile = keysDir.resolve(actionKey);
        Path gens = HeavyActionPolicy.gensFile(tasksDir, taskId);
        if (!Files.isRegularFile(gens)) {
            return unlinkDeadKey(keyFile, tasksDir, actionKey, taskId, null, superseded, grace);
        }
        boolean[] removed = {false};
        FileLocks.withLock(
                HeavyActionPolicy.gensLock(gens),
                () -> removed[0] = unlinkDeadKey(keyFile, tasksDir, actionKey, taskId, gens, superseded, grace));
        return removed[0];
    }

    /**
     * The re-read and the unlink itself; run under {@link HeavyActionPolicy#gensLock} when
     * {@code gens} is non-null. False leaves everything in place.
     */
    private static boolean unlinkDeadKey(
            Path keyFile,
            Path tasksDir,
            String actionKey,
            String taskId,
            @Nullable Path gens,
            boolean superseded,
            long grace)
            throws IOException {
        if (Files.isRegularFile(keyFile)) {
            long live = Files.getLastModifiedTime(keyFile).toMillis();
            if (Clock.SYSTEM.millis() - live < grace) return false;
        }
        Path pointer = tasksDir.resolve(taskId);
        boolean named = false;
        if (Files.isRegularFile(pointer)) {
            String current = Files.readString(pointer, StandardCharsets.UTF_8).trim();
            named = actionKey.equals(current);
        }
        // The scan saw a dead key. A store that has since aimed the pointer at it republished it.
        if (superseded && named) return false;
        if (gens != null && Files.isRegularFile(gens)) {
            List<HeavyActionPolicy.Generation> kept = new ArrayList<>();
            for (HeavyActionPolicy.Generation g : HeavyActionPolicy.readGenerations(gens)) {
                if (!g.key().equals(actionKey)) kept.add(g);
            }
            HeavyActionPolicy.writeGenerations(gens, kept);
        }
        Files.deleteIfExists(keyFile);
        if (named) Files.deleteIfExists(pointer);
        return true;
    }

    /** The key file's mtime when it is still there, otherwise the mtime captured at scan time. */
    private static long liveMtime(Path keyFile, long scanned) {
        try {
            if (Files.isRegularFile(keyFile))
                return Files.getLastModifiedTime(keyFile).toMillis();
        } catch (IOException ignored) {
            // keep the scan-time mtime
        }
        return scanned;
    }
}
