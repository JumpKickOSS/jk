// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import cc.jumpkick.builds.HostStore;
import cc.jumpkick.host.Log;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;

/**
 * Host-wide remote artifact fetch durations for ETA (CAS misses). Successful network fetches only
 * cancelled / failed downloads never train. Outliers are dropped by trimming the top and bottom 10%
 * of samples before averaging.
 *
 * <p>Stored in the host's {@link HostStore} ({@link HostStore#FETCH}) so it survives {@code jk clean}.
 */
public final class FetchTimings {

    /** Cap retained samples so the file stays small. */
    static final int MAX_SAMPLES = 200;

    private static final ReentrantLock LOCK = new ReentrantLock();
    private static volatile @Nullable List<Long> memo;

    private FetchTimings() {}

    /** The host's store. */
    public static Path defaultFile() {
        return HostStore.defaultFile();
    }

    /**
     * Record one successful remote fetch duration (ms). Non-positive samples are ignored. Best-effort
     * — never throws into the fetch path.
     *
     * <p>Reads through the in-process memo (one disk read per process, not one per artifact
     * ; the write is last-writer-wins across concurrent engines, which is acceptable for an
     * advisory prior (a lost sample only delays convergence of the trimmed mean).
     */
    public static void record(long durationMs) {
        if (durationMs <= 0) return;
        LOCK.lock();
        try {
            AtomicReference<@Nullable List<Long>> kept = new AtomicReference<>();
            HostStore.update(defaultFile(), HostStore.FETCH, lines -> {
                List<Long> samples = new ArrayList<>(parse(lines));
                samples.add(durationMs);
                while (samples.size() > MAX_SAMPLES) samples.remove(0);
                kept.set(List.copyOf(samples));
                return List.of(render(samples));
            });
            List<Long> now = kept.get();
            if (now != null) memo = now;
        } catch (RuntimeException e) {
            // advisory
            Log.debug("record: advisory", e);
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * Trimmed mean of successful fetch durations (ms): drop the lowest and highest 10% of samples
     * (at least one each side when n ≥ 10), then average the middle. Empty when nothing learned.
     */
    public static long trimmedMeanMs() {
        return trimmedMeanMs(load());
    }

    /** Pure trimmed mean over {@code samples} (package-visible for tests). */
    static long trimmedMeanMs(List<Long> samples) {
        if (samples == null || samples.isEmpty()) return 0;
        List<Long> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        int n = sorted.size();
        int trim = n >= 10 ? Math.max(1, n / 10) : 0;
        if (trim * 2 >= n) trim = 0;
        long sum = 0;
        int count = 0;
        for (int i = trim; i < n - trim; i++) {
            sum += sorted.get(i);
            count++;
        }
        return count == 0 ? 0 : sum / count;
    }

    /**
     * Progress-bar weight units for one CAS-miss fetch: {@code round(trimmedMean / msPerWeight)}
     * (min 1), or {@code fallback} when cold.
     */
    public static int weightUnits(int fallback, int msPerWeight) {
        long ms = trimmedMeanMs();
        if (ms <= 0 || msPerWeight <= 0) return fallback;
        return Math.max(1, (int) Math.round(ms / (double) msPerWeight));
    }

    private static List<Long> load() {
        List<Long> m = memo;
        if (m != null) return m;
        LOCK.lock();
        try {
            List<Long> loaded = memo;
            if (loaded != null) return loaded;
            loaded = List.copyOf(loadUnlocked());
            memo = loaded;
            return loaded;
        } finally {
            LOCK.unlock();
        }
    }

    private static List<Long> loadUnlocked() {
        return parse(HostStore.section(defaultFile(), HostStore.FETCH));
    }

    /** {@code samples ms ms ...}. */
    private static List<Long> parse(List<String> lines) {
        List<Long> out = new ArrayList<>();
        for (String line : lines) {
            String[] f = line.strip().split("\\s+");
            if (f.length == 0 || !f[0].equals("samples")) continue;
            for (int i = 1; i < f.length; i++) {
                try {
                    long v = Long.parseLong(f[i]);
                    if (v > 0) out.add(v);
                } catch (NumberFormatException ignored) {
                    // skip
                }
            }
        }
        return out;
    }

    private static String render(List<Long> samples) {
        StringBuilder sb = new StringBuilder("samples");
        for (long v : samples) sb.append(' ').append(v);
        return sb.toString();
    }
}
