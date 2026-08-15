// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.progress;

import java.util.Locale;

/**
 * How aggregate progress percent is painted (CLI header, engine workspace-progress, web).
 *
 * <ul>
 *   <li>{@link #CLOCK} — open-loop {@code elapsed / R0}
 *   <li>{@link #WEIGHTED} — Σ effort-weight slices
 *   <li>{@link #AUTO} — clock when R0 &gt; 0, else weighted (default)
 * </ul>
 *
 * Override with {@code JK_PROGRESS_MODE=clock|weighted} (default {@code auto}).
 */
public enum ProgressBarMode {
    CLOCK,
    WEIGHTED,
    AUTO;

    public static final String ENV = "JK_PROGRESS_MODE";

    public static ProgressBarMode fromEnvironment() {
        return parse(System.getenv(ENV));
    }

    public static ProgressBarMode parse(String raw) {
        if (raw == null || raw.isBlank()) return AUTO;
        String v = raw.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "clock", "open-loop", "openloop", "r0", "eta" -> CLOCK;
            case "weighted", "weight", "work", "weights" -> WEIGHTED;
            case "auto", "default" -> AUTO;
            default -> AUTO;
        };
    }

    /** Wire name for the per-request {@code progressMode} field. */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public HeaderProgressStrategy select(HeaderProgressStrategy clock, HeaderProgressStrategy weighted, long r0Ms) {
        return select(clock, weighted, r0Ms, -1);
    }

    /**
     * Pick the painting strategy. Forced CLOCK falls back to weighted when the clock has nothing
     * to paint from (no R0 seed and no residual) — the alternative was a visible bar frozen at 0%
     * on first-ever builds, and the web already fell back, so the three front-ends disagreed.
     */
    public HeaderProgressStrategy select(
            HeaderProgressStrategy clock, HeaderProgressStrategy weighted, long r0Ms, long residualRemainingMs) {
        return switch (this) {
            case CLOCK -> r0Ms > 0 || residualRemainingMs >= 0 ? clock : weighted;
            case WEIGHTED -> weighted;
            case AUTO -> r0Ms > 0 ? clock : weighted;
        };
    }
}
