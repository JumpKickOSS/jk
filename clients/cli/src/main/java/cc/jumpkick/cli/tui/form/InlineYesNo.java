// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui.form;

import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.RenderContext;
import cc.jumpkick.terminal.Ansi;
import cc.jumpkick.terminal.InputMode;
import cc.jumpkick.terminal.Key;
import cc.jumpkick.terminal.ModeGuard;
import cc.jumpkick.terminal.RegionPainter;
import cc.jumpkick.terminal.TerminalSession;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * A yes/no asked on one row after a lead the caller paints, such as a wedge and a question: {@code
 * <lead> [Yes] No}. Under ANSI the {@link YesNo} toggle is live on that row (← → move, y / n answer
 * at once, Enter takes the focused one) and settles to {@code <lead> ➜ Yes}. Keys typed before
 * the row appeared are discarded first, so a keystroke meant for the next command cannot answer
 * it. Under plain mode it is a cooked {@code [Y/n]} line. Empty when the person backs out (Esc,
 * Ctrl-C, end of input).
 */
public final class InlineYesNo {

    /** How long pending input is discarded before the row is painted. */
    static final Duration TYPEAHEAD = Duration.ofMillis(40);

    private static final Duration POLL = Duration.ofMillis(75);

    private InlineYesNo() {}

    public static Optional<Boolean> ask(
            TerminalSession tty,
            PrintStream out,
            BufferedReader in,
            RenderContext ctx,
            String lead,
            boolean defaultYes) {
        return ask(tty, out, in, ctx, lead, defaultYes, TYPEAHEAD);
    }

    /** As {@link #ask}, discarding pending input for {@code typeahead} first (none when zero). */
    static Optional<Boolean> ask(
            TerminalSession tty,
            PrintStream out,
            BufferedReader in,
            RenderContext ctx,
            String lead,
            boolean defaultYes,
            Duration typeahead) {
        YesNo question = new YesNo(lead, null, defaultYes);
        return ctx.ansi() ? raw(tty, out, ctx, lead, question, typeahead) : cooked(out, in, lead, question);
    }

    /** The live row: the lead, then the two segments with the focused one a pill. */
    static String row(RenderContext ctx, String lead, boolean focusYes) {
        return lead + " " + Rows.segment(ctx, "Yes", focusYes) + " " + Rows.segment(ctx, "No", !focusYes);
    }

    /** The settled row: the lead, a green arrow and the answer in italics. */
    static String settledRow(RenderContext ctx, String lead, boolean answer) {
        Theme t = ctx.theme();
        return lead + " " + Theme.paint("➜", t.brightGreen()) + " "
                + Theme.paint(answer ? "Yes" : "No", t.settled().italic());
    }

    private static Optional<Boolean> raw(
            TerminalSession tty, PrintStream out, RenderContext ctx, String lead, YesNo question, Duration typeahead) {
        if (!typeahead.isZero()) tty.drain(typeahead);
        var painter = new RegionPainter(RegionPainter.Park.IN_ROW);
        boolean focusYes = question.focusYes();
        try (ModeGuard ignored = tty.enter(InputMode.PROMPT)) {
            out.print("\r" + Ansi.HIDE_CURSOR);
            paint(painter, out, tty, row(ctx, lead, focusYes));
            while (true) {
                Optional<Key> key = tty.readKey(POLL);
                if (key.isEmpty()) {
                    if (tty.isLive() && !Thread.currentThread().isInterrupted()) continue;
                    return settle(painter, out, tty, lead, Optional.empty());
                }
                if (key.get() instanceof Key.CtrlC) return settle(painter, out, tty, lead, Optional.empty());
                switch (question.key(key.get())) {
                    case Question.Done<Boolean> d -> {
                        return settle(painter, out, tty, settledRow(ctx, lead, d.value()), Optional.of(d.value()));
                    }
                    case Question.Cancelled<Boolean> c -> {
                        return settle(painter, out, tty, lead, Optional.empty());
                    }
                    default -> paint(painter, out, tty, row(ctx, lead, question.focusYes()));
                }
            }
        } finally {
            out.print(Ansi.SHOW_CURSOR);
            out.flush();
        }
    }

    private static void paint(RegionPainter painter, PrintStream out, TerminalSession tty, String row) {
        StringBuilder frame = new StringBuilder();
        painter.paint(frame, List.of(row), tty.size().cols(), 0, 0);
        out.print(frame);
        out.flush();
    }

    private static Optional<Boolean> settle(
            RegionPainter painter, PrintStream out, TerminalSession tty, String row, Optional<Boolean> answer) {
        StringBuilder frame = new StringBuilder();
        painter.paint(frame, List.of(row), tty.size().cols(), 0, 0);
        out.print(frame.append("\r\n"));
        out.flush();
        return answer;
    }

    private static Optional<Boolean> cooked(PrintStream out, BufferedReader in, String lead, YesNo question) {
        while (true) {
            out.print(lead + " " + question.cue() + ": ");
            out.flush();
            String line;
            try {
                line = in.readLine();
            } catch (IOException e) {
                line = null;
            }
            if (line == null) {
                out.println();
                return Optional.empty();
            }
            switch (question.line(line.strip())) {
                case Question.Done<Boolean> d -> {
                    out.println(Asker.SETTLED_PREFIX + question.settled(d.value()));
                    return Optional.of(d.value());
                }
                case Question.Retry<Boolean> r -> r.lines().forEach(out::println);
                default -> {
                    return Optional.empty();
                }
            }
        }
    }
}
