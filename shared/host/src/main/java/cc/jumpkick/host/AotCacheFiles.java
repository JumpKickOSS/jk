// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Everything a jk process must agree on about a JEP 514 AOT cache: what its sidecar marker is
 * called, when the cache counts as present, and what a JVM refusing it looks like in a log.
 *
 * <p>Five modules read these facts — the engine's worker trainer, the CLI's engine spawn, the
 * {@code --aot-cache} packager, the image plugin's container trainer and the {@code aot.toml}
 * index — and they share one directory, so a second spelling is not a style question. When the
 * marker was {@code <cache>.noaot} on one side and {@code <cache>.aot.noaot} on the other, each
 * sweep was blind to the other's markers; when the refusal test was {@code [error][aot]} on one
 * side and {@code [aot} on the other, the engine missed the padded error line the JVM actually
 * emits.
 *
 * <p>Lives on the host leaf because the image plugin runs as a forked worker that links nothing
 * heavier, and because {@code :core}'s {@code AotManifest} is itself a caller.
 */
public final class AotCacheFiles {

    /** Suffix of a cache file: {@code <tool>-[<jk-version>-]<16hex>.aot}. */
    public static final String CACHE = ".aot";

    /**
     * Suffix of the sticky "training failed for this key" sidecar. Appended to the <em>whole</em>
     * cache file name, so the marker for {@code kotlinc-0123456789abcdef.aot} is
     * {@code kotlinc-0123456789abcdef.aot.noaot} — the same rule the {@code .config} and
     * {@code .tmp-<pid>} sidecars follow, and reversible by plain suffix strip.
     *
     * <p>The engine used to strip {@code .aot} first, so markers written by an older jk are
     * orphaned rather than migrated: a key that was once refused gets exactly one more training
     * attempt, and the stray file is reclaimed by the next key change or engine upgrade. That is
     * already the regime a marker lives under — the engine's own TTL expires them on purpose — and
     * it costs one background train rather than a reader for a spelling that no longer exists.
     */
    public static final String MARKER = ".noaot";

    /**
     * How long a refusal is believed, for every key — engine and worker alike. Past this age a
     * marker is expired at read time by {@link #blocked} and the key gets one fresh training
     * attempt.
     *
     * <p>A refusal is usually environmental: a loaded machine, a transient mapping failure, a
     * trainer that lost a race for memory. Without an expiry the first bad minute disables AOT for
     * that key until something else mints a new one. For a worker key "something else" is a JDK,
     * Kotlin or GC change; for the engine it is a new engine jar or JDK, which is every day for
     * someone building jk and possibly never for someone running a release. The key that outlives
     * the window is exactly the key whose owner is not going to change it, so a single policy is
     * the honest one, and it costs one training start a week on a process started rarely.
     *
     * <p>This is not the window a {@code pending} row in {@code aot.toml} lives under. That one
     * asks how long a claim that a train is <em>running</em> stays credible, and a train is bounded
     * by its own two-minute hard limit; this one asks how long a finished failure stays believed.
     * Two questions, two clocks — see {@code AotManifest.PENDING_TTL_MILLIS}.
     */
    public static final long MARKER_TTL_MILLIS = 7L * 24 * 60 * 60 * 1_000;

    /**
     * The level field of a {@code -Xlog} line is padded to the widest enabled level, so an error
     * arrives as {@code [error  ]} whenever a {@code warning} is also enabled — which is the
     * common case.
     */
    private static final Pattern ERROR_LEVEL = Pattern.compile("\\[error\\s*]");

    private AotCacheFiles() {}

    /** The sticky refusal marker beside {@code cache}. */
    public static Path marker(Path cache) {
        return cache.resolveSibling(cache.getFileName() + MARKER);
    }

    /** True when {@code name} is a refusal marker rather than a cache or another sidecar. */
    public static boolean isMarker(String name) {
        return name != null && name.endsWith(CACHE + MARKER);
    }

    /** The cache file name a marker belongs to, or {@code null} when {@code name} is not one. */
    public static String cacheOf(String markerName) {
        return isMarker(markerName) ? markerName.substring(0, markerName.length() - MARKER.length()) : null;
    }

    /**
     * Does {@code cache}'s refusal marker still block training? The engine's spawn decision and
     * the worker trainer both decide here, so a refusal expires on one schedule
     * ({@link #MARKER_TTL_MILLIS}) rather than on whichever file the read landed in.
     *
     * <p>An expired marker is deleted here rather than merely ignored, so the answer and the disk
     * cannot drift apart, and a sweep that never runs cannot resurrect it. An unreadable marker
     * blocks: skipping one train is cheaper than failing a build over a sidecar.
     */
    public static boolean blocked(Path cache) {
        if (cache == null) return false;
        Path marker = marker(cache);
        try {
            if (!Files.exists(marker)) return false;
            long age = System.currentTimeMillis()
                    - Files.getLastModifiedTime(marker).toMillis();
            if (age <= MARKER_TTL_MILLIS) return true;
            Files.deleteIfExists(marker);
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    /**
     * The one definition of "cache present": a non-empty regular file. Zero-byte leftovers
     * (disk-full truncation, an interrupted copy) count as missing everywhere, or the warmup gate
     * and the train paths disagree forever.
     */
    public static boolean usable(Path cache) {
        try {
            return cache != null && Files.isRegularFile(cache) && Files.size(cache) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    /** Drop a zero-byte leftover so its key can retrain. Best-effort; no-op on anything else. */
    public static void deleteIfEmpty(Path cache) {
        try {
            if (cache != null && Files.isRegularFile(cache) && Files.size(cache) == 0) {
                Files.deleteIfExists(cache);
            }
        } catch (IOException ignored) {
            // best-effort
        }
    }

    /** True when {@code jvmLog} proves the JVM would not use the cache. */
    public static boolean refused(String jvmLog) {
        return refusal(jvmLog) != null;
    }

    /**
     * The line proving the JVM refused the cache, or {@code null} when it mapped. Callers hand in
     * whatever the JVM wrote — a {@code -Xlog:aot=info} transcript from a verification run, or the
     * first few KiB of an engine start log, where the AOT subsystem speaks only when unhappy.
     */
    public static String refusal(String jvmLog) {
        if (jvmLog == null || jvmLog.isEmpty()) return null;
        for (String line : jvmLog.split("\n")) {
            if (refuses(line)) return line.trim();
        }
        return null;
    }

    private static boolean refuses(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        // Untagged. The module-system complaints the JVM prints when it silently drops the cache
        // carry no [aot] tag at all, so a tag-first test never reaches them.
        if (lower.contains("mismatched values for property") || lower.contains("disabling optimized module handling")) {
            return true;
        }
        // The tag, not the whole decorator: [aot], [aot ] and [aot,heap] are all the AOT subsystem.
        if (!line.contains("[aot")) return false;
        // At error level the tag speaks only about the cache, so any error is a refusal — the
        // caller's answer (drop it, retrain) does not depend on which one, and the shapes below
        // are not a closed set.
        if (ERROR_LEVEL.matcher(line).find()) return true;
        // Below error the tag narrates ordinary progress, so match the refusal shapes only — not
        // per-item noise like "failed to load class X", which appears on runs that mapped fine.
        return lower.contains("mismatch")
                || lower.contains("different version")
                || lower.contains("unable to map")
                || lower.contains("unable to use")
                || lower.contains("cannot be used")
                || lower.contains("disabled")
                || ((lower.contains("archive") || lower.contains("cache")) && lower.contains("failed"));
    }
}
