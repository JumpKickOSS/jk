// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.theme.Gradient;
import cc.jumpkick.cli.theme.Rgb;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.config.NerdFontCaps;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.terminal.Style;
import cc.jumpkick.wire.runtime.WorkspaceProgressTracker;
import java.io.PrintStream;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

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
 * <p>Content only: the live region — animator, cursor, OSC taskbar, in-place repaint, Ctrl-C
 * settle — is {@link LiveLine}'s. What is the spinner's own is the frame for a tick, the message
 * {@link #update} may change under it, and the plain-mode cadence: a {@code working...} line when
 * it starts, one more per {@link #PLAIN_HEARTBEAT_MS} while it runs, {@code done.} on close.
 * Thread-safe {@link #update}/{@link #close}. {@link #close()} clears the row so the caller can
 * print a settled {@link CommandWedge} in the same place.
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
    static final long FRAME_MS = WorkspaceProgressTracker.TTY_FRAME_MS;

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

    private final PrintStream out;
    private final Style[] frameColors;
    private final Object lock = new Object();
    /** Non-null when painting as a CommandWedge chip ({@link #showWedge}). */
    private final @Nullable String wedgeCommand;

    private final NerdFontCaps nerdFont;
    private final LiveLine line;

    /** Plain-mode still-working heartbeat interval. */
    public static final long PLAIN_HEARTBEAT_MS = 60_000L;

    private volatile String message;
    private boolean plainStarted;
    private long plainLastBeatMs;
    private Clock clock = Clock.SYSTEM;

    public static Spinner show(PrintStream out, String message) {
        return new Spinner(out, message, null, false, true).plainStart();
    }

    /** Test seam: the clock the plain heartbeat cadence reads. */
    void clockForTests(Clock clock) {
        this.clock = clock;
    }

    /**
     * Live CommandWedge: blue chip with a pulsing {@link #PULSE_GLYPH} icon and {@code message}
     * after the powerline cap. Clears on {@link #close()} so the caller can print the settled
     * wedge (e.g. {@code ≡ Status  …}) on the same line.
     */
    public static Spinner showWedge(PrintStream out, String command, String message) {
        return new Spinner(out, message, command == null ? "" : command, true, true).plainStart();
    }

    /** Package-private: open mode, the region never opened on screen; tests drive {@link #step}. */
    Spinner(PrintStream out, String message) {
        this(out, message, null, false, false);
    }

    /** Package-private: wedge mode, the region never opened on screen; tests drive {@link #step}. */
    static Spinner wedge(PrintStream out, String command, String message) {
        return new Spinner(out, message, command, true, false);
    }

    private Spinner(PrintStream out, String message, @Nullable String wedgeCommand, boolean wedge, boolean open) {
        // PlainAscii.wrap is identity under ANSI; under --no-ansi rewrites …/•/● in messages.
        this.out = PlainAscii.wrapping(out);
        this.message = message == null ? "" : message;
        this.wedgeCommand = wedge ? (wedgeCommand == null ? "" : wedgeCommand) : null;
        this.nerdFont = wedge ? GlobalConfig.nerdFont() : NerdFontCaps.NONE;
        if (wedge) {
            // Glyph FG breathes white↔chip blue on the pill.
            this.frameColors = buildChipPulseStyles(PULSE_FRAMES, Theme.active().planBadgeColor());
        } else {
            // Standalone spinner sits on the terminal background — open blue↔dark-blue pulse.
            this.frameColors = buildOpenPulseStyles(PULSE_FRAMES);
        }
        LiveLine.Builder region = LiveLine.of(this.out, this::frame).heartbeat(() -> printPlainWorking(false));
        this.line = open ? region.open() : region.still();
    }

    /** The plain start: say the work has begun, so a long wait is not a silent one. */
    private Spinner plainStart() {
        if (line.plain()) printPlainWorking(true);
        return this;
    }

    public void update(String message) {
        this.message = message == null ? "" : message;
    }

    /** One beat of the region: a frame when animating, a heartbeat when plain (tests). */
    void step() {
        line.step();
    }

    /** The row for animator frame {@code tick}: the chip wedge, or the open pulse and message. */
    private String frame(int tick) {
        String currentMsg = message;
        if (wedgeCommand != null) return renderWedgeFrame(tick, wedgeCommand, currentMsg, nerdFont, frameColors);
        String row = Theme.colorize(PULSE_GLYPH, frameColors[tick]) + " " + currentMsg;
        return RenderContext.truncateVisible(
                row, RenderContext.rowColumnBudget(RenderContext.current().width()));
    }

    /** Plain working line: on start, and then no more often than {@link #PLAIN_HEARTBEAT_MS}. */
    private void printPlainWorking(boolean force) {
        synchronized (lock) {
            long now = clock.millis();
            if (!force && plainStarted && now - plainLastBeatMs < PLAIN_HEARTBEAT_MS) return;
            plainStarted = true;
            plainLastBeatMs = now;
            out.println(plainWorkingLine(wedgeCommand, message));
            out.flush();
        }
    }

    private void printPlainDone() {
        synchronized (lock) {
            out.println(plainDoneLine(wedgeCommand, message));
            out.flush();
        }
    }

    /** {@code "jk: * Status > Message - working..."} (open spinner omits command when null). */
    static String plainWorkingLine(@Nullable String command, String message) {
        return JkWedge.plainStatusLine(command, message, JkWedge.PlainTail.WORKING);
    }

    static String plainDoneLine(@Nullable String command, String message) {
        return JkWedge.plainStatusLine(command, message, JkWedge.PlainTail.DONE);
    }

    /**
     * One frame of the live CommandWedge: pulse circle + command on the blue chip, powerline (or
     * plain) cap, then the message. Package-private for tests.
     */
    static String renderWedgeFrame(int frame, String command, String message, NerdFontCaps nerdFont, Style[] pulseFg) {
        RenderContext ctx = RenderContext.current().withCaps(nerdFont).withFrame(frame);
        // renderLiveLine, not renderLine: the frame is repainted with \r, which rewinds one physical
        // row, so a message long enough to wrap would leave a new line behind on every frame.
        return new JkWedge(
                        Icon.spinner(), command == null ? "" : command, RichText.ansi(message == null ? "" : message))
                .variant(JkWedge.Variant.WORK)
                .renderLiveLine(ctx);
    }

    /** Release the row: wiped and the cursor back when animating; a {@code done.} line when plain. */
    @Override
    public void close() {
        if (!line.finish()) return;
        if (line.plain()) printPlainDone();
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

    /**
     * {@code color} is part of the key because {@link Theme#bright(Rgb)} bakes the colour decision
     * into every {@link Style} it returns — a colourless {@code Style} has an empty SGR body. The
     * {@code theme} field does not capture that: {@link Theme#active()} is one instance in both
     * modes and re-derives the decision on each call, so without {@code color} the first caller's
     * mode is served to every later one, and a plain-mode render silently strips a later ANSI one.
     */
    private record PulseKey(int n, Rgb bright, Rgb dim, Theme theme, boolean color) {}

    /** A handful of (frame-count, color-pair) combos exist; live renders ask every frame. */
    private static final ConcurrentHashMap<PulseKey, Style[]> PULSE_CACHE = new ConcurrentHashMap<>();

    /** Pulse styles: {@code bright} at the ends of the cycle, {@code dim} at the midpoint. */
    static Style[] buildPulseStyles(int n, Rgb bright, Rgb dim) {
        PulseKey key = new PulseKey(n, bright, dim, Theme.active(), GlobalConfig.colorEnabled());
        return PULSE_CACHE.computeIfAbsent(key, k -> {
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
