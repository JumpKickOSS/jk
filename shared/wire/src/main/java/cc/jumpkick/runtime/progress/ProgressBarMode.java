// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.progress;

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
        String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (v) {
            case "clock", "open-loop", "openloop", "r0", "eta" -> CLOCK;
            case "weighted", "weight", "work", "weights" -> WEIGHTED;
            case "auto", "default" -> AUTO;
            default -> AUTO;
        };
    }

    public HeaderProgressStrategy select(HeaderProgressStrategy clock, HeaderProgressStrategy weighted, long r0Ms) {
        return switch (this) {
            case CLOCK -> clock;
            case WEIGHTED -> weighted;
            case AUTO -> r0Ms > 0 ? clock : weighted;
        };
    }
}
