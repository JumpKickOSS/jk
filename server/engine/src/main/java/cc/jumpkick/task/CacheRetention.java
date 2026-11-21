// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkCacheConfig;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.time.Clock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Applies the {@link CacheTier} bound of every {@link CacheTree}, then reclaims whatever {@link
 * CacheTree} does not name.
 *
 * <p>One entry point, called from one place: {@code CachePlans.pruneBuildPlan}, which the engine
 * runs at the idle boundary after a build and on its 12 h maintenance tick. There is no second call
 * site and no CLI-only path — that is the whole point of the class.
 *
 * <p>No window and no cap takes an entry younger than {@link Cas#MIN_AGE_FOR_SWEEP}. The two
 * reset instruments do, because what they clear is a hard link or a derived snapshot that the next
 * build recreates, and half a tier is not a state worth aiming for. The prune holds the cache
 * locks, but a second engine sharing the root holds none of them and may be mid-write, so every
 * bound here is soft: a tier left above its cap under continuous write pressure is a legitimate
 * outcome, not a failure.
 */
public final class CacheRetention {

    private CacheRetention() {}

    /**
     * @param deletedFiles regular files unlinked across every tier and the whitelist sweep
     * @param freedBytes their apparent bytes — see {@link CacheTier} on why that is not the whole
     *     story for the small-file tiers, and why those are capped by count instead
     * @param finalActionBytes what {@link ActionCachePrune} left in the action tier, for the
     *     cadence stamp
     * @param unknownEntries top-level names the table did not recognise, reclaimed by the sweep
     */
    public record Report(int deletedFiles, long freedBytes, long finalActionBytes, List<String> unknownEntries) {
        static final Report EMPTY = new Report(0, 0L, 0L, List.of());
    }

    /**
     * Run every tier, then the whitelist sweep. {@code alreadyFreedShas} are blobs a same-pass
     * {@link CasSweep} claimed, handed to {@link ActionCachePrune} so a dry run reports the same
     * numbers as a real one.
     */
    public static Report sweep(Path cacheRoot, Cas cacheCas, Set<String> alreadyFreedShas, boolean dryRun)
            throws IOException {
        return sweep(cacheRoot, cacheCas, alreadyFreedShas, dryRun, PruneYield.NEVER);
    }

    /**
     * As {@link #sweep(Path, Cas, Set, boolean)}, stopping at {@code yield}'s say-so between tiers
     * and inside the action tier ({@link ActionCachePrune}); a tier's own instrument runs whole.
     */
    public static Report sweep(
            Path cacheRoot, Cas cacheCas, Set<String> alreadyFreedShas, boolean dryRun, PruneYield yield)
            throws IOException {
        return sweep(cacheRoot, cacheCas, alreadyFreedShas, dryRun, Probe.REAL, Map.of(), yield);
    }

    /**
     * What the pass reads of one entry, from the attributes its walk already holds. A test counts
     * the questions to show a pass that has no use for an answer never asks for it.
     */
    interface Probe {

        Probe REAL = new Probe() {
            @Override
            public long mtime(BasicFileAttributes entry) {
                return entry.lastModifiedTime().toMillis();
            }

            @Override
            public long size(BasicFileAttributes entry) {
                return entry.size();
            }
        };

        long mtime(BasicFileAttributes entry);

        long size(BasicFileAttributes entry);
    }

    /**
     * The same pass with the filesystem questions and the table's numbers substituted: {@code
     * caps} overrides a tier's {@link Bound}, so a test can make a cap bind without seeding
     * 32,768 entries to do it.
     */
    static Report sweep(
            Path cacheRoot,
            Cas cacheCas,
            Set<String> alreadyFreedShas,
            boolean dryRun,
            Probe probe,
            Map<CacheTree, Bound> caps)
            throws IOException {
        return sweep(cacheRoot, cacheCas, alreadyFreedShas, dryRun, probe, caps, PruneYield.NEVER);
    }

    private static Report sweep(
            Path cacheRoot,
            Cas cacheCas,
            Set<String> alreadyFreedShas,
            boolean dryRun,
            Probe probe,
            Map<CacheTree, Bound> caps,
            PruneYield yield)
            throws IOException {
        if (!Files.isDirectory(cacheRoot)) return Report.EMPTY;
        long now = Clock.SYSTEM.millis();
        long grace = Cas.MIN_AGE_FOR_SWEEP.toMillis();

        int files = 0;
        long bytes = 0L;
        long finalActionBytes = 0L;

        for (CacheTree tier : CacheTree.values()) {
            yield.check();
            Bound bound = caps.getOrDefault(tier, CacheTier.bound(tier));
            Path root = tier.under(cacheRoot);
            switch (bound.kind()) {
                case DELEGATED -> {
                    // Both delegated tiers are one pass over one Policy; run it on ACTIONS only.
                    if (tier != CacheTree.ACTIONS) continue;
                    var policy = ActionCachePrune.Policy.of(JkCacheConfig.resolve());
                    var report = ActionCachePrune.run(cacheRoot, cacheCas, policy, alreadyFreedShas, dryRun, yield);
                    files += (int) report.totalDeletedFiles();
                    bytes += report.totalFreedBytes();
                    finalActionBytes = report.finalBytes();
                }
                case UNBOUNDED -> {
                    // Present so the table is total and the sweep below spares it.
                }
                case FILES -> {
                    Tally t = sweepFiles(root, bound, now, grace, dryRun, probe);
                    files += t.files();
                    bytes += t.bytes();
                }
                case SUBTREES -> {
                    Tally t = sweepSubtrees(root, bound, now, grace, dryRun, probe);
                    files += t.files();
                    bytes += t.bytes();
                }
                case NESTED_SUBTREES -> {
                    Tally t = strays(root, now, grace, dryRun);
                    for (Child parent : children(root)) {
                        if (parent.directory())
                            t = t.plus(sweepSubtrees(parent.path(), bound, now, grace, dryRun, probe));
                    }
                    files += t.files();
                    bytes += t.bytes();
                }
            }
        }

        Tally unknown = new Tally(0, 0L);
        List<String> names = new ArrayList<>();
        Set<String> known = CacheTree.entries();
        for (Child child : children(cacheRoot)) {
            yield.check();
            Path entry = child.path();
            String name = entry.getFileName().toString();
            if (known.contains(name)) continue;
            if (age(entry, now) < grace) continue;
            names.add(name);
            unknown = unknown.plus(delete(entry, dryRun));
        }
        files += unknown.files();
        bytes += unknown.bytes();

        return new Report(files, bytes, finalActionBytes, List.copyOf(names));
    }

    // ---------------------------------------------------------------- instruments

    /** Window then cap over a tree of files. One victim is one file. */
    private static Tally sweepFiles(Path root, Bound bound, long now, long grace, boolean dryRun, Probe probe)
            throws IOException {
        if (!Files.isDirectory(root)) return new Tally(0, 0L);
        if (bound.cap() instanceof Bound.Cap.ResetAlways) return delete(root, dryRun);

        // Below a count cap the pass must not pay for a stat walk it will not use: hash-memo is
        // ~16k files, and enumerating names is a few ms against ~20 ms to stat them all.
        if (bound.window() == null && bound.cap() instanceof Bound.Cap.Count(int max)) {
            if (countFiles(root) <= max) return new Tally(0, 0L);
        }

        // A reset tier has no order to put its entries in, so it never asks how old they are:
        // the answer could only be used to choose a victim it does not choose.
        boolean ranks = bound.window() != null || bound.cap() instanceof Bound.Cap.Count;

        List<Entry> entries = new ArrayList<>();
        PathUtil.forEachRegularFile(
                root,
                (file, attrs) -> entries.add(new Entry(file, ranks ? probe.mtime(attrs) : 0L, probe.size(attrs))));
        long total = 0L;
        for (Entry e : entries) total += e.size();

        Tally out = new Tally(0, 0L);
        List<Entry> survivors = new ArrayList<>();
        for (Entry e : entries) {
            if (expired(bound.window(), now, e.mtime()) && now - e.mtime() >= grace) {
                out = out.plus(delete(e.path(), dryRun));
                total -= e.size();
            } else {
                survivors.add(e);
            }
        }

        switch (bound.cap()) {
            case Bound.Cap.Count(int max) -> {
                if (max > 0 && survivors.size() > max) {
                    survivors.sort(Comparator.comparingLong(Entry::mtime));
                    for (int i = 0; i < survivors.size() - max; i++) {
                        Entry e = survivors.get(i);
                        if (now - e.mtime() < grace) continue;
                        out = out.plus(delete(e.path(), dryRun));
                    }
                }
            }
            case Bound.Cap.ResetOverBytes(long budget) -> {
                if (budget > 0 && total > budget) out = out.plus(delete(root, dryRun));
            }
            // Took the whole tier at the top of the method, before paying for the stat walk.
            case Bound.Cap.ResetAlways ignored -> {}
            // The window was the whole policy.
            case Bound.Cap.None ignored -> {}
        }
        if (!dryRun && out.files() > 0) pruneEmptyDirs(root);
        return out;
    }

    /**
     * Reclaim what is not a tree in a tier of trees. Every tier laid out this way keys itself by a
     * directory per entry, so a loose file beside them is residue — most often the tarball an
     * interrupted base-image extract left behind, which no window and no cap is written against.
     */
    private static Tally strays(Path root, long now, long grace, boolean dryRun) throws IOException {
        Tally out = new Tally(0, 0L);
        for (Child child : children(root)) {
            if (child.directory()) continue;
            if (age(child.path(), now) < grace) continue;
            out = out.plus(delete(child.path(), dryRun));
        }
        return out;
    }

    /** Window then cap over a directory of self-contained trees. One victim is one child tree. */
    private static Tally sweepSubtrees(Path root, Bound bound, long now, long grace, boolean dryRun, Probe probe)
            throws IOException {
        if (!Files.isDirectory(root)) return new Tally(0, 0L);
        record Tree(Path dir, long mtime) {}
        List<Tree> trees = new ArrayList<>();
        for (Child child : children(root)) {
            if (child.directory()) trees.add(new Tree(child.path(), newestMtime(child.path(), probe)));
        }

        Tally out = strays(root, now, grace, dryRun);
        List<Tree> survivors = new ArrayList<>();
        for (Tree t : trees) {
            if (expired(bound.window(), now, t.mtime()) && now - t.mtime() >= grace) {
                out = out.plus(delete(t.dir(), dryRun));
            } else {
                survivors.add(t);
            }
        }
        if (bound.cap() instanceof Bound.Cap.Count(int max) && max > 0 && survivors.size() > max) {
            survivors.sort(Comparator.comparingLong(Tree::mtime));
            for (int i = 0; i < survivors.size() - max; i++) {
                Tree t = survivors.get(i);
                if (now - t.mtime() < grace) continue;
                out = out.plus(delete(t.dir(), dryRun));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- helpers

    private record Entry(Path path, long mtime, long size) {}

    private record Tally(int files, long bytes) {
        Tally plus(Tally other) {
            return new Tally(files + other.files(), bytes + other.bytes());
        }
    }

    private static boolean expired(@Nullable Duration window, long now, long mtime) {
        return window != null && !window.isZero() && !window.isNegative() && now - mtime >= window.toMillis();
    }

    /** A child of a tier, and whether it is a directory or a link to one. */
    private record Child(Path path, boolean directory) {}

    /** The listing says which children are directories; only a link is resolved to ask. */
    private static List<Child> children(Path dir) throws IOException {
        List<Child> out = new ArrayList<>();
        PathUtil.forEachChild(dir, (p, attrs) -> {
            out.add(new Child(p, attrs.isDirectory() || !attrs.isRegularFile() && Files.isDirectory(p)));
            return true;
        });
        return out;
    }

    /** One listing per shard and nothing asked of an entry, for the common below-the-cap pass. */
    private static long countFiles(Path root) throws IOException {
        long n = 0;
        for (Child shard : children(root)) {
            if (shard.directory()) n += countFiles(shard.path());
            else n++;
        }
        return n;
    }

    private static long newestMtime(Path dir, Probe probe) throws IOException {
        long[] newest = {0L};
        PathUtil.forEachRegularFile(dir, (f, attrs) -> newest[0] = Math.max(newest[0], probe.mtime(attrs)));
        return newest[0];
    }

    private static long age(Path p, long now) {
        try {
            return now - Files.getLastModifiedTime(p).toMillis();
        } catch (IOException unreadable) {
            return 0L; // unreadable is not evidence of staleness
        }
    }

    /** Unlink a file or a whole tree, returning what it cost. Counts regular files only. */
    private static Tally delete(Path target, boolean dryRun) throws IOException {
        if (!Files.exists(target)) return new Tally(0, 0L);
        if (Files.isRegularFile(target)) {
            long size = sizeOf(target);
            if (!dryRun) Files.deleteIfExists(target);
            return new Tally(1, size);
        }
        PathUtil.Removed removed;
        if (dryRun) {
            removed = PathUtil.measureTrees(List.of(target));
        } else {
            removed = new PathUtil.Removed();
            PathUtil.deleteRecursivelyOrThrow(target, removed);
        }
        return new Tally((int) removed.files(), removed.bytes());
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException gone) {
            return 0L;
        }
    }

    /** Drop shard directories left empty by the pass, deepest first. */
    private static void pruneEmptyDirs(Path root) throws IOException {
        PathUtil.pruneTree(root, file -> true);
    }
}
