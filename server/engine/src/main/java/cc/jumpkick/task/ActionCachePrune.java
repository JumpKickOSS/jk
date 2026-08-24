// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkCacheConfig;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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
 * <p><b>Order within a tier.</b> Superseded entries first — a key that is neither its task's current
 * pointer nor in its generation list can never be hit again, so it is free to take. Then oldest
 * mtime, which {@link ActionCache#lookup} keeps as <em>last use</em>: a hit re-stamps the key
 * (coarsened to an hour, deduped per engine), so a stable module that hits daily sorts young and a
 * churning module's dead keys sort old. The blob mtimes are deliberately not consulted: the CAS is
 * write-once, so a blob's mtime is its <em>first</em> store time, and an old blob under a young key
 * means "still current, first seen long ago".
 *
 * <p>Runs after {@link CasSweep} in the same pass so reclaimed garbage covers the shortfall before
 * anything live is evicted.
 */
public final class ActionCachePrune {

    private static final Pattern TASK = Pattern.compile("^TASK (\\S+)", Pattern.MULTILINE);
    private static final Pattern OUTPUT_SHA = Pattern.compile("^OUTPUT ([0-9a-fA-F]{64}) ", Pattern.MULTILINE);

    /** Zinc analysis trees, evicted a directory at a time. */
    private static final List<String> INCREMENTAL_DIRS = List.of("incremental-java", "incremental-kotlin");

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
     * <p>Entries and blobs touched within {@link Sweep#MIN_AGE_FOR_SWEEP} are never taken: the
     * caller's locks keep this engine's builds out of the way, but a second engine sharing the cache
     * root holds none of them and may be mid-{@link ActionCache#restore}. Every budget here is
     * therefore soft — under continuous write pressure a final size above the budget is a legitimate
     * outcome.
     */
    public static Report run(Path cacheRoot, Cas cacheCas, Policy policy, Set<String> alreadyFreedShas, boolean dryRun)
            throws IOException {
        long now = System.currentTimeMillis();
        long grace = Sweep.MIN_AGE_FOR_SWEEP.toMillis();
        Path actionsDir = CacheTree.ACTIONS.under(cacheRoot);
        if (!Files.isDirectory(actionsDir)) return Report.EMPTY;

        Incremental incremental = pruneIncremental(actionsDir, policy, now, grace, dryRun);

        Map<String, Long> blobSize = new HashMap<>();
        Map<String, Long> blobMtime = new HashMap<>();
        long used = scanCas(cacheCas, alreadyFreedShas, blobSize, blobMtime);

        Path keysDir = actionsDir.resolve("keys");
        Path tasksDir = actionsDir.resolve("tasks");
        Scan scan = scanActions(actionsDir, keysDir, tasksDir);
        used += scan.bytes();

        Evictor evictor =
                new Evictor(keysDir, tasksDir, cacheCas, scan.refs(), blobSize, blobMtime, now, grace, dryRun);

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
     * Superseded first — those can never be hit again, so they cost nothing to take — then oldest
     * last use.
     */
    private static final Comparator<Entry> EVICTION_ORDER =
            Comparator.comparingInt((Entry e) -> e.superseded() ? 0 : 1).thenComparingLong(Entry::mtime);

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

    /** Trim the memo tier to {@code max} surviving records, superseded and coldest first. */
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
        private final Map<String, Integer> refs;
        private final Map<String, Long> blobSize;
        private final Map<String, Long> blobMtime;
        private final long now;
        private final long grace;
        private final boolean dryRun;

        int deletedKeys;
        int deletedBlobs;
        long freed;

        Evictor(
                Path keysDir,
                Path tasksDir,
                Cas cacheCas,
                Map<String, Integer> refs,
                Map<String, Long> blobSize,
                Map<String, Long> blobMtime,
                long now,
                long grace,
                boolean dryRun) {
            this.keysDir = keysDir;
            this.tasksDir = tasksDir;
            this.cacheCas = cacheCas;
            this.refs = refs;
            this.blobSize = blobSize;
            this.blobMtime = blobMtime;
            this.now = now;
            this.grace = grace;
            this.dryRun = dryRun;
        }

        /** Bytes freed by taking {@code entry}, or {@code 0} if the grace window protects it. */
        long evict(Entry entry) throws IOException {
            if (entry.deleted()) return 0L;
            if (now - entry.mtime() < grace) return 0L;
            entry.markDeleted();

            if (!dryRun) deleteKey(keysDir, tasksDir, entry.actionKey(), entry.taskId());
            deletedKeys++;
            long bytes = entry.bytes();

            for (String sha : entry.shas()) {
                // Key records are the whole root set for this pool, so a refcount of zero really is
                // unreferenced: the cache CAS holds action outputs only, and CacheRoots' other roots
                // (synced manifests, tool envs, jk-local memos) name store-CAS blobs.
                if (refs.merge(sha, -1, Integer::sum) > 0) continue;
                Long size = blobSize.get(sha);
                if (size == null) continue; // swept this pass, or never in this CAS
                if (now - blobMtime.get(sha) < grace) continue;
                if (!dryRun) Files.deleteIfExists(cacheCas.pathFor(sha));
                deletedBlobs++;
                bytes += size;
            }
            freed += bytes;
            return bytes;
        }
    }

    /** One action-cache entry: the key record and the outputs it names. */
    private static final class Entry {
        private final String actionKey;
        private final String taskId;
        private final long bytes;
        private final long mtime;
        private final Set<String> shas;
        private final boolean superseded;
        private boolean deleted;

        Entry(String actionKey, String taskId, long bytes, long mtime, Set<String> shas, boolean superseded) {
            this.actionKey = actionKey;
            this.taskId = taskId;
            this.bytes = bytes;
            this.mtime = mtime;
            this.shas = shas;
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

        Set<String> shas() {
            return shas;
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
            return shas.isEmpty();
        }
    }

    /** What one pass over {@code actions/} found, minus the incremental trees. */
    private record Scan(long bytes, List<Entry> entries, Map<String, Integer> refs) {}

    /** What the incremental pass reclaimed, and what it left. */
    private record Incremental(int deletedFiles, long freedBytes, long finalBytes) {}

    /** Records {@code size}/{@code mtime} per live cache-CAS blob and returns their total bytes. */
    private static long scanCas(
            Cas cacheCas, Set<String> alreadyFreedShas, Map<String, Long> blobSize, Map<String, Long> blobMtime)
            throws IOException {
        Path shaRoot = cacheCas.root().resolve("sha256");
        if (!Files.isDirectory(shaRoot)) return 0L;
        long bytes = 0L;
        try (Stream<Path> stream = Files.walk(shaRoot)) {
            for (Path file : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(file)) continue;
                if (file.getFileName().toString().startsWith(".put-")) continue;
                var hex = cacheCas.hashFromPath(file);
                if (hex.isEmpty() || alreadyFreedShas.contains(hex.get())) continue;
                long size = Files.size(file);
                blobSize.put(hex.get(), size);
                blobMtime.put(hex.get(), Files.getLastModifiedTime(file).toMillis());
                bytes += size;
            }
        }
        return bytes;
    }

    /**
     * Read every key record under {@code keysDir}, count how many name each output sha, and total
     * the action tier's bytes — key records plus task pointers plus generation lists, the
     * denominator {@code max-cache-size-gb} bounds. The incremental trees are excluded: they are
     * measured and bounded separately, and folding them in here is what let a large workspace's Zinc
     * state push the action budget over a line no amount of key eviction could bring back.
     */
    private static Scan scanActions(Path actionsDir, Path keysDir, Path tasksDir) throws IOException {
        record KeyFile(Path file, long bytes, long mtime) {}
        List<KeyFile> keyFiles = new ArrayList<>();
        long bytes = 0L;
        try (Stream<Path> stream = Files.walk(actionsDir)) {
            for (Path file : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(file)) continue;
                if (underIncremental(actionsDir, file)) continue;
                long size = Files.size(file);
                bytes += size;
                if (keysDir.equals(file.getParent())) {
                    keyFiles.add(new KeyFile(
                            file, size, Files.getLastModifiedTime(file).toMillis()));
                }
            }
        }

        // A null pointer set means "could not tell": every key then counts as current, so the
        // secondary sort degrades to plain oldest-first instead of marking the whole cache free.
        Set<String> live = livePointers(tasksDir);
        List<Entry> entries = new ArrayList<>();
        Map<String, Integer> refs = new HashMap<>();
        for (KeyFile keyFile : keyFiles) {
            String body;
            try {
                body = Files.readString(keyFile.file(), StandardCharsets.UTF_8);
            } catch (NoSuchFileException vanished) {
                // A concurrent ActionCache.trimGenerations dropped it between the walk and here.
                // Any other read fault propagates: without the record we cannot tell which blobs it
                // roots, and guessing "none" would let another entry's eviction unlink them.
                continue;
            }
            Set<String> shas = new LinkedHashSet<>();
            Matcher outputs = OUTPUT_SHA.matcher(body);
            while (outputs.find()) shas.add(outputs.group(1).toLowerCase(Locale.ROOT));
            for (String sha : shas) refs.merge(sha, 1, Integer::sum);

            Matcher task = TASK.matcher(body);
            // ActionCache always renders TASK first, so a record without one is foreign content,
            // not a jk entry: there is no pointer or generation list to unlink with it. Its shas
            // stay counted above, which is what CacheRoots.collect does with the same text — the
            // prune must not unlink a blob the same pass's sweep considers rooted.
            if (!task.find()) continue;
            String actionKey = keyFile.file().getFileName().toString();
            boolean superseded = live != null && !live.contains(actionKey);
            entries.add(new Entry(actionKey, task.group(1), keyFile.bytes(), keyFile.mtime(), shas, superseded));
        }
        return new Scan(bytes, entries, refs);
    }

    /** True when {@code file} sits inside one of the separately-budgeted Zinc analysis trees. */
    private static boolean underIncremental(Path actionsDir, Path file) {
        Path relative = actionsDir.relativize(file);
        return relative.getNameCount() > 0
                && INCREMENTAL_DIRS.contains(relative.getName(0).toString());
    }

    /**
     * Every action key some task still points at: the current {@code tasks/<taskId>} pointer plus
     * the generations a Class-C task retains. Anything else is superseded — its inputs have changed,
     * so no future lookup can name it — and goes first. {@code null} when the pointers could not be
     * read, which the caller reads as "assume nothing is superseded".
     */
    private static Set<String> livePointers(Path tasksDir) throws IOException {
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
                for (String line : lines) {
                    String key = line.trim();
                    if (!key.isEmpty()) live.add(key);
                }
            }
        }
        return live;
    }

    /**
     * Window then budget over the Zinc analysis trees, a task directory at a time. The {@code zinc}
     * file's mtime is the last compile that wrote it, so no stamping is needed here — unlike a key
     * record, incremental state is rewritten by every use.
     */
    private static Incremental pruneIncremental(Path actionsDir, Policy policy, long now, long grace, boolean dryRun)
            throws IOException {
        record Tree(Path dir, long bytes, long mtime) {}
        List<Tree> trees = new ArrayList<>();
        long used = 0L;
        for (String name : INCREMENTAL_DIRS) {
            Path root = actionsDir.resolve(name);
            if (!Files.isDirectory(root)) continue;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
                for (Path dir : stream) {
                    if (!Files.isDirectory(dir)) continue;
                    long[] size = {0L};
                    long[] newest = {0L};
                    try (Stream<Path> walk = Files.walk(dir)) {
                        for (Path file : (Iterable<Path>) walk::iterator) {
                            if (!Files.isRegularFile(file)) continue;
                            size[0] += Files.size(file);
                            newest[0] = Math.max(
                                    newest[0], Files.getLastModifiedTime(file).toMillis());
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

    private static int countFiles(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            return (int) walk.filter(Files::isRegularFile).count();
        } catch (NoSuchFileException vanished) {
            return 0;
        }
    }

    /** Depth-first delete of one task's analysis directory; returns the regular files unlinked. */
    private static int deleteTree(Path dir) throws IOException {
        int files = 0;
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(dir)) {
            paths = walk.sorted(Comparator.reverseOrder()).toList();
        } catch (NoSuchFileException vanished) {
            return 0;
        }
        for (Path path : paths) {
            boolean regular = Files.isRegularFile(path);
            if (Files.deleteIfExists(path) && regular) files++;
        }
        return files;
    }

    /** Unlink one entry's on-disk footprint: key record, task pointer, generation-list line. */
    private static void deleteKey(Path keysDir, Path tasksDir, String actionKey, String taskId) throws IOException {
        Files.deleteIfExists(keysDir.resolve(actionKey));
        Path pointer = tasksDir.resolve(taskId);
        if (Files.isRegularFile(pointer)) {
            // Deliberately racy compare-and-delete: POSIX has no atomic "delete if contents match",
            // and losing to a concurrent store only costs a re-run of one task.
            String current = Files.readString(pointer, StandardCharsets.UTF_8).trim();
            if (actionKey.equals(current)) Files.deleteIfExists(pointer);
        }
        Path gens = HeavyActionPolicy.gensFile(tasksDir, taskId);
        if (!Files.isRegularFile(gens)) return;
        List<String> kept = new ArrayList<>();
        for (String line : Files.readAllLines(gens, StandardCharsets.UTF_8)) {
            String key = line.trim();
            if (!key.isEmpty() && !key.equals(actionKey)) kept.add(key);
        }
        // Atomic like the writer side (ActionCache.trimGenerations): a second engine sharing this
        // cache root holds neither the cache gate nor .prune.lock, so a torn plain write here would
        // clobber its concurrent update.
        if (kept.isEmpty()) Files.deleteIfExists(gens);
        else AtomicWrites.replace(gens, String.join("\n", kept) + "\n");
    }
}
