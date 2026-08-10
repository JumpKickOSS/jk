// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Reclaim per-file format stamps under {@code <cacheRoot>/format-stamps/}. Invoked by {@code jk
 * cache prune} (and the engine's 12 h idle-boundary prune) alongside the other cache-tier steps.
 *
 * <p>Format stamps are empty marker files keyed by SHA-256 of (stamp version + config descriptor +
 * file bytes). They become unreachable when content or formatter config changes, and hits only
 * refresh mtime — so growth is orphaned content-address keys plus a live working set.
 *
 * <p>Policy (two phases):
 *
 * <ol>
 *   <li><strong>Age</strong> — delete stamps older than {@link #DEFAULT_TTL} (7 days).
 *   <li><strong>Count cap (LRU)</strong> — if survivors still exceed {@link #resolveMaxFiles()},
 *       delete oldest-by-mtime until at the cap. Default cap is {@link #DEFAULT_MAX_FILES}
 *       (512 000); when {@code CI=1} or {@code CI=true}, {@link #CI_MAX_FILES} (1 000 000).
 * </ol>
 *
 * <p>Losing a still-needed stamp costs at most one extra format pass to re-stamp. Empty shard
 * directories ({@code AB/CD/}) are removed after deletions.
 */
public final class FormatStampGc {

    /** Stamps unused for this long are deleted regardless of count. */
    public static final Duration DEFAULT_TTL = Duration.ofDays(7);

    /** Default max stamp files after the age pass (dev / non-CI). */
    public static final int DEFAULT_MAX_FILES = 512_000;

    /** Max stamp files when {@code CI=1} or {@code CI=true}. */
    public static final int CI_MAX_FILES = 1_000_000;

    private FormatStampGc() {}

    /**
     * @param deleted total stamp files removed (age + cap)
     * @param freedBytes sum of file sizes removed (empty stamps → usually 0 content bytes)
     * @param deletedByAge removed because mtime older than TTL
     * @param deletedByCap removed to enforce the count cap (oldest first)
     */
    public record Report(int deleted, long freedBytes, int deletedByAge, int deletedByCap) {
        public Report(int deleted, long freedBytes) {
            this(deleted, freedBytes, deleted, 0);
        }
    }

    /** Cap for this process: {@link #CI_MAX_FILES} when CI is set, else {@link #DEFAULT_MAX_FILES}. */
    public static int resolveMaxFiles() {
        return resolveMaxFiles(System::getenv);
    }

    /** Testable: {@code CI=1} / {@code CI=true} (case-insensitive) → 1M, otherwise 512k. */
    public static int resolveMaxFiles(Function<String, String> env) {
        String ci = env.apply("CI");
        if ("1".equals(ci) || (ci != null && "true".equalsIgnoreCase(ci))) {
            return CI_MAX_FILES;
        }
        return DEFAULT_MAX_FILES;
    }

    /**
     * Sweep with {@link #DEFAULT_TTL} and {@link #resolveMaxFiles()} from the process environment.
     */
    public static Report sweep(Path cacheRoot, boolean dryRun) throws IOException {
        return sweep(cacheRoot, DEFAULT_TTL, resolveMaxFiles(), dryRun);
    }

    /**
     * Walk {@code <cacheRoot>/format-stamps/}: drop entries older than {@code ttl}, then if the
     * remainder exceeds {@code maxFiles} drop oldest-by-mtime until at the cap. {@code maxFiles <=
     * 0} means no count cap (age-only). Cleans empty shard directories afterwards.
     */
    public static Report sweep(Path cacheRoot, Duration ttl, int maxFiles, boolean dryRun) throws IOException {
        Path stampsDir = cacheRoot.resolve("format-stamps");
        if (!Files.isDirectory(stampsDir)) return new Report(0, 0L, 0, 0);
        long cutoff = System.currentTimeMillis() - ttl.toMillis();

        record Entry(Path path, long mtime, long size) {}
        List<Entry> survivors = new ArrayList<>();
        int deletedByAge = 0;
        long freedBytes = 0L;

        try (Stream<Path> stream = Files.walk(stampsDir)) {
            for (Path file : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator) {
                long mtime = Files.getLastModifiedTime(file).toMillis();
                long size = Files.size(file);
                if (mtime < cutoff) {
                    freedBytes += size;
                    if (!dryRun) Files.deleteIfExists(file);
                    deletedByAge++;
                } else {
                    survivors.add(new Entry(file, mtime, size));
                }
            }
        }

        int deletedByCap = 0;
        if (maxFiles > 0 && survivors.size() > maxFiles) {
            survivors.sort(Comparator.comparingLong(Entry::mtime));
            int toDelete = survivors.size() - maxFiles;
            for (int i = 0; i < toDelete; i++) {
                Entry e = survivors.get(i);
                freedBytes += e.size();
                if (!dryRun) Files.deleteIfExists(e.path());
                deletedByCap++;
            }
        }

        int deleted = deletedByAge + deletedByCap;
        // After deleting, remove any now-empty shard directories (deepest-first).
        if (!dryRun && deleted > 0) {
            try (Stream<Path> dirs =
                    Files.walk(stampsDir).filter(Files::isDirectory).sorted(Comparator.reverseOrder())) {
                for (Path dir : (Iterable<Path>) dirs::iterator) {
                    if (dir.equals(stampsDir)) continue;
                    try (Stream<Path> contents = Files.list(dir)) {
                        if (contents.findFirst().isEmpty()) Files.deleteIfExists(dir);
                    }
                }
            }
        }

        return new Report(deleted, freedBytes, deletedByAge, deletedByCap);
    }
}
