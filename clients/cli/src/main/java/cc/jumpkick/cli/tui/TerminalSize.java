// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import java.util.function.Supplier;

/**
 * Process-wide terminal-size cache. Probing costs a subprocess fork — {@code stty size} against
 * {@code /dev/tty}. We deliberately do NOT build a JLine terminal: JLine probes the terminal with
 * capability queries (DA1 {@code \e[c}, mode reports like {@code \e[?2027$p}), and a transient
 * build-then-close races the async replies — they arrive after we exit and the shell echoes them
 * as garbage. Instead ask the tty directly via {@code stty size} (an ioctl, no escape sequences),
 * then the {@code $LINES}/{@code $COLUMNS} env, then conservative defaults.
 *
 * <p>The probe runs once and the result is reused; {@link #refresh()} re-probes at natural
 * boundaries (the start of a live plan), which also picks up a resize between builds. Render
 * paths run every animation frame and must never probe.
 */
public final class TerminalSize {

    static final int DEFAULT_WIDTH = 80;
    static final int DEFAULT_HEIGHT = 24;

    /** Injectable for tests; production probes the tty. */
    static Supplier<int[]> probe = TerminalSize::probeTty;

    private static volatile int[] cached;

    /**
     * SIGWINCH invalidation (JK-1966): with the cache probed only at plan start, everything
     * rendered after a mid-build resize — failure-snippet budgets, settle wedges — used the stale
     * width until the next plan. The handler only drops the cache (never probes); the next
     * consumer pays one {@code stty} fork per physical resize, not per frame. Installed lazily at
     * the first runtime probe so native-image build-time class init never registers a handler.
     * Best-effort: platforms without {@code sun.misc.Signal}/WINCH (Windows, exotic runtimes)
     * keep the plan-start-only behavior.
     */
    private static volatile boolean winchAttempted;

    private static void ensureWinchHandler() {
        if (winchAttempted) return;
        winchAttempted = true;
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return;
        }
        try {
            sun.misc.Signal.handle(new sun.misc.Signal("WINCH"), sig -> cached = null);
        } catch (Throwable t) {
            // unsupported runtime — the plan-start refresh still applies
        }
    }

    private TerminalSize() {}

    /** Cached {@code {rows, cols}}; probes on first use. */
    public static int[] size() {
        ensureWinchHandler();
        int[] s = cached;
        if (s == null) {
            s = probe.get();
            cached = s;
        }
        return s;
    }

    /** Terminal width in columns ({@code stty size} → {@code $COLUMNS} → {@value #DEFAULT_WIDTH}). */
    public static int columns() {
        return size()[1];
    }

    /** Re-probe and cache — call at plan start, never from a render path. */
    public static int[] refresh() {
        int[] s = probe.get();
        cached = s;
        return s;
    }

    /** Test hook: forget the cached size. */
    static void reset() {
        cached = null;
    }

    private static int[] probeTty() {
        try {
            Process p = new ProcessBuilder("stty", "size")
                    .redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/tty")))
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            String out =
                    new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII).trim();
            p.waitFor();
            String[] parts = out.split("\\s+"); // "<rows> <cols>"
            if (parts.length == 2) {
                int rows = Integer.parseInt(parts[0]);
                int cols = Integer.parseInt(parts[1]);
                if (rows > 0 && cols > 0) return new int[] {rows, cols};
            }
        } catch (Exception ignored) {
            // no /dev/tty, no stty (e.g. Windows), or unparsable — fall through
        }
        return new int[] {envInt("LINES", DEFAULT_HEIGHT), envInt("COLUMNS", DEFAULT_WIDTH)};
    }

    private static int envInt(String name, int fallback) {
        try {
            String v = System.getenv(name);
            if (v != null) {
                int n = Integer.parseInt(v.trim());
                if (n > 0) return n;
            }
        } catch (NumberFormatException ignored) {
            // unparsable env — fall through
        }
        return fallback;
    }
}
