// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api.runtime;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.time.Clock;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * The walks of a source tree a process has taken, kept while no directory they listed has changed.
 * A file is added, removed or renamed only by writing its directory, which moves the directory's
 * mtime; so a walk holds while every directory it entered still reads its recorded mtime and no
 * file whose appearance would stop a pruning has appeared. Statting the directories is a fraction of
 * listing them and every file in them.
 *
 * <p>A directory written within {@link #RACY_MILLIS} of the walk might change again inside the same
 * mtime tick, so a walk that saw one is never reused.
 */
final class TreeWalks {

    /** Coarser than any mtime granularity a source tree is kept on (FAT's two seconds). */
    static final long RACY_MILLIS = 2_000;

    private static final int KEPT = 8;

    private static final Map<Key, Holder> HOLDERS = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, Holder> eldest) {
            return size() > KEPT;
        }
    };

    private TreeWalks() {}

    /** Every regular file under {@code dirs}, root-relative and sorted, and the glob matches over it. */
    static final class Listing {
        final List<String> files;
        final ConcurrentHashMap<String, List<String>> matches = new ConcurrentHashMap<>();

        Listing(List<String> files) {
            this.files = files;
        }
    }

    /** What decides a walk; {@code dirs} absolute and normalized. */
    private record Key(Path root, List<Path> dirs, @Nullable String outputDir) {}

    /** A walk and what proves it still holds. */
    private record Walk(Listing listing, Map<Path, FileTime> mtimes, List<Path> absent, boolean reusable) {}

    /**
     * The listing of {@code dirs} under {@code root}, pruning what {@code skipped} accepts: the last
     * one taken when it still holds, else a fresh walk. {@code outputDir} is part of the key because
     * {@code skipped} reads it; {@code probe} names, for a pruned directory, the file whose
     * appearance would stop its pruning, or {@code null} when nothing in the tree would.
     */
    static Listing listing(
            Path root,
            List<Path> dirs,
            @Nullable String outputDir,
            Predicate<Path> skipped,
            Function<Path, @Nullable Path> probe) {
        Holder h;
        synchronized (HOLDERS) {
            h = HOLDERS.computeIfAbsent(new Key(root, List.copyOf(dirs), outputDir), k -> new Holder());
        }
        return h.listing(root, dirs, skipped, probe);
    }

    /** Take a fresh walk, keeping nothing: a view that judges the tree once. */
    static Listing walk(Path root, List<Path> dirs, Predicate<Path> skipped) {
        return walk(root, dirs, skipped, d -> null).listing;
    }

    /** Forget every walk; a test seam. */
    static void clear() {
        synchronized (HOLDERS) {
            HOLDERS.clear();
        }
    }

    private static final class Holder {
        private @Nullable Walk last;

        synchronized Listing listing(
                Path root, List<Path> dirs, Predicate<Path> skipped, Function<Path, @Nullable Path> probe) {
            Walk w = last;
            if (w != null && w.reusable && holds(w)) return w.listing;
            w = walk(root, dirs, skipped, probe);
            last = w;
            return w.listing;
        }
    }

    private static boolean holds(Walk w) {
        for (Path p : w.absent) if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return false;
        for (var e : w.mtimes.entrySet()) {
            try {
                if (!Files.getLastModifiedTime(e.getKey(), LinkOption.NOFOLLOW_LINKS)
                        .equals(e.getValue())) return false;
            } catch (IOException gone) {
                return false;
            }
        }
        return true;
    }

    private static Walk walk(
            Path root, List<Path> dirs, Predicate<Path> skipped, Function<Path, @Nullable Path> probe) {
        long started = Clock.SYSTEM.millis();
        TreeSet<String> files = new TreeSet<>();
        Map<Path, FileTime> mtimes = new HashMap<>();
        List<Path> absent = new ArrayList<>();
        try {
            for (Path dir : dirs) {
                if (!Files.isDirectory(dir)) {
                    absent.add(dir);
                    continue;
                }
                Predicate<Path> skip = d -> {
                    if (!skipped.test(d)) return false;
                    Path p = probe.apply(d);
                    if (p != null) absent.add(p);
                    return true;
                };
                PathUtil.forEachRegularFile(dir, skip, new PathUtil.FileVisit() {
                    @Override
                    public void accept(Path file, BasicFileAttributes attrs) {
                        files.add(rel(root, file));
                    }

                    @Override
                    public void directory(Path d, BasicFileAttributes attrs) {
                        mtimes.put(d, attrs.lastModifiedTime());
                    }
                });
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        boolean reusable = true;
        for (FileTime t : mtimes.values()) {
            if (t.toMillis() > started - RACY_MILLIS) {
                reusable = false;
                break;
            }
        }
        return new Walk(new Listing(List.copyOf(files)), Map.copyOf(mtimes), List.copyOf(absent), reusable);
    }

    static String rel(Path root, Path f) {
        return root.relativize(f.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }
}
