// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.theme.Gradient;
import cc.jumpkick.cli.theme.Rgb;
import cc.jumpkick.config.EnvValues;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.terminal.Ansi;
import cc.jumpkick.terminal.Terminals;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Locale;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The line a slow start shows: once {@link #GATE_MS} pass without output, the phase jk is waiting
 * on, until the command's own first output replaces it. Phases are named at a few coarse
 * checkpoints with {@link #phase}; a phase is shown only once it has itself been current for
 * {@link #GATE_MS}, so a fast phase never flickers past.
 *
 * <p>ANSI: {@code " ● Initializing…"} with a breathing dot, erased on release. Plain: {@code
 * " * Initializing..."}, later slow phases appended to the same line, and {@code " Done."} on
 * release. Gated on argv and environment alone: the config files are the slow read this covers.
 */
public final class StartupPulse implements LiveRegion, AutoCloseable {

    /** Silence allowed before the first paint, and the age a later phase needs to be shown. */
    static final long GATE_MS = 250;

    /** Whole-run elapsed seconds follow the label from this point on (ANSI only). */
    static final long ELAPSED_AFTER_MS = 3_000;

    static final String INITIALIZING = "Initializing";

    private static volatile @Nullable StartupPulse armed;

    private final Object lock = new Object();
    private final PrintStream out;
    private final boolean ansi;
    private final boolean color;
    private final Clock clock;
    private final long startNanos;
    private final String[] frames;

    private String phase = INITIALIZING;
    private long phaseSinceNanos;
    private @Nullable String shown;
    private boolean released;
    private int frame;
    private @Nullable PrintStream wrappedOut;
    private @Nullable PrintStream wrappedErr;
    private @Nullable PrintStream realOut;
    private @Nullable PrintStream realErr;
    private @Nullable Thread thread;

    StartupPulse(PrintStream out, boolean ansi, boolean color, Clock clock) {
        this.out = out;
        this.ansi = ansi;
        this.color = color;
        this.clock = clock;
        this.startNanos = clock.nanos();
        this.phaseSinceNanos = startNanos;
        this.frames = glyphFrames(color);
    }

    /**
     * Arm the pulse for this process when both stdout and stderr are an interactive terminal and
     * nothing in {@code args} or the environment asks for quiet or machine output.
     */
    public static void arm(String[] args) {
        Gate gate = gate(args, System::getenv);
        if (gate == Gate.OFF || !Interactivity.stdoutIsTty() || !Terminals.stderrIsTty()) return;
        PrintStream realOut = System.out;
        StartupPulse pulse =
                new StartupPulse(realOut, gate == Gate.ANSI, colorWanted(args, System::getenv), Clock.SYSTEM);
        pulse.realOut = realOut;
        pulse.realErr = System.err;
        pulse.wrappedOut = releasing(realOut, pulse);
        pulse.wrappedErr = releasing(System.err, pulse);
        armed = pulse;
        System.setOut(pulse.wrappedOut);
        System.setErr(pulse.wrappedErr);
        Terminals.beforeForeground(StartupPulse::release);
        LiveRegion.setActive(pulse);
        Thread t = new Thread(pulse::run, "jk-startup-pulse");
        t.setDaemon(true);
        pulse.thread = t;
        t.start();
    }

    /** Name the phase jk is in now. Shown only if it lasts {@link #GATE_MS}. No-op when not armed. */
    public static void phase(String label) {
        StartupPulse pulse = armed;
        if (pulse != null) pulse.enter(label);
    }

    /** Take the pulse off screen: the command has output of its own. Idempotent; no-op when not armed. */
    public static void release() {
        StartupPulse pulse = armed;
        if (pulse != null) pulse.close();
    }

    /** Whether a pulse is armed in this process (test seam for dispatch wiring). */
    static boolean isArmed() {
        return armed != null;
    }

    enum Gate {
        OFF,
        PLAIN,
        ANSI
    }

    /** The pulse's mode from jk's own args (up to {@code --}) and the environment. */
    static Gate gate(String[] args, Function<String, @Nullable String> env) {
        if (EnvValues.isCi(env)) return Gate.OFF;
        if (EnvValues.string(env, "JK_NONINTERACTIVE").isPresent()) return Gate.OFF;
        if ("dumb".equals(env.apply("TERM"))) return Gate.OFF;
        if (EnvValues.bool(env, "JK_QUIET").orElse(false)) return Gate.OFF;
        if (EnvValues.bool(env, "JK_NO_PROGRESS").orElse(false)) return Gate.OFF;
        boolean ansi = !EnvValues.bool(env, "JK_NO_ANSI").orElse(false);
        boolean forceAnsi = EnvValues.bool(env, "JK_FORCE_ANSI").orElse(false);
        for (String a : args) {
            if ("--".equals(a)) break;
            switch (a) {
                case "-q", "--quiet", "--no-progress", "-O", "--output", "-h", "--help", "-V", "--version" -> {
                    return Gate.OFF;
                }
                case "--no-ansi" -> ansi = false;
                default -> {
                    if (a.startsWith("--output=")) return Gate.OFF;
                }
            }
        }
        return ansi || forceAnsi ? Gate.ANSI : Gate.PLAIN;
    }

    /** {@code --color never}, {@code JK_COLOR=never} and {@code NO_COLOR} take the dot's color away. */
    static boolean colorWanted(String[] args, Function<String, @Nullable String> env) {
        String choice = env.apply("JK_COLOR");
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--".equals(a)) break;
            if ("--color".equals(a) && i + 1 < args.length) choice = args[i + 1];
            else if (a.startsWith("--color=")) choice = a.substring("--color=".length());
        }
        if (choice != null) {
            String c = choice.trim().toLowerCase(Locale.ROOT);
            if (c.equals("never")) return false;
            if (c.equals("always")) return true;
        }
        String noColor = env.apply("NO_COLOR");
        return noColor == null || noColor.isEmpty();
    }

    void enter(String label) {
        synchronized (lock) {
            if (released || label.equals(phase)) return;
            phase = label;
            phaseSinceNanos = clock.nanos();
        }
    }

    private void run() {
        try {
            Thread.sleep(GATE_MS);
            while (tick()) Thread.sleep(FrameClock.FRAME_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** One beat: first paint, a slow phase swapped in, the dot breathing. False once released. */
    boolean tick() {
        synchronized (lock) {
            if (released) return false;
            long now = clock.nanos();
            if (shown == null) {
                shown = phase;
                out.print(System.lineSeparator());
                if (ansi) paintFrame(now);
                else out.print(" * " + phase + "...");
            } else {
                if (!phase.equals(shown) && now - phaseSinceNanos >= GATE_MS * 1_000_000L) {
                    shown = phase;
                    if (!ansi) out.print(" " + lowerFirst(phase) + "...");
                }
                if (ansi) paintFrame(now);
            }
            out.flush();
            return true;
        }
    }

    private void paintFrame(long now) {
        frame = (frame + 1) % frames.length;
        StringBuilder row = new StringBuilder(Ansi.CLEAR_LINE)
                .append(' ')
                .append(frames[frame])
                .append(' ')
                .append(shown)
                .append('…');
        long elapsedMs = (now - startNanos) / 1_000_000L;
        if (elapsedMs >= ELAPSED_AFTER_MS) {
            row.append(Ansi.CSI)
                    .append("2m ")
                    .append(elapsedMs / 1000)
                    .append('s')
                    .append(Ansi.RESET);
        }
        out.print(row);
    }

    /**
     * Off screen and out of the way. ANSI wipes the row and steps back onto the blank above it, so
     * the command's own leading blank lands where it would have without the pulse.
     */
    @Override
    public void close() {
        synchronized (lock) {
            if (released) return;
            released = true;
            if (shown != null) {
                if (ansi) out.print(Ansi.CLEAR_LINE + Ansi.CSI + "1A");
                else out.println(" Done.");
                out.flush();
            }
        }
        Thread t = thread;
        if (t != null && t != Thread.currentThread()) t.interrupt();
        if (wrappedOut != null && System.out == wrappedOut) System.setOut(realOut);
        if (wrappedErr != null && System.err == wrappedErr) System.setErr(realErr);
        LiveRegion.clearActive(this);
        if (armed == this) armed = null;
    }

    @Override
    public boolean renderCanceled() {
        close();
        return false;
    }

    /** What the row says right now; null before the first paint. Package-private for tests. */
    @Nullable
    String shown() {
        synchronized (lock) {
            return shown;
        }
    }

    private static String lowerFirst(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    /** The breathing dot: the open pulse's blue↔dark-blue cycle, or a still glyph without color. */
    private static String[] glyphFrames(boolean color) {
        int n = Spinner.PULSE_FRAMES;
        String[] a = new String[n];
        Gradient gradient = new Gradient(Spinner.PULSE_OPEN_BRIGHT, Spinner.PULSE_OPEN_DIM);
        for (int i = 0; i < n; i++) {
            if (!color) {
                a[i] = Spinner.PULSE_GLYPH;
                continue;
            }
            Rgb c = gradient.at(Spinner.pulseWave(i, n));
            a[i] = Ansi.CSI + Ansi.fgBody(c.r(), c.g(), c.b()) + "m" + Spinner.PULSE_GLYPH + Ansi.RESET;
        }
        return a;
    }

    /** {@code real} behind a stream whose first byte releases {@code pulse}, in {@code real}'s charset. */
    private static PrintStream releasing(PrintStream real, StartupPulse pulse) {
        OutputStream gate = new OutputStream() {
            @Override
            public void write(int b) {
                pulse.close();
                real.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) {
                pulse.close();
                real.write(b, off, len);
            }

            @Override
            public void flush() {
                real.flush();
            }

            @Override
            public void close() throws IOException {
                real.close();
            }
        };
        return new PrintStream(gate, true, real.charset());
    }
}
