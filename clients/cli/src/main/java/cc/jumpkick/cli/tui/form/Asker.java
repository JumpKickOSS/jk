// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui.form;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.Interactivity;
import cc.jumpkick.cli.tui.RenderContext;
import cc.jumpkick.terminal.Ansi;
import cc.jumpkick.terminal.InputMode;
import cc.jumpkick.terminal.Key;
import cc.jumpkick.terminal.ModeGuard;
import cc.jumpkick.terminal.RegionPainter;
import cc.jumpkick.terminal.TerminalSession;
import cc.jumpkick.terminal.Terminals;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Asks one {@link Question} at a time. Under ANSI (nerd or not) the question is a live region on
 * {@code out}: {@link InputMode#PROMPT}, keys from the TTY, repainted cell by cell, replaced by its
 * settled row when answered. Under plain mode it is cooked lines: the label, any menu, a prompt, and
 * a whole line read from {@code in}; the answer re-prints as {@code -> answer}, and end of input
 * answers with the question's default. Either way an empty result means the user backed out, or,
 * once {@link #ended()}, that a question with no default met end of input.
 */
public final class Asker {

    /** Plain mode's settled-row prefix, the cooked twin of {@code ➜}. */
    static final String SETTLED_PREFIX = "-> ";

    /** Key poll slice, so a dead terminal is noticed. */
    private static final Duration POLL = Duration.ofMillis(75);

    private final TerminalSession tty;
    private final PrintStream out;
    private final BufferedReader in;
    private final RenderContext ctx;

    /** Set once a cooked read meets end of input; every question after it takes its default. */
    private boolean ended;

    public Asker(TerminalSession tty, PrintStream out, BufferedReader in, RenderContext ctx) {
        this.tty = tty;
        this.out = out;
        this.in = in;
        this.ctx = ctx;
    }

    /**
     * The asker for this process's controlling terminal, painting on stderr; {@code null} when no
     * human can answer ({@link Interactivity#canAsk()}: no TTY, stdin redirected, {@code CI}, {@code
     * JK_NONINTERACTIVE}).
     */
    public static @Nullable Asker interactive() {
        if (!Interactivity.canAsk()) return null;
        TerminalSession tty = Terminals.controlling();
        if (!tty.isLive()) return null;
        var in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        return new Asker(tty, CliOutput.stderr(), in, RenderContext.current());
    }

    public RenderContext context() {
        return ctx;
    }

    /**
     * True once input ended: an empty answer since then is a required question with no default, not
     * a person backing out.
     */
    public boolean ended() {
        return ended;
    }

    /** Prints one line of chrome outside any question. */
    public void println(String line) {
        out.print(line + (ctx.ansi() ? "\r\n" : "\n"));
        out.flush();
    }

    /** Prints a settled row for an answer given up front, so the transcript shows every field. */
    public <T> void given(Question<T> question, T value) {
        println(
                ctx.ansi()
                        ? Rows.settled(ctx, question.label(), question.settled(value))
                        : question.label() + "\n" + SETTLED_PREFIX + question.settled(value));
    }

    public <T> Optional<T> ask(Question<T> question) {
        return ctx.ansi() ? raw(question) : cooked(question);
    }

    private <T> Optional<T> raw(Question<T> q) {
        var painter = new RegionPainter(RegionPainter.Park.BELOW);
        try (ModeGuard ignored = tty.enter(InputMode.PROMPT)) {
            out.print("\r" + Ansi.HIDE_CURSOR);
            paint(painter, q);
            while (true) {
                Optional<Key> key = tty.readKey(POLL);
                if (key.isEmpty()) {
                    if (tty.isLive()) continue;
                    return settle(painter, q, new Question.Cancelled<>());
                }
                Question.Reply<T> reply =
                        key.get() instanceof Key.CtrlC ? new Question.Cancelled<>() : q.key(key.get());
                switch (reply) {
                    case Question.Done<T> d -> {
                        return settle(painter, q, d);
                    }
                    case Question.Cancelled<T> c -> {
                        return settle(painter, q, c);
                    }
                    case Question.Pending<T> p -> paint(painter, q);
                    case Question.Retry<T> r -> paint(painter, q);
                }
            }
        } finally {
            out.print(Ansi.SHOW_CURSOR);
            out.flush();
        }
    }

    private <T> void paint(RegionPainter painter, Question<T> q) {
        List<String> rows = new ArrayList<>();
        rows.add(Rows.header(ctx, q.label(), q.help()));
        rows.addAll(q.body(ctx));
        StringBuilder frame = new StringBuilder();
        painter.paint(frame, rows, tty.size().cols(), 0, tty.size().rows());
        out.print(frame);
        out.flush();
    }

    private <T> Optional<T> settle(RegionPainter painter, Question<T> q, Question.Reply<T> reply) {
        StringBuilder frame = new StringBuilder();
        painter.lift(frame, 0, tty.size().rows());
        Optional<T> answer;
        if (reply instanceof Question.Done<T> d) {
            frame.append(Rows.settled(ctx, q.label(), q.settled(d.value())));
            answer = Optional.of(d.value());
        } else {
            frame.append(
                    Theme.paint(Glyphs.CROSS + "  " + q.label(), ctx.theme().error()));
            answer = Optional.empty();
        }
        out.print(frame.append("\r\n"));
        out.flush();
        return answer;
    }

    private <T> Optional<T> cooked(Question<T> q) {
        List<String> menu = q.menu(ctx);
        String prompt;
        if (menu.isEmpty()) {
            prompt = q.label()
                    + (q.help() == null ? "" : " (" + q.help() + ")")
                    + (q.cue().isEmpty() ? "" : " " + q.cue());
        } else {
            out.println(q.label() + (q.help() == null ? "" : "  " + q.help()));
            menu.forEach(out::println);
            prompt = q.cue();
        }
        while (true) {
            out.print(prompt + ": ");
            out.flush();
            String line = null;
            if (!ended) {
                try {
                    line = in.readLine();
                } catch (IOException e) {
                    line = null;
                }
            }
            if (line == null) {
                ended = true;
                out.println();
                // End of input takes the default; a question without one has no answer.
                if (q.line("") instanceof Question.Done<T> d) {
                    out.println(SETTLED_PREFIX + q.settled(d.value()));
                    return Optional.of(d.value());
                }
                return Optional.empty();
            }
            switch (q.line(line.strip())) {
                case Question.Done<T> d -> {
                    out.println(SETTLED_PREFIX + q.settled(d.value()));
                    return Optional.of(d.value());
                }
                case Question.Cancelled<T> c -> {
                    return Optional.empty();
                }
                case Question.Retry<T> r -> r.lines().forEach(out::println);
                case Question.Pending<T> p -> {}
            }
        }
    }
}
