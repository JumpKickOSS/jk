// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.theme;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.EnvValues;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.terminal.Terminals;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * What each of jk's two output streams may carry: the one owner of the plain-or-terminal decision.
 * Every renderer, live region, hyperlink and notification asks here.
 *
 * <p>A stream that is not a terminal is plain: no colour, no cursor or OSC sequences, no live
 * region and no Unicode chrome beyond what {@code --no-ansi} allows, whether it is a file or a pipe.
 * The rules, in order:
 *
 * <ol>
 *   <li>{@code --no-ansi} (and agent mode, which implies it) turns everything off.
 *   <li>A force switch ({@code --color always}, {@code JK_FORCE_ANSI} / {@code force-ansi}) keeps
 *       colour, chrome and hyperlinks on a non-terminal, for {@code less -R}. It never forces a live
 *       region or a terminal-only OSC (progress, title, notification) into a file.
 *   <li>{@code TERM=dumb} and {@code CI} turn ANSI off.
 *   <li>Otherwise the stream carries ANSI exactly when it is a terminal.
 * </ol>
 *
 * <p>Each stream is judged on its own device: {@code jk build 2> err.log} keeps a coloured stdout
 * and a plain error log, and {@code jk build > out.log} keeps the terminal's coloured failure and
 * warning lines on stderr. Chrome is rendered for the stream it goes to ({@link #renderSide}, set by
 * the stderr print helpers through {@link #renderingFor}); {@link CliOutput} strips any stream that
 * may not carry ANSI of what slipped through. A live region draws only on a terminal stdout, the
 * one place live output goes: with stdout redirected the run settles headless, its failure and
 * warning lines on stderr, as under {@code -O json}.
 */
public final class StreamCaps {

    /** One of the process's two output streams. */
    public enum Side {
        OUT,
        ERR
    }

    private StreamCaps() {}

    /** The device answer for each side, read once per process: fd 1 and fd 2 do not change. */
    private static volatile @Nullable Boolean outTty;

    private static volatile @Nullable Boolean errTty;

    /**
     * A test class's standing force switch, as {@code JK_FORCE_ANSI} is for a shell: for tests
     * whose assertions are about what a terminal shows, on every thread, through {@code Jk.execute}.
     */
    private static volatile boolean forcedForTests;

    /** The stream the calling thread is rendering for; stdout when nothing is bound. */
    private static final ScopedValue<Side> RENDERING = ScopedValue.newInstance();

    /** A test's stand-in for the device, bound to the calling thread for one body. */
    private static final ScopedValue<boolean[]> PINNED = ScopedValue.newInstance();

    /** True when {@code side} is a terminal. */
    public static boolean terminal(Side side) {
        if (PINNED.isBound()) return PINNED.get()[side.ordinal()];
        return side == Side.OUT ? outTty() : errTty();
    }

    /**
     * The stream chrome rendered on this thread is meant for: stdout unless the caller is inside
     * {@link #renderingFor}. The theme decides colour and glyphs for this side.
     */
    public static Side renderSide() {
        return RENDERING.isBound() ? RENDERING.get() : Side.OUT;
    }

    /**
     * {@code render}'s result, rendered for {@code side}: the theme asks that stream's device, so a
     * failure line bound for a terminal stderr keeps its colour while stdout is redirected, and one
     * bound for a redirected stderr is plain beside a coloured stdout.
     */
    public static <T> T renderingFor(Side side, Supplier<T> render) {
        return ScopedValue.where(RENDERING, side).call(render::get);
    }

    /** True when {@code side} may carry colour, attributes, glyph chrome and hyperlinks. */
    public static boolean ansi(Side side) {
        JkConfig config = SessionContext.current().config();
        if (config.noAnsiOr(false)) return false;
        if (forced(config)) return true;
        if (GlobalConfig.ansiSuppressed()) return false;
        return terminal(side);
    }

    /** True when {@code side} may carry foreground colour: {@link #ansi} and the {@code --color} choice. */
    public static boolean color(Side side) {
        if (!ansi(side)) return false;
        return switch (SessionContext.current().config().colorOr(JkConfig.ColorChoice.AUTO)) {
            case ALWAYS -> true;
            case NEVER -> false;
            case AUTO -> {
                String noColor = BuildEnv.get("NO_COLOR");
                yield noColor == null || noColor.isEmpty();
            }
        };
    }

    /**
     * True when {@code side} may carry a live region (cursor movement, repainted frames) or a
     * terminal-only OSC (taskbar progress, window title, desktop notification): a real terminal,
     * never a forced one, in a run that may be interactive.
     */
    public static boolean live(Side side) {
        return ansi(side) && terminal(side) && !forcedNonInteractive();
    }

    /** True when {@code side} may carry an OSC 8 hyperlink: {@link #ansi} and OSC not turned off. */
    public static boolean links(Side side) {
        return ansi(side) && !SessionContext.current().config().noOscOr(false);
    }

    /**
     * Run {@code body} as though stdout and stderr were (or were not) terminals, for this thread
     * only: the seam the output-mode tests drive a terminal run through.
     */
    public static <T> T asTerminal(boolean out, boolean err, Callable<T> body) throws Exception {
        return ScopedValue.where(PINNED, new boolean[] {out, err}).<T, Exception>call(body::call);
    }

    /** {@code CI} truthy, {@code JK_NONINTERACTIVE} set, or {@code TERM=dumb}: no live region anywhere. */
    public static boolean forcedNonInteractive() {
        if (EnvValues.isCi(System::getenv)) return true;
        String nonInteractive = System.getenv("JK_NONINTERACTIVE");
        if (nonInteractive != null && !nonInteractive.isBlank()) return true;
        return "dumb".equals(System.getenv("TERM"));
    }

    /** Turn the test force switch on or off (see {@link #forcedForTests}); tests only. */
    public static void forceAnsiForTests(boolean on) {
        forcedForTests = on;
    }

    private static boolean forced(JkConfig config) {
        return forcedForTests
                || config.forceAnsiOr(false)
                || config.colorOr(JkConfig.ColorChoice.AUTO) == JkConfig.ColorChoice.ALWAYS;
    }

    private static boolean outTty() {
        Boolean known = outTty;
        if (known == null) outTty = known = Terminals.stdoutIsTty();
        return known;
    }

    private static boolean errTty() {
        Boolean known = errTty;
        if (known == null) errTty = known = Terminals.stderrIsTty();
        return known;
    }
}
