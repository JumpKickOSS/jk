// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.host.time.Clock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/**
 * The one rule jk never breaks: it does not fill a disk. A volume is <em>critical</em> when less
 * than {@link #CRITICAL_FREE_RATIO} of it or less than {@link #CRITICAL_FREE_BYTES} is free; on a
 * critical volume jk writes no {@linkplain #HEAVY_BYTES heavy} blob to its caches, whatever the
 * cache would gain, and the engine prunes at once ({@link #refused} wakes it).
 *
 * <p>Retention windows shrink with {@link DiskPressure} well before this; the guard is the floor
 * under them. A probe is one {@code statfs}, remembered for {@link #PROBE_MILLIS} per path, so a
 * caller may ask before every write.
 */
public final class DiskGuard {

    private static final long GIB = 1024L * 1024L * 1024L;

    /** Below this share of the volume free, the volume is critical. */
    public static final double CRITICAL_FREE_RATIO = 0.10;

    /** Below this many bytes free, the volume is critical, however large it is. */
    public static final long CRITICAL_FREE_BYTES = 2 * GIB;

    /**
     * A blob this large or larger is heavy: a native image, a fat jar, a jlink image. Expensive to
     * hold, rarely reused after a day; the caches treat it apart from everything else.
     */
    public static final long HEAVY_BYTES = 32L * 1024 * 1024;

    /** How long one probe of a path is trusted. */
    static final long PROBE_MILLIS = 5_000;

    private record Probe(long atMillis, JkCacheConfig.@Nullable DiskSpace space) {}

    private static final Map<Path, Probe> PROBES = new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile LongSupplier clock = Clock.SYSTEM::millis;

    private DiskGuard() {}

    /** True when {@code space} is critical; no probe is never critical. */
    public static boolean critical(JkCacheConfig.@Nullable DiskSpace space) {
        if (space == null || space.totalBytes() <= 0) return false;
        return space.freeBytes() < CRITICAL_FREE_BYTES
                || (double) space.freeBytes() / space.totalBytes() < CRITICAL_FREE_RATIO;
    }

    /** True when the volume holding {@code path} is critical, from a probe at most {@link #PROBE_MILLIS} old. */
    public static boolean critical(Path path) {
        return critical(space(path));
    }

    /** The space of the volume holding {@code path}, from a probe at most {@link #PROBE_MILLIS} old. */
    public static JkCacheConfig.@Nullable DiskSpace space(Path path) {
        long now = clock.getAsLong();
        Probe p = PROBES.get(path);
        if (p == null || now - p.atMillis() >= PROBE_MILLIS) {
            p = new Probe(now, JkCacheConfig.DiskSpace.probe(path));
            PROBES.put(path, p);
        }
        return p.space();
    }

    /**
     * True when a {@code bytes}-sized blob may go into a cache on the volume holding {@code path}: it
     * is not heavy, or the volume is not critical. A refusal wakes every listener.
     */
    public static boolean admits(Path path, long bytes) {
        if (bytes < HEAVY_BYTES || !critical(path)) return true;
        refused();
        return false;
    }

    /** As {@link #admits(Path, long)} for {@code files} about to be written; a file that cannot be sized counts nothing. */
    public static boolean admits(Path path, Collection<Path> files) {
        long total = 0;
        for (Path f : files) {
            try {
                if (Files.isRegularFile(f)) total += Files.size(f);
            } catch (IOException unreadable) {
                // a file that cannot be sized is stored or refused by the store itself
            }
        }
        return admits(path, total);
    }

    /** A write was refused for want of space: wake whatever prunes ({@link #onRefused}). */
    public static void refused() {
        for (Runnable l : LISTENERS) {
            try {
                l.run();
            } catch (RuntimeException ignored) {
                // a listener that fails must not fail the write path that asked
            }
        }
    }

    /** Run {@code listener} whenever a write is refused for want of space. */
    public static void onRefused(Runnable listener) {
        LISTENERS.add(listener);
    }

    /** Stop running {@code listener}. */
    public static void removeListener(Runnable listener) {
        LISTENERS.remove(listener);
    }

    /** "1.4 GiB free of 120 GiB (1%)", for the line that says why. */
    public static String describe(JkCacheConfig.@Nullable DiskSpace space) {
        if (space == null || space.totalBytes() <= 0) return "free space unknown";
        long pct = Math.round(100.0 * space.freeBytes() / space.totalBytes());
        return gib(space.freeBytes()) + " free of " + gib(space.totalBytes()) + " (" + pct + "%)";
    }

    private static String gib(long bytes) {
        return String.format(Locale.ROOT, "%.1f GiB", bytes / (double) GIB);
    }

    /** Test seam: the clock probes are aged by, and a fresh probe table. */
    static void resetForTest(LongSupplier testClock) {
        clock = testClock;
        PROBES.clear();
        LISTENERS.clear();
    }

    /** Test seam: what a probe of {@code path} answers until it ages out. */
    public static void putProbeForTest(Path path, JkCacheConfig.@Nullable DiskSpace space) {
        PROBES.put(path, new Probe(clock.getAsLong(), space));
    }
}
