// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.cache.Cas;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Applies every {@link CacheTier}'s {@link Bound}, then reclaims whatever the table does not name.
 *
 * <p>One entry point, called from one place: {@code CachePlans.pruneBuildPlan}, which the engine
 * runs at the idle boundary after a build and on its 12 h maintenance tick. There is no second call
 * site and no CLI-only path — that is the whole point of the class.
 *
 * <p>No window and no cap takes an entry younger than {@link Sweep#MIN_AGE_FOR_SWEEP}. The two
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
        return sweep(cacheRoot, cacheCas, alreadyFreedShas, dryRun, Probe.REAL, Map.of());
    }

    /**
     * What the pass is allowed to ask about one entry. Enumeration — {@code readdir}, plus one
     * {@code isDirectory} per shard — is the floor and does not come through here; everything
     * that costs per entry does, so a test can assert the below-cap pass asked nothing at all.
     */
    interface Probe {

        Probe REAL = new Probe() {
            @Override
            public long mtime(Path entry) throws IOException {
                return Files.getLastModifiedTime(entry).toMillis();
            }

            @Override
            public long size(Path entry) throws IOException {
                return Files.size(entry);
            }

            @Override
            public String read(Path entry) throws IOException {
                return Files.readString(entry, StandardCharsets.UTF_8);
            }
        };

        long mtime(Path entry) throws IOException;

        long size(Path entry) throws IOException;

        String read(Path entry) throws IOException;
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
            Map<CacheTier, Bound> caps)
            throws IOException {
        if (!Files.isDirectory(cacheRoot)) return Report.EMPTY;
        long now = System.currentTimeMillis();
        long grace = Sweep.MIN_AGE_FOR_SWEEP.toMillis();

        int files = 0;
        long bytes = 0L;
        long finalActionBytes = 0L;

        for (CacheTier tier : CacheTier.values()) {
            Bound bound = caps.getOrDefault(tier, tier.bound());
            Path root = cacheRoot.resolve(tier.entry());
            switch (bound.kind()) {
                case DELEGATED -> {
                    // Both delegated tiers are one pass over one Policy; run it on ACTIONS only.
                    if (tier != CacheTier.ACTIONS) continue;
                    var policy = ActionCachePrune.Policy.of(cc.jumpkick.config.JkCacheConfig.resolve());
                    var report = ActionCachePrune.run(cacheRoot, cacheCas, policy, alreadyFreedShas, dryRun);
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
                    Tally t = new Tally(0, 0L);
                    for (Path parent : children(root)) {
                        if (!Files.isDirectory(parent)) continue;
                        t = t.plus(sweepSubtrees(parent, bound, now, grace, dryRun, probe));
                    }
                    files += t.files();
                    bytes += t.bytes();
                }
            }
        }

        Tally unknown = new Tally(0, 0L);
        List<String> names = new ArrayList<>();
        Set<String> known = CacheTier.knownEntries();
        for (Path entry : children(cacheRoot)) {
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
        if (bound.window() == null && bound.cap() instanceof Bound.Cap.Count(int max, var rule)) {
            if (countFiles(root) <= max) return new Tally(0, 0L);
        }

        // A reset tier has no order to put its entries in, so it never asks how old they are:
        // the answer could only be used to choose a victim it does not choose.
        boolean ranks = bound.window() != null || bound.cap() instanceof Bound.Cap.Count;

        List<Entry> entries = new ArrayList<>();
        long total = 0L;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                try {
                    long size = probe.size(file);
                    entries.add(new Entry(file, ranks ? probe.mtime(file) : 0L, size));
                    total += size;
                } catch (NoSuchFileException vanished) {
                    // another engine got there first
                }
            }
        }

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
            case Bound.Cap.Count(int max, Bound.VictimRule rule) -> {
                if (max > 0 && survivors.size() > max && rule == Bound.VictimRule.SUPERSEDED_THEN_OLDEST) {
                    List<Entry> live = new ArrayList<>(survivors.size());
                    for (Entry e : survivors) {
                        if (now - e.mtime() >= grace && superseded(e.path(), probe)) {
                            out = out.plus(delete(e.path(), dryRun));
                        } else {
                            live.add(e);
                        }
                    }
                    survivors = live;
                }
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
            default -> {}
        }
        if (!dryRun && out.files() > 0) pruneEmptyDirs(root);
        return out;
    }

    /**
     * Whether the source {@code entry} describes is gone, read from the path on its last line.
     *
     * <p>Two ways to answer "no" that are not "the file is there": an entry that records no path
     * says nothing about anything, and a path whose every ancestor is also missing reads as a
     * volume that is not mounted rather than a file that was deleted. Both are kept, because a
     * dead entry costs one dirent while a wrongly-dropped live one costs a re-hash of a file the
     * next build is about to read anyway.
     */
    private static boolean superseded(Path entry, Probe probe) {
        String record;
        try {
            record = probe.read(entry);
        } catch (IOException unreadable) {
            return false;
        }
        int nl = record.lastIndexOf('\n');
        if (nl < 0 || nl == record.length() - 1) return false;
        Path source;
        try {
            source = Path.of(record.substring(nl + 1));
        } catch (InvalidPathException notAPath) {
            return false;
        }
        if (Files.exists(source)) return false;
        for (Path dir = source.getParent(); dir != null && dir.getParent() != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir)) return true;
        }
        return false;
    }

    /** Window then cap over a directory of self-contained trees. One victim is one child tree. */
    private static Tally sweepSubtrees(Path root, Bound bound, long now, long grace, boolean dryRun, Probe probe)
            throws IOException {
        if (!Files.isDirectory(root)) return new Tally(0, 0L);
        record Tree(Path dir, long mtime) {}
        List<Tree> trees = new ArrayList<>();
        for (Path dir : children(root)) {
            if (!Files.isDirectory(dir)) continue;
            trees.add(new Tree(dir, newestMtime(dir, probe)));
        }

        Tally out = new Tally(0, 0L);
        List<Tree> survivors = new ArrayList<>();
        for (Tree t : trees) {
            if (expired(bound.window(), now, t.mtime()) && now - t.mtime() >= grace) {
                out = out.plus(delete(t.dir(), dryRun));
            } else {
                survivors.add(t);
            }
        }
        if (bound.cap() instanceof Bound.Cap.KeepOnly(String live)) {
            for (Tree t : survivors) {
                if (t.dir().getFileName().toString().equals(live)) continue;
                if (now - t.mtime() < grace) continue; // a half-written extract, not a leftover
                out = out.plus(delete(t.dir(), dryRun));
            }
            return out;
        }
        if (bound.cap() instanceof Bound.Cap.Count(int max, var rule) && max > 0 && survivors.size() > max) {
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

    private static boolean expired(Duration window, long now, long mtime) {
        return window != null && !window.isZero() && !window.isNegative() && now - mtime >= window.toMillis();
    }

    private static List<Path> children(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) out.add(p);
        }
        return out;
    }

    /** Name-only enumeration — no {@code stat}, for the common below-the-cap pass. */
    private static long countFiles(Path root) throws IOException {
        long n = 0;
        for (Path shard : children(root)) {
            if (Files.isDirectory(shard)) n += countFiles(shard);
            else n++;
        }
        return n;
    }

    private static long newestMtime(Path dir, Probe probe) throws IOException {
        long newest = 0L;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path f : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                try {
                    newest = Math.max(newest, probe.mtime(f));
                } catch (NoSuchFileException vanished) {
                    // ignore
                }
            }
        } catch (NoSuchFileException vanished) {
            return 0L;
        }
        return newest;
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
        int files = 0;
        long bytes = 0L;
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(target)) {
            paths = walk.sorted(Comparator.reverseOrder()).toList();
        } catch (NoSuchFileException vanished) {
            return new Tally(0, 0L);
        }
        for (Path p : paths) {
            boolean regular = Files.isRegularFile(p);
            long size = regular ? sizeOf(p) : 0L;
            if (dryRun) {
                if (regular) {
                    files++;
                    bytes += size;
                }
                continue;
            }
            if (Files.deleteIfExists(p) && regular) {
                files++;
                bytes += size;
            }
        }
        return new Tally(files, bytes);
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
        try (Stream<Path> dirs = Files.walk(root).filter(Files::isDirectory).sorted(Comparator.reverseOrder())) {
            for (Path dir : (Iterable<Path>) dirs::iterator) {
                if (dir.equals(root)) continue;
                try (Stream<Path> contents = Files.list(dir)) {
                    if (contents.findFirst().isEmpty()) Files.deleteIfExists(dir);
                }
            }
        } catch (NoSuchFileException vanished) {
            // the whole tree went; nothing to tidy
        }
    }
}
