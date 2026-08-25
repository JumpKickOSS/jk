// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.Osc;
import cc.jumpkick.cli.theme.Gradient;
import cc.jumpkick.cli.theme.Rgb;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.NerdFontCaps;
import cc.jumpkick.terminal.Ansi;
import cc.jumpkick.terminal.Style;
import java.io.PrintStream;

/**
 * Single-line animated spinner for indeterminate CLI work. A solid circle glyph ({@value
 * #PULSE_GLYPH}) <em>pulses</em> by lerping its foreground between two colors and back — the same
 * breathing effect as the web dashboard's live indicators (no multi-glyph thrash).
 *
 * <p>Two pulse palettes / layouts:
 *
 * <ul>
 *   <li><b>Open</b> ({@link #show}) — brand blue ↔ almost-black blue on the terminal background:
 *       {@code ● message}.
 *   <li><b>Wedge / chip</b> ({@link #showWedge}) — white ↔ chip blue on the CommandWedge pill
 *       (same chrome as {@link JkManager}'s plan header): {@code ● Status  message}.
 * </ul>
 *
 * <p>Cursor hidden between {@link #show}/{@link #showWedge} and {@link #close()}. Thread-safe
 * {@link #update}/{@link #close}. {@link #close()} clears the line so the caller can print a
 * settled {@link CommandWedge} in the same place.
 */
public final class Spinner implements AutoCloseable {

    /** Solid circle used for the pulse animation (U+25CF) — CommandWedge / open pulse. */
    public static final String PULSE_GLYPH = "●";

    /**
     * Filling-circle phases for tree rows under the progress bar (not the CommandWedge). Cycle:
     * white circle → bullseye → fisheye → bullseye, each held for {@link #FILL_HOLD} animator frames,
     * constant blue. Distinct glyphs only — hold is applied in {@link #fillGlyph(int)}, not by
     * repeating entries (the plan painter also skips rewriting a tree line when its text is
     * unchanged, so held frames are free).
     *
     * <ul>
     *   <li>U+25CB ○ white circle
     *   <li>U+25CE ◎ bullseye
     *   <li>U+25C9 ◉ fisheye
     * </ul>
     */
    public static final String[] FILL_PHASES = {
        "\u25CB", // ○
        "\u25CE", // ◎
        "\u25C9", // ◉
        "\u25CE", // ◎
    };

    /** Animator frames to hold each {@link #FILL_PHASES} glyph before advancing. */
    public static final int FILL_HOLD = 4;

    /** Frames in one full fill cycle ({@code FILL_PHASES.length * FILL_HOLD}). */
    public static final int FILL_FRAMES = FILL_PHASES.length * FILL_HOLD;

    /** Frames in one full bright→dim→bright cycle (odd so the midpoint lands exactly on dim). */
    static final int PULSE_FRAMES = 25;

    /** Interval between pulse frames (2.0s per full breath at 25 frames). */
    static final long FRAME_MS = cc.jumpkick.runtime.WorkspaceProgressTracker.TTY_FRAME_MS;

    /**
     * Glyph for animator frame {@code i} (wraps). Same glyph for {@link #FILL_HOLD} consecutive
     * frames so the paint path can no-op on tree lines until the phase actually changes.
     */
    public static String fillGlyph(int i) {
        int phase = Math.floorMod(i, FILL_FRAMES) / FILL_HOLD;
        return FILL_PHASES[phase];
    }

    /**
     * Bright end of the open (no-background) pulse — brand run blue ({@code #3D9BFF}, web {@code
     * --run} / Jk Dark primary).
     */
    static final Rgb PULSE_OPEN_BRIGHT = Rgb.hex(0x3D9BFF);

    /**
     * Dim end of the open pulse — almost-black blue in the same family (~10% of primary so it
     * still reads blue, not pure black).
     */
    static final Rgb PULSE_OPEN_DIM = PULSE_OPEN_BRIGHT.scaled(0.10);

    /** White end of the chip pulse (glyph on a solid blue pill). */
    static final Rgb PULSE_CHIP_BRIGHT = Rgb.hex(0xFFFFFF);

    private static final String HIDE_CURSOR = Ansi.HIDE_CURSOR;
    private static final String SHOW_CURSOR = Ansi.SHOW_CURSOR;
    private static final String CLEAR_LINE = Ansi.CLEAR_LINE;

    // Prefer gated helpers so --no-osc suppresses taskbar OSC without muting the spinner glyphs.
    private static String oscIndeterminate() {
        return Osc.taskbarIndeterminate();
    }

    private static String oscClear() {
        return Osc.taskbarClear();
    }

