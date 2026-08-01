// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.Ansi;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.config.JkConfig;
import java.io.PrintStream;

/**
 * Desktop notifications for long (or forced) builds via OSC&nbsp;99.
 *
 * <p>Policy ({@link JkConfig.NotifyChoice} / {@code config.notify} / {@code --notify}):
 *
 * <ul>
 *   <li>{@code auto} (default) — notify when ETA or elapsed ≥ 1 minute
 *   <li>{@code always} / {@code true} — always notify on finish
 *   <li>{@code never} / {@code false} — never notify
 * </ul>
 *
 * <p>{@code --no-progress}, {@code --no-osc}, and JSONL output also suppress notifications.
 */
public final class BuildNotify {

    /** One-minute threshold (estimate or elapsed) for automatic notifications. */
    public static final long THRESHOLD_MS = 60_000L;

    public enum Outcome {
        COMPLETE("complete"),
        FAILED("failed"),
        CANCELLED("cancelled");

        final String word;

        Outcome(String word) {
            this.word = word;
        }
    }

    private BuildNotify() {}

    /**
     * Whether a notification should fire for this finish. Hard suppressors ({@code no-progress},
     * {@code no-osc}, JSONL) win; else the resolved {@link GlobalOptions#notify} policy applies.
     */
    public static boolean shouldNotify(GlobalOptions global, long estimateMs, long elapsedMs) {
        if (global == null) return false;
        // Suppress when progress chrome is off, OSC is off, or stdout is JSONL (OSC would corrupt
        // the machine stream).
        if (global.noProgress || global.noOsc || global.outputIsJson()) return false;
        if (!Ansi.oscEnabled()) return false;
        JkConfig.NotifyChoice policy =
                global.notify == null ? JkConfig.NotifyChoice.AUTO : global.notify;
        return switch (policy) {
            case NEVER -> false;
            case ALWAYS -> true;
            case AUTO -> Math.max(0, estimateMs) >= THRESHOLD_MS || Math.max(0, elapsedMs) >= THRESHOLD_MS;
        };
    }

    /**
     * Title for build notifications (fixed product chrome).
     */
    public static final String TITLE = "JumpKick Build";

    /**
     * Body: {@code Build complete for g:n (took 1m 30s)} (and failed / cancelled variants).
     * Duration uses whole-second components (no {@code 18.0s} fractional form).
     */
    public static String message(Outcome outcome, String groupArtifact, long elapsedMs) {
        String ga = groupArtifact == null || groupArtifact.isBlank() ? "project" : groupArtifact;
        return "Build " + outcome.word + " for " + ga + " (took " + formatTook(elapsedMs) + ")";
    }

    /**
     * Human duration for notification bodies: {@code 18s}, {@code 1m 30s}, {@code 5m 18s}, {@code
     * 712ms} when under one second.
     */
    static String formatTook(long millis) {
        long ms = Math.max(0, millis);
        if (ms < 1000) return ms + "ms";
        long totalSec = ms / 1000;
        long days = totalSec / 86_400;
        totalSec %= 86_400;
        long hours = totalSec / 3_600;
        totalSec %= 3_600;
        long mins = totalSec / 60;
        long secs = totalSec % 60;
        java.util.ArrayList<String> parts = new java.util.ArrayList<>(4);
        if (days > 0) parts.add(days + "d");
        if (hours > 0) parts.add(hours + "h");
        if (mins > 0) parts.add(mins + "m");
        if (secs > 0 || parts.isEmpty()) parts.add(secs + "s");
        return String.join(" ", parts);
    }

    /**
     * Emit OSC&nbsp;99 when {@link #shouldNotify} is true. Writes to {@code out} (typically stdout so
     * the terminal hosting the build sees it). No-op when suppressed.
     */
    public static void maybeNotify(
            PrintStream out,
            GlobalOptions global,
            Outcome outcome,
            String groupArtifact,
            long estimateMs,
            long elapsedMs) {
        if (out == null || !shouldNotify(global, estimateMs, elapsedMs)) return;
        String seq = Ansi.desktopNotify(TITLE, message(outcome, groupArtifact, elapsedMs));
        if (seq.isEmpty()) return;
        out.print(seq);
        out.flush();
    }
}
