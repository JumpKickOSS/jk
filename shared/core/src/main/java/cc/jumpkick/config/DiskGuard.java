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
 * The one rule jk never breaks: it does not fill a disk. A volume is <em>critical</em> when less is
 * free than its {@linkplain #criticalLine line}: {@link #CRITICAL_FREE_RATIO} of it, but never less
 * than {@link #CRITICAL_FREE_BYTES} nor more than {@link #CRITICAL_RATIO_CAP_BYTES}. On a critical
 * volume jk writes no {@linkplain #HEAVY_BYTES heavy} blob to its caches, whatever the cache would
 * gain, and the engine prunes at once ({@link #refused} wakes it).
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
     * With this many bytes free, the volume is not critical, however small a share of it that is: a
     * heavy blob is a sliver of it, and the retention windows are already at their shortest.
     */
    public static final long CRITICAL_RATIO_CAP_BYTES = 10 * GIB;

    /**
     * A blob this large or larger is heavy: a native image, a fat jar, a jlink image. Expensive to
     * hold, rarely reused after a day; the caches treat it apart from everything else.
     */
    public static final long HEAVY_BYTES = 32L * 1024 * 1024;

    /** How long one probe of a path is trusted. */
    static final long PROBE_MILLIS = 5_000;

    /** A probe, or a test's {@linkplain #pinForTest pin}: pinned, it never ages and answers for its tree. */
    private record Probe(long atMillis, JkCacheConfig.@Nullable DiskSpace space, boolean pinned) {
        Probe(long atMillis, JkCacheConfig.@Nullable DiskSpace space) {
            this(atMillis, space, false);
        }
    }

    private static final Map<Path, Probe> PROBES = new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile LongSupplier clock = Clock.SYSTEM::millis;

    private DiskGuard() {}

    /** True when {@code space} is critical; no probe is never critical. */
    public static boolean critical(JkCacheConfig.@Nullable DiskSpace space) {
        if (space == null || space.totalBytes() <= 0) return false;
        return space.freeBytes() < criticalLine(space.totalBytes());
    }

    /** The free bytes under which a volume of {@code totalBytes} is critical. */
    public static long criticalLine(long totalBytes) {
        long ratio = (long) Math.ceil(totalBytes * CRITICAL_FREE_RATIO);
        return Math.max(CRITICAL_FREE_BYTES, Math.min(CRITICAL_RATIO_CAP_BYTES, ratio));
    }

    /** True when the volume holding {@code path} is critical, from a probe at most {@link #PROBE_MILLIS} old. */
    public static boolean critical(Path path) {
        return critical(space(path));
    }

    /** The space of the volume holding {@code path}, from a probe at most {@link #PROBE_MILLIS} old. */
    public static JkCacheConfig.@Nullable DiskSpace space(Path path) {
        long now = clock.getAsLong();
        Probe p = PROBES.get(path);
        if (p == null || (!p.pinned() && now - p.atMillis() >= PROBE_MILLIS)) {
            Probe pin = pinAbove(path);
            if (pin != null) return pin.space();
            p = new Probe(now, JkCacheConfig.DiskSpace.probe(path));
            PROBES.put(path, p);
        }
        return p.space();
    }

    /** The pin on {@code path}'s nearest pinned ancestor; asked only when a path's probe is due. */
    private static @Nullable Probe pinAbove(Path path) {
        for (Path at = path.toAbsolutePath().normalize(); at != null; at = at.getParent()) {
            Probe p = PROBES.get(at);
            if (p != null && p.pinned()) return p;
        }
        return null;
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

    /** A {@linkplain #pinForTest pin}; closing it gives its tree back to the probe. */
    public interface Pin extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * Test seam: {@code root} and every path under it read as {@code space} until the returned pin is
     * closed, however long that is (a probe planted by {@link #putProbeForTest} ages out in {@link
     * #PROBE_MILLIS}, less than a build takes). Scoped to a path, so tests that pin their own cache
     * directories cannot answer for each other; close it in a finally.
     */
    public static Pin pinForTest(Path root, JkCacheConfig.DiskSpace space) {
        Path key = root.toAbsolutePath().normalize();
        PROBES.keySet().removeIf(p -> p.toAbsolutePath().normalize().startsWith(key));
        Probe pin = new Probe(clock.getAsLong(), space, true);
        PROBES.put(key, pin);
        return () -> PROBES.remove(key, pin);
    }

    /** Test seam: what a probe of {@code path} answers until it ages out. */
    public static void putProbeForTest(Path path, JkCacheConfig.@Nullable DiskSpace space) {
        PROBES.put(path, new Probe(clock.getAsLong(), space));
    }
}