    private final PrintStream out;
    private final Style[] frameColors;
    private final Object lock = new Object();
    private final boolean silent;
    /** Non-null when painting as a CommandWedge chip ({@link #showWedge}). */
    private final String wedgeCommand;

    private final NerdFontCaps nerdFont;

    /** Plain-mode still-working heartbeat interval. */
    public static final long PLAIN_HEARTBEAT_MS = 60_000L;

    private volatile String message;
    private int frame = 0;
    private String lastMessage = "";
    private volatile boolean closed = false;
    private Thread animator;
    private boolean plainStarted;
    private long plainLastBeatMs;
    private java.util.function.LongSupplier clock = System::currentTimeMillis;

    public static Spinner show(PrintStream out, String message) {
        CommandWedge.envelopeStart(out); // open spinner is often first chrome for the command
        Spinner s = new Spinner(out, message, null, false);
        s.start();
        return s;
    }

    /** Test seam: wall clock for plain heartbeat cadence. */
    void clockForTests(java.util.function.LongSupplier clock) {
        if (clock != null) this.clock = clock;
    }

    /**
     * Live CommandWedge: blue chip with a pulsing {@link #PULSE_GLYPH} icon and {@code message}
     * after the powerline cap. Clears on {@link #close()} so the caller can print the settled
     * wedge (e.g. {@code ≡ Status  …}) on the same line.
     */
    public static Spinner showWedge(PrintStream out, String command, String message) {
        // analyzing() also calls envelopeStart — idempotent if both run.
        CommandWedge.envelopeStart(out);
        Spinner s = new Spinner(out, message, command == null ? "" : command, true);
        s.start();
        return s;
    }

    /** Package-private: open mode without starting the animator (tests). */
    Spinner(PrintStream out, String message) {
        this(out, message, null, false);
    }

    /** Package-private: wedge mode without starting the animator (tests). */
    static Spinner wedge(PrintStream out, String command, String message) {
        return new Spinner(out, message, command, true);
    }

    private Spinner(PrintStream out, String message, String wedgeCommand, boolean wedge) {
        // PlainAscii.wrap is identity under ANSI; under --no-ansi rewrites …/•/● in messages.
        this.out = PlainAscii.wrap(out);
        this.message = message == null ? "" : message;
        this.wedgeCommand = wedge ? (wedgeCommand == null ? "" : wedgeCommand) : null;
        this.nerdFont = wedge ? cc.jumpkick.config.GlobalConfig.nerdFont() : NerdFontCaps.NONE;
        // Script mode is no-progress: cursor-control ANSI/OSC and heartbeat lines must never
        // enter a stream a program is parsing (JK-2330's rule, applied at the primitive so no
        // call site can route around it the way Spinner.show(CliOutput.stdout()) did).
        this.silent = cc.jumpkick.config.SessionContext.current().config().noProgressOr(false)
                || cc.jumpkick.cli.CliOutput.scriptMode();
        if (wedge) {
            // Glyph FG breathes white↔chip blue; BG applied per frame in step().
            this.frameColors = buildChipPulseStyles(PULSE_FRAMES, Theme.active().planBadgeColor());
        } else {
            // Standalone spinner sits on the terminal background — open blue↔dark-blue pulse.
            this.frameColors = buildOpenPulseStyles(PULSE_FRAMES);
        }
    }

    private void start() {
        if (silent) return;
        // Plain / --no-ansi: multi-line start + optional 60s heartbeats + done on close.
        if (!Theme.active().isAnsi()) {
            printPlainWorking(true);
            animator = new Thread(this::plainHeartbeatLoop, "jk-spinner-plain");
            animator.setDaemon(true);
            animator.start();
            return;
        }
        out.print(HIDE_CURSOR);
        out.print(oscIndeterminate());
        out.flush();
        animator = new Thread(this::loop, "jk-spinner");
        animator.setDaemon(true);
        animator.start();
    }

