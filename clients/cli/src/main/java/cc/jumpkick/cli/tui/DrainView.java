// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.run.DurationText;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.NerdFontCaps;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.terminal.Ansi;
import cc.jumpkick.terminal.InputMode;
import cc.jumpkick.terminal.Key;
import cc.jumpkick.terminal.ModeGuard;
import cc.jumpkick.terminal.RegionPainter;
import cc.jumpkick.terminal.Style;
import cc.jumpkick.terminal.TerminalSession;
import cc.jumpkick.terminal.Terminals;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;

/**
 * Live two-line TUI for {@code jk engine stop} drain (job count + elapsed; Ctrl-X forces kill via
 * {@link #forceRequested()}). {@link InputMode#PLAN_KEYS}; no-op when non-interactive.
 */
public final class DrainView implements LiveRegion, AutoCloseable {

    private final @Nullable TerminalSession terminal; // null → inactive no-op
    private final @Nullable ModeGuard mode;
    private final @Nullable PrintWriter out;
    private final NerdFontCaps nerdFont;
    private final long startNanos;

    private final Object lock = new Object();
    private final AtomicBoolean settled = new AtomicBoolean();
    private volatile int jobs;
    private volatile boolean forceRequested;
    private volatile boolean closed;
    private final RegionPainter painter = new RegionPainter(RegionPainter.Park.BELOW);
    private @Nullable FrameClock clock;
    private @Nullable Thread keys;
    private @Nullable Thread restoreHook;

    /** The tty writer; only the live view (a non-null terminal) paints, so callers are past the guard. */
    private PrintWriter out() {
        return Objects.requireNonNull(out, "inactive drain view has no tty");
    }

    private DrainView(
            @Nullable TerminalSession terminal,
            @Nullable ModeGuard mode,
            int jobs,
            NerdFontCaps nerdFont,
            long startNanos) {
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
            DrainView v = new DrainView(t, mode, initialJobs, nerdFont, now);
            // Leading blank before the live Engine drain wedge (same envelope as other chrome).
            CommandWedge.envelopeStart();
            v.out().print(Ansi.HIDE_CURSOR);
            v.out().flush();
            LiveRegion.setActive(v);
            v.restoreHook = new Thread(v::restoreTerminalQuietly, "jk-drain-restore");
            Runtime.getRuntime().addShutdownHook(v.restoreHook);
            v.clock = FrameClock.start("jk-drain-anim", FrameClock.FRAME_MS, v::beat, true);
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

    private void beat() {
        synchronized (lock) {
            if (closed) return;
            StringBuilder f = new StringBuilder();
            painter.paint(f, List.of(lines()), 0, 0, Integer.MAX_VALUE);
            if (f.isEmpty()) return;
            out().print(f);
            out().flush();
        }
    }

    /** Erase the live region; the cursor is left where it started. Must hold {@link #lock}. */
    private void wipe() {
        StringBuilder f = new StringBuilder();
        painter.lift(f, 0, Integer.MAX_VALUE);
        out().print(f);
    }

    private String[] lines() {
        int n = jobs;
        String elapsed = Theme.colorize(
                "+" + DurationText.clockMillis((System.nanoTime() - startNanos) / 1_000_000),
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
        if (terminal != null) {
            terminal.drain(Duration.ofMillis(40));
        }
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
            wipe();
            out().print(wedgeLine);
            out().print(System.lineSeparator());
            out().flush();
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
                wipe();
                out().flush();
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
            wipe();
            out().print("jk engine: still draining in the background");
            out().print(System.lineSeparator());
            out().flush();
        }
        restoreTerminalQuietly();
        return true;
    }

    private void stopAnimator() {
        closed = true;
        if (clock != null) clock.stop();
    }

    private final AtomicBoolean restored = new AtomicBoolean();

    private void restoreTerminalQuietly() {
        if (terminal == null || !restored.compareAndSet(false, true)) return;
        try {
            out().print(Ansi.SHOW_CURSOR);
            out().print(Ansi.RESET);
            out().flush();
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
        return Interactivity.stdoutIsTty() && !SessionContext.current().config().noProgressOr(false);
    }
}
