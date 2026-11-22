// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.theme.StreamCaps;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.terminal.Ansi;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

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
 * <p>{@code --no-progress}, {@code --no-osc}, and JSONL output also suppress notifications. A
 * notification is written only to a terminal: to the build's own stream when that is one, else to
 * stderr when stdout is redirected and stderr is still the terminal, else nowhere, so a redirected
 * log never collects one.
 *
 * <p>A run that quarantines a flaky test class sends one notification for each class, whatever the
 * run took: every policy but {@code never} sends it, and the same suppressors hold.
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
    public static boolean shouldNotify(@Nullable GlobalOptions global, long estimateMs, long elapsedMs) {
        if (global == null || suppressed(global)) return false;
        return switch (policy(global)) {
            case NEVER -> false;
            case ALWAYS -> true;
            case AUTO -> Math.max(0, estimateMs) >= THRESHOLD_MS || Math.max(0, elapsedMs) >= THRESHOLD_MS;
        };
    }

    /** Whether a quarantine notification may go: not suppressed, and the policy is not {@code never}. */
    static boolean shouldNotifyQuarantine(@Nullable GlobalOptions global) {
        return global != null && !suppressed(global) && policy(global) != JkConfig.NotifyChoice.NEVER;
    }

    /**
     * No notification at all: progress chrome off, OSC off, or JSONL on stdout, which an OSC
     * sequence would corrupt.
     */
    private static boolean suppressed(GlobalOptions global) {
        if (global.noProgress || global.noOsc || global.outputIsJson()) return true;
        return SessionContext.current().config().noOscOr(false);
    }

    private static JkConfig.NotifyChoice policy(GlobalOptions global) {
        return global.notify == null ? JkConfig.NotifyChoice.AUTO : global.notify;
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
        ArrayList<String> parts = new ArrayList<>(4);
        if (days > 0) parts.add(days + "d");
        if (hours > 0) parts.add(hours + "h");
        if (mins > 0) parts.add(mins + "m");
        if (secs > 0 || parts.isEmpty()) parts.add(secs + "s");
        return String.join(" ", parts);
    }

    /**
     * Emit OSC&nbsp;99 when {@link #shouldNotify} is true, on the terminal hosting the build ({@link
     * #terminalFor}). No-op when suppressed or when no stream is a terminal.
     */
    public static void maybeNotify(
            PrintStream out,
            @Nullable GlobalOptions global,
            Outcome outcome,
            String groupArtifact,
            long estimateMs,
            long elapsedMs) {
        if (out == null || !shouldNotify(global, estimateMs, elapsedMs)) return;
        PrintStream terminal = terminalFor(out);
        if (terminal == null) return;
        // The terminal check already asked whether that stream may carry ANSI.
        terminal.print(Ansi.desktopNotify(TITLE, message(outcome, groupArtifact, elapsedMs)));
        terminal.flush();
    }

    /** The warning a run says for each test class it has just put in quarantine. */
    public static final String QUARANTINED = "test-quarantined";

    /** Title for the notification of a test class put in quarantine. */
    public static final String QUARANTINE_TITLE = "JumpKick quarantined a flaky test";

    /**
     * Body for a class the module {@code coord} put in quarantine: {@code g:n: quarantined
     * a.FlakyTest: passed on a re-run 2 of its last 20 runs}, the module and the run's own warning.
     */
    public static String quarantineMessage(String coord, String warning) {
        return coord == null || coord.isBlank() ? warning : coord + ": " + warning;
    }

    /**
     * Emit one OSC&nbsp;99 for each of {@code bodies} ({@link #quarantineMessage}) when {@link
     * #shouldNotifyQuarantine} is true, on the terminal hosting the run.
     */
    public static void maybeNotifyQuarantined(PrintStream out, @Nullable GlobalOptions global, List<String> bodies) {
        if (out == null || bodies.isEmpty() || !shouldNotifyQuarantine(global)) return;
        PrintStream terminal = terminalFor(out);
        if (terminal == null) return;
        for (String body : bodies) terminal.print(Ansi.desktopNotify(QUARANTINE_TITLE, body));
        terminal.flush();
    }

    /**
     * The stream a notification for a build drawn on {@code out} goes to: {@code out} when it may
     * carry terminal OSC ({@link StreamCaps#live}), else stderr when that may, else null. A stream
     * {@link CliOutput} does not know (a test's capture) is taken as the caller meant it.
     */
    static @Nullable PrintStream terminalFor(PrintStream out) {
        var side = CliOutput.sideOf(out);
        if (side.isEmpty() || StreamCaps.live(side.get())) return out;
        return side.get() == StreamCaps.Side.OUT && StreamCaps.live(StreamCaps.Side.ERR) ? System.err : null;
    }
}
