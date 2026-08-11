// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui.progress;

/**
 * How the plan header progress bar is painted.
 *
 * <ul>
 *   <li>{@link #CLOCK} — open-loop {@code elapsed / R0} (aligned with the countdown)
 *   <li>{@link #WEIGHTED} — Σ effort-weight slices from the engine
 *   <li>{@link #AUTO} — clock when R0 is seeded, else weighted (default)
 * </ul>
 *
 * Override with {@code JK_PROGRESS_MODE=clock|weighted} (default {@code auto} / unset).
 */
public enum ProgressBarMode {
    /** Open-loop wall fill from seed R0. */
    CLOCK,
    /** Engine effort-weight numerator/denominator. */
    WEIGHTED,
    /** Prefer clock when R0 &gt; 0; otherwise weighted. */
    AUTO;

    public static final String ENV = "JK_PROGRESS_MODE";

    /**
     * Resolve from {@code JK_PROGRESS_MODE}: {@code clock}, {@code weighted}, or {@code auto}
     * (default when unset/blank/unknown).
     */
    public static ProgressBarMode fromEnvironment() {
        return parse(System.getenv(ENV));
    }

    static ProgressBarMode parse(String raw) {
        if (raw == null || raw.isBlank()) return AUTO;
        String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (v) {
            case "clock", "open-loop", "openloop", "r0", "eta" -> CLOCK;
            case "weighted", "weight", "work", "weights" -> WEIGHTED;
            case "auto", "default" -> AUTO;
            default -> AUTO;
        };
    }

    /**
     * Pick the concrete strategy for this snapshot. Forced modes always win; {@link #AUTO} uses
     * clock when {@code r0Ms > 0}.
     */
    public HeaderProgressStrategy select(HeaderProgressStrategy clock, HeaderProgressStrategy weighted, long r0Ms) {
        return switch (this) {
            case CLOCK -> clock;
            case WEIGHTED -> weighted;
            case AUTO -> r0Ms > 0 ? clock : weighted;
        };
    }
}
