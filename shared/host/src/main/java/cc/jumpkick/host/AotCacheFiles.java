// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Reads a JVM's {@code -Xlog:aot} transcript for the user-facing trainers ({@code jk build
 * --aot-cache}, {@code [image] aot-cache}): a JEP 514 cache the JVM refuses is silent at default
 * log level, so the line that proves the refusal is the only evidence a build can act on.
 */
public final class AotCacheFiles {

    /**
     * The level field of a {@code -Xlog} line is padded to the widest enabled level, so an error
     * arrives as {@code [error  ]} whenever a {@code warning} is also enabled — which is the
     * common case.
     */
    private static final Pattern ERROR_LEVEL = Pattern.compile("\\[error\\s*]");

    private AotCacheFiles() {}

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
