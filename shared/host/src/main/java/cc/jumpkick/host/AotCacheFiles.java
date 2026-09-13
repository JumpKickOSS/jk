// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Everything a jk process must agree on about a JEP 514 AOT cache: what its sidecar marker is
 * called, when the cache counts as present, and what a JVM refusing it looks like in a log.
 *
 * <p>Five modules read these facts — the engine's worker trainer, the CLI's engine spawn, the
 * {@code --aot-cache} packager, the image plugin's container trainer and the {@code aot.toml}
 * index — and they share one directory, so the marker is always {@code <cache>.aot.noaot} and
 * the refusal test matches the JVM's padded error line ({@code [error  ][aot]}).
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
     */
    public static final String MARKER = ".noaot";

    /** Suffix of the JVM's AOT configuration sidecar, recorded beside the cache while training. */
    public static final String CONFIG = ".config";

    /** Suffix of the training claim — one trainer per cache at a time. */
    public static final String TRAINING = ".training";

    /** Infix of a trainer's private assembly target: {@code <cache>.tmp-<pid>}. */
    public static final String TMP_INFIX = ".tmp-";

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

    /** The configuration sidecar of {@code cache}: the same whole-name suffix rule as the marker. */
    public static Path configOf(Path cache) {
        return cache.resolveSibling(cache.getFileName() + CONFIG);
    }

    /** The training claim of {@code cache}. */
    public static Path trainingClaim(Path cache) {
        return cache.resolveSibling(cache.getFileName() + TRAINING);
    }

    /** The private assembly target a trainer with {@code pid} writes before the atomic move. */
    public static Path tmpFor(Path cache, long pid) {
        return cache.resolveSibling(cache.getFileName() + TMP_INFIX + pid);
    }

    /** True for a sidecar of any cache — config, training claim, refusal marker or a trainer's temp. */
    public static boolean isSidecar(String name) {
        return name.endsWith(CONFIG) || name.endsWith(TRAINING) || name.contains(TMP_INFIX) || isMarker(name);
    }

    /** The sticky refusal marker beside {@code cache}. */
    public static Path marker(Path cache) {
        return cache.resolveSibling(cache.getFileName() + MARKER);
    }

    /** True when {@code name} is a refusal marker rather than a cache or another sidecar. */
    public static boolean isMarker(@Nullable String name) {
        return name != null && name.endsWith(CACHE + MARKER);
    }

    /** The cache file name a marker belongs to, or {@code null} when {@code name} is not one. */
    public static @Nullable String cacheOf(String markerName) {
        return isMarker(markerName) ? markerName.substring(0, markerName.length() - MARKER.length()) : null;
    }

    /**
     * Does {@code cache}'s refusal marker still block training? The engine's spawn decision and
     * the worker trainer both decide here, so a refusal expires on one schedule
     * ({@link #MARKER_TTL_MILLIS}).
     *
     * <p>An expired marker is deleted here rather than merely ignored, so the answer and the disk
     * cannot drift apart, and a sweep that never runs cannot resurrect it. An unreadable marker
     * blocks: skipping one train is cheaper than failing a build over a sidecar.
     */
    public static boolean blocked(@Nullable Path cache) {
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
    public static boolean usable(@Nullable Path cache) {
        try {
            return cache != null && Files.isRegularFile(cache) && Files.size(cache) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    /** Drop a zero-byte leftover so its key can retrain. Best-effort; no-op on anything else. */
    public static void deleteIfEmpty(@Nullable Path cache) {
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
     *
     * <p>Two kinds of evidence, ranked. An error-level {@code [aot]} line, or the untagged
     * module-graph complaint, is a refusal whatever else the log says. Below error level the tag
     * narrates, and a line shaped like a disable verdict ({@link #softRefusal}) counts only when
     * the JVM never said it was using the cache: once {@link #mapped} has spoken, a warning about
     * one entry — an adapter blob whose saved name disagrees with the recomputed one, a code
     * cache dropped for a GC change — describes a cache that is in use, not one that was refused.
     * Among several proving lines the specific one wins over the JVM's generic preamble, so the
     * caller's message names the cause rather than "run with -Xlog:aot for details".
     */
    public static @Nullable String refusal(@Nullable String jvmLog) {
        if (jvmLog == null || jvmLog.isEmpty()) return null;
        String hard = null;
        String soft = null;
        boolean mapped = false;
        for (String raw : jvmLog.split("\n")) {
            String line = raw.trim();
            if (hardRefusal(line)) {
                if (hard == null || isPreamble(hard)) hard = line;
            } else if (soft == null && softRefusal(line)) {
                soft = line;
            } else if (mapped(line)) {
                mapped = true;
            }
        }
        if (hard != null) return hard;
        return mapped ? null : soft;
    }

    /**
     * The JVM's own word that the cache is in use: the line it prints once the archive is mapped
     * and validated, on every run that got that far. A refusal never reaches it.
     */
    static boolean mapped(String line) {
        return line.contains("[aot") && line.toLowerCase(Locale.ROOT).contains("using aot-linked classes:");
    }

    /**
     * A refusal no later line can soften. Untagged: the module-system complaints the JVM prints
     * when it silently drops the archived module graph carry no {@code [aot]} tag at all. Tagged:
     * at error level the tag speaks only about the cache, so any error is a refusal — the caller's
     * answer (drop it, retrain) does not depend on which one, and the shapes are not a closed set.
     */
    private static boolean hardRefusal(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.contains("mismatched values for property") || lower.contains("disabling optimized module handling")) {
            return true;
        }
        // The tag, not the whole decorator: [aot], [aot ] and [aot,heap] are all the AOT subsystem.
        return line.contains("[aot") && ERROR_LEVEL.matcher(line).find();
    }

    /**
     * Below error level the tag narrates ordinary progress, so only the JVM's disable verdicts
     * count: a header, JDK or classpath mismatch, a cache it cannot map or use, a subsystem it
     * disabled. Per-item noise — "failed to load class X", an adapter it could not link — appears
     * on runs that mapped fine and is not a shape.
     */
    private static boolean softRefusal(String line) {
        if (!line.contains("[aot")) return false;
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("mismatch")
                || lower.contains("different version")
                || lower.contains("not the one used while building")
                || lower.contains("unable to map")
                || lower.contains("unable to use")
                || lower.contains("cannot be used")
                || lower.contains("disabled");
    }

    /** The JVM's generic error banner, printed ahead of the line that names the cause. */
    private static boolean isPreamble(String line) {
        return line.contains("An error has occurred while processing the AOT cache");
    }
}