    private void loop() {
        while (!closed) {
            step();
            try {
                Thread.sleep(FRAME_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void plainHeartbeatLoop() {
        while (!closed) {
            try {
                Thread.sleep(Math.min(FRAME_MS * 10, 5_000L));
            } catch (InterruptedException e) {
                return;
            }
            if (closed) return;
            printPlainWorking(false);
        }
    }

    public void update(String message) {
        this.message = message == null ? "" : message;
    }

    void step() {
        synchronized (lock) {
            if (closed || silent) return;
            String currentMsg = message;
            if (!Theme.active().isAnsi()) {
                printPlainWorking(false);
                lastMessage = currentMsg;
                return;
            }
            out.print(oscIndeterminate());
            out.print("\r");
            if (wedgeCommand != null) {
                out.print(renderWedgeFrame(frame, wedgeCommand, currentMsg, nerdFont, frameColors));
                out.print(Ansi.ERASE_LINE_TO_END);
            } else {
                out.print(Theme.colorize(PULSE_GLYPH, frameColors[frame]));
                out.print(" ");
                out.print(currentMsg);
                int shrink = lastMessage.length() - currentMsg.length();
                if (shrink > 0) out.print(" ".repeat(shrink));
            }
            out.flush();
            lastMessage = currentMsg;
            frame = (frame + 1) % PULSE_FRAMES;
        }
    }

    /** Plain multi-line working frame (start or 60s heartbeat). */
    private void printPlainWorking(boolean force) {
        synchronized (lock) {
            if (closed || silent) return;
            long now = clock.getAsLong();
            if (!force && plainStarted && now - plainLastBeatMs < PLAIN_HEARTBEAT_MS) return;
            plainStarted = true;
            plainLastBeatMs = now;
            out.println(plainWorkingLine(wedgeCommand, message));
            out.flush();
        }
    }

    private void printPlainDone() {
        synchronized (lock) {
            if (silent) return;
            out.println(plainDoneLine(wedgeCommand, message));
            out.flush();
        }
    }

    /** {@code "jk: * Status > Message - working..."} (open spinner omits command when null). */
    static String plainWorkingLine(String command, String message) {
        return JkWedge.plainStatusLine(command, message, JkWedge.PlainTail.WORKING);
    }

    static String plainDoneLine(String command, String message) {
        return JkWedge.plainStatusLine(command, message, JkWedge.PlainTail.DONE);
    }

    /**
     * One frame of the live CommandWedge: pulse circle + command on the blue chip, powerline (or
     * plain) cap, then the message. Package-private for tests.
     */
    static String renderWedgeFrame(int frame, String command, String message, NerdFontCaps nerdFont, Style[] pulseFg) {
        RenderContext ctx = RenderContext.current().withCaps(nerdFont).withFrame(frame);
        return new JkWedge(
                        Icon.spinner(), command == null ? "" : command, RichText.ansi(message == null ? "" : message))
                .variant(JkWedge.Variant.WORK)
                .renderLine(ctx);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (animator != null) animator.interrupt();
        if (silent) return;
        synchronized (lock) {
            if (!Theme.active().isAnsi()) {
                printPlainDone();
                return;
            }
            out.print(CLEAR_LINE);
            out.print(oscClear());
            out.print(SHOW_CURSOR);
            out.flush();
        }
    }

    /**
     * Open-terminal pulse (no chip background): brand blue at the ends of the cycle, almost-black
     * blue at the midpoint.
     */
    static Style[] buildOpenPulseStyles(int n) {
        return buildPulseStyles(n, PULSE_OPEN_BRIGHT, PULSE_OPEN_DIM);
    }

    /**
     * Chip / wedge pulse (glyph painted on a solid colored pill): white at the ends, {@code dim}
     * (typically the chip blue) at the midpoint — same as historical behavior so the glyph stays
     * readable on the blue background.
     */
    static Style[] buildChipPulseStyles(int n, Rgb dim) {
        return buildPulseStyles(n, PULSE_CHIP_BRIGHT, dim);
    }

    private record PulseKey(int n, Rgb bright, Rgb dim, Theme theme) {}

    /** A handful of (frame-count, color-pair) combos exist; live renders ask every frame. */
    private static final java.util.concurrent.ConcurrentHashMap<PulseKey, Style[]> PULSE_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Pulse styles: {@code bright} at the ends of the cycle, {@code dim} at the midpoint. */
    static Style[] buildPulseStyles(int n, Rgb bright, Rgb dim) {
        return PULSE_CACHE.computeIfAbsent(new PulseKey(n, bright, dim, Theme.active()), k -> {
            Gradient gradient = new Gradient(k.bright(), k.dim());
            Style[] a = new Style[k.n()];
            for (int i = 0; i < k.n(); i++) {
                a[i] = k.theme().bright(gradient.at(pulseWave(i, k.n())));
            }
            return a;
        });
    }

    /** 0 at frame 0 and last, 1 at the midpoint — bright→dim→bright when used as gradient {@code t}. */
    static double pulseWave(int frame, int n) {
        if (n <= 1) return 0.0;
        double t = (double) frame / (n - 1); // 0..1
        return t <= 0.5 ? t * 2.0 : (1.0 - t) * 2.0;
    }
}
