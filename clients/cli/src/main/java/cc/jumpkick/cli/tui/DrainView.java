// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.Ansi;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.NerdFontCaps;
import cc.jumpkick.terminal.InputMode;
import cc.jumpkick.terminal.Key;
import cc.jumpkick.terminal.ModeGuard;
import cc.jumpkick.terminal.Style;
import cc.jumpkick.terminal.TerminalSession;
import cc.jumpkick.terminal.Terminals;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Live two-line TUI for {@code jk engine stop} drain (job count + elapsed; Ctrl-X forces kill via
 * {@link #forceRequested()}). {@link InputMode#PLAN_KEYS}; no-op when non-interactive.
 */
public final class DrainView implements LiveRegion, AutoCloseable {

    private final TerminalSession terminal; // null → inactive no-op
    private final ModeGuard mode;
    private final PrintWriter out;
    private final NerdFontCaps nerdFont;
    private final long startNanos;

    private final Object lock = new Object();
    private final AtomicBoolean settled = new AtomicBoolean();
    private volatile int jobs;
    private volatile boolean forceRequested;
    private volatile boolean closed;
    private int linesDrawn;
    private Thread animator;
    private Thread keys;
    private Thread restoreHook;

    private DrainView(TerminalSession terminal, ModeGuard mode, int jobs, NerdFontCaps nerdFont, long startNanos) {
        this.terminal = terminal;
        this.mode = mode;
        this.out = terminal == null ? null : terminal.ttyOut();
        this.jobs = jobs;
        this.nerdFont = nerdFont;
        this.startNanos = startNanos;
    }

    /** Start the live drain region, or a silent no-op instance when non-interactive / no-progress. */
    public static DrainView start(int initialJobs, NerdFontCaps nerdFont) {
        long now = System.nanoTime();
        if (!interactive()) return new DrainView(null, null, initialJobs, nerdFont, now);
        try {
            TerminalSession t = Terminals.controlling();
            if (!t.isLive()) {
                return new DrainView(null, null, initialJobs, nerdFont, now);
            }
            ModeGuard mode = t.enter(InputMode.PLAN_KEYS);
            t.drain(Duration.ofMillis(40));
            DrainView v = new DrainView(t, mode, initialJobs, nerdFont, now);
            // Leading blank before the live Engine drain wedge (same envelope as other chrome).
            CommandWedge.envelopeStart();
            v.out.print(Ansi.HIDE_CURSOR);
            v.out.flush();
            LiveRegion.setActive(v);
            v.restoreHook = new Thread(v::restoreTerminalQuietly, "jk-drain-restore");
            Runtime.getRuntime().addShutdownHook(v.restoreHook);
            v.animator = new Thread(v::animate, "jk-drain-anim");
            v.animator.setDaemon(true);
            v.animator.start();
            v.keys = new Thread(v::readKeys, "jk-drain-keys");
            v.keys.setDaemon(true);
            v.keys.start();
            return v;
        } catch (Exception e) {
            return new DrainView(null, null, initialJobs, nerdFont, now); // degrade to no-op
        }
    }

    public boolean active() {
        return terminal != null;
    }

    public void setJobs(int n) {
        this.jobs = n;
    }

    public boolean forceRequested() {
        return forceRequested;
    }

    // --- rendering -----------------------------------------------------------

    private void animate() {
        // The only moving part is the 1s-granularity elapsed counter — 2 Hz is plenty.
        while (!closed) {
            synchronized (lock) {
                if (closed) break;
                paint();
            }
            try {
                Thread.sleep(500L);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void paint() {
        StringBuilder sb = new StringBuilder();
        if (linesDrawn > 0) sb.append(Ansi.cursorUp(linesDrawn));
        for (String line : lines()) {
            sb.append('\r').append(line).append(Ansi.ERASE_LINE_TO_END).append('\n');
        }
        out.print(sb);
        out.flush();
        linesDrawn = 2;
    }

    private String[] lines() {
        int n = jobs;
        String elapsed = Theme.colorize(
                "+" + fmtElapsed((System.nanoTime() - startNanos) / 1_000_000),
                Theme.active().midGray());
        String l1 = JkWedge.chipLine(
                Spinner.PULSE_GLYPH,
                "Engine",
                nerdFont,
                "Draining " + n + " job" + (n == 1 ? "" : "s") + "… " + elapsed);
        String hint = Theme.colorize(
                        "Wait for jobs to finish, or press ", Theme.active().dim())
                + Theme.colorize("Ctrl-X", Style.EMPTY.bold())
                + Theme.colorize(" to kill the engine now", Theme.active().dim());
        return new String[] {l1, hint};
    }

    private void readKeys() {
        while (!closed && terminal != null) {
            var key = terminal.readKey(Duration.ofMillis(100));
            if (key.isEmpty()) {
                if (!terminal.isLive()) {
                    return;
                }
                continue;
            }
            if (key.get() instanceof Key.CtrlX) {
                forceRequested = true;
                return;
            }
        }
    }

    // --- settle / teardown ---------------------------------------------------

    /** Wipe the live region and print the settled stop wedge line. Idempotent. */
    public void settleStopped(String wedgeLine) {
        if (terminal == null) return;
        if (!settled.compareAndSet(false, true)) return;
        stopAnimator();
        synchronized (lock) {
            if (linesDrawn > 0) out.print(Ansi.cursorUp(linesDrawn));
            out.print('\r');
            out.print(Ansi.ERASE_DISPLAY_TO_END);
            out.print(wedgeLine);
            out.print(System.lineSeparator());
            out.flush();
            linesDrawn = 0;
        }
    }

    @Override
    public void close() {
        if (terminal == null) return;
        stopAnimator();
        if (keys != null) keys.interrupt();
        synchronized (lock) {
            if (!settled.get()) {
                // No settle line was printed — just wipe the live region.
                if (linesDrawn > 0) out.print(Ansi.cursorUp(linesDrawn));
                out.print('\r');
                out.print(Ansi.ERASE_DISPLAY_TO_END);
                out.flush();
                linesDrawn = 0;
            }
        }
        restoreTerminalQuietly();
        LiveRegion.clearActive(this);
        removeHook();
    }

    @Override
    public boolean renderCanceled() {
        // Ctrl-C during the wait: the engine keeps draining in the background (correct) — leave a
        // clean note. GlobalCancel halts right after, bypassing shutdown hooks, so restore here.
        if (terminal == null) return true;
        stopAnimator();
        synchronized (lock) {
            if (linesDrawn > 0) out.print(Ansi.cursorUp(linesDrawn));
            out.print('\r');
            out.print(Ansi.ERASE_DISPLAY_TO_END);
            out.print("jk engine: still draining in the background");
            out.print(System.lineSeparator());
            out.flush();
            linesDrawn = 0;
        }
        restoreTerminalQuietly();
        return true;
    }

    private void stopAnimator() {
        closed = true;
        if (animator != null) animator.interrupt();
        try {
            if (animator != null) animator.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private final AtomicBoolean restored = new AtomicBoolean();

    private void restoreTerminalQuietly() {
        if (terminal == null || !restored.compareAndSet(false, true)) return;
        try {
            out.print(Ansi.SHOW_CURSOR);
            out.print(Ansi.RESET);
            out.flush();
            if (mode != null) {
                mode.close();
            }
        } catch (Exception ignored) {
            // best-effort restore
        }
    }

    private void removeHook() {
        if (restoreHook == null) return;
        try {
            Runtime.getRuntime().removeShutdownHook(restoreHook);
        } catch (IllegalStateException ignored) {
            // already shutting down
        }
    }

    // --- helpers -------------------------------------------------------------

    private static boolean interactive() {
        // Output axis (live drain region) — stdout must be a tty; also honor --no-progress.
        return Interactivity.stdoutIsTty()
                && !cc.jumpkick.config.SessionContext.current().config().noProgressOr(false);
    }

    /** {@code 14s} / {@code 1m 02s} / {@code 1h 05m 09s}. */
    private static String fmtElapsed(long millis) {
        long s = millis / 1000;
        if (s < 60) return s + "s";
        long m = s / 60, sec = s % 60;
        if (m < 60) return m + "m " + String.format("%02d", sec) + "s";
        long h = m / 60;
        m %= 60;
        return h + "h " + String.format("%02d", m) + "m " + String.format("%02d", sec) + "s";
    }
}
