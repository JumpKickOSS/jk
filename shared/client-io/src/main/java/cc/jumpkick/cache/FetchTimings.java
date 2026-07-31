// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Host-wide remote artifact fetch durations for ETA (CAS misses). Successful network fetches only —
 * cancelled / failed downloads never train. Outliers are dropped by trimming the top and bottom 10%
 * of samples before averaging.
 *
 * <p>Stored at {@code ~/.jk/state/builds/fetch-timings.toml} so it survives {@code jk clean}.
 */
public final class FetchTimings {

    /** Cap retained samples so the file stays small. */
    static final int MAX_SAMPLES = 200;

    private static final ReentrantLock LOCK = new ReentrantLock();
    private static volatile List<Long> memo;

    private FetchTimings() {}

    /** Default store path (state dir, next to metrics/calibration). */
    public static Path defaultFile() {
        return JkDirs.builds().resolve("fetch-timings.toml");
    }

    /**
     * Record one successful remote fetch duration (ms). Non-positive samples are ignored. Best-effort
     * — never throws into the fetch path.
     *
     * <p>Reads through the in-process memo (one disk read per process, not one per artifact —
     * JK-1300); the write is last-writer-wins across concurrent engines, which is acceptable for an
     * advisory prior (a lost sample only delays convergence of the trimmed mean).
     */
    public static void record(long durationMs) {
        if (durationMs <= 0) return;
        LOCK.lock();
        try {
            List<Long> current = memo;
            if (current == null) current = loadUnlocked();
            List<Long> samples = new ArrayList<>(current);
            samples.add(durationMs);
            while (samples.size() > MAX_SAMPLES) samples.remove(0);
            writeUnlocked(samples);
            memo = List.copyOf(samples);
        } catch (IOException | RuntimeException ignored) {
            // advisory
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
            if (memo != null) return memo;
            memo = List.copyOf(loadUnlocked());
            return memo;
        } finally {
            LOCK.unlock();
        }
    }

    private static List<Long> loadUnlocked() {
        Path file = defaultFile();
        if (!Files.isRegularFile(file)) return List.of();
        try {
            List<Long> out = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String s = line.strip();
                if (s.isEmpty() || s.startsWith("#") || s.startsWith("[")) continue;
                // samples = [12, 34, 56]
                if (s.startsWith("samples")) {
                    int lb = s.indexOf('[');
                    int rb = s.lastIndexOf(']');
                    if (lb < 0 || rb <= lb) continue;
                    for (String part : s.substring(lb + 1, rb).split(",")) {
                        String t = part.strip();
                        if (t.isEmpty()) continue;
                        try {
                            long v = Long.parseLong(t);
                            if (v > 0) out.add(v);
                        } catch (NumberFormatException ignored) {
                            // skip
                        }
                    }
                }
            }
            return out;
        } catch (IOException e) {
            return List.of();
        }
    }

    private static void writeUnlocked(List<Long> samples) throws IOException {
        Path file = defaultFile();
        Files.createDirectories(file.getParent());
        StringBuilder sb = new StringBuilder();
        sb.append("# jk remote artifact fetch durations (ms) — successful fetches only\n");
        sb.append("samples = [");
        for (int i = 0; i < samples.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(samples.get(i));
        }
        sb.append("]\n");
        AtomicWrites.replace(file, sb.toString());
    }
}
