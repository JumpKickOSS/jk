// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui.form;

import cc.jumpkick.cli.testing.NoAnsi;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.LiveRegionScreen;
import cc.jumpkick.cli.tui.RenderContext;
import cc.jumpkick.config.NerdFontCaps;
import cc.jumpkick.terminal.MemoryTerminal;
import cc.jumpkick.terminal.Terminals;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Drives a {@link Question} or a {@link FormRenderer} the way a person would: keystrokes into a
 * memory TTY for the nerd and ANSI modes, whole lines on stdin for plain mode. A raw run keeps the
 * screen after every flush, through the same VT viewport the live-region tests use, so a test can
 * read what each keystroke showed.
 */
public final class Scripted {

    public static final String UP = "\u001b[A";
    public static final String DOWN = "\u001b[B";
    public static final String RIGHT = "\u001b[C";
    public static final String LEFT = "\u001b[D";
    public static final String ENTER = "\r";
    public static final String TAB = "\t";
    public static final String BACKSPACE = "\u007f";
    public static final String CTRL_C = "\u0003";

    /** The private-use codepoints only the nerd mode may paint. */
    public static final String PILL_CAP = "";

    public enum Mode {
        NERD,
        ANSI,
        PLAIN
    }

    /** One run: the answer, every frame as screen rows, and the raw bytes written. */
    public record Run<T>(Optional<T> answer, List<List<String>> frames, String written) {
        /** The screen as the run left it. */
        public List<String> last() {
            return frames.isEmpty() ? List.of() : frames.getLast();
        }

        /** The first frame that shows {@code text}, joined by newlines. */
        public String frameWith(String text) {
            for (List<String> f : frames) {
                String joined = String.join("\n", f);
                if (joined.contains(text)) return joined;
            }
            throw new AssertionError("no frame shows " + text + " in " + frames);
        }
    }

    private Scripted() {}

    /** {@code keys} in nerd or ANSI mode, {@code lines} in plain mode. */
    public static <T> Run<T> run(Mode mode, Question<T> question, String keys, String lines) throws Exception {
        return drive(mode, keys, lines, asker -> asker.ask(question));
    }

    public static <R> Run<R> drive(Mode mode, String keys, String lines, Function<Asker, Optional<R>> body)
            throws Exception {
        if (mode == Mode.PLAIN) return NoAnsi.forced(() -> cooked(lines, body));
        NerdFontCaps caps = mode == Mode.NERD ? NerdFontCaps.ALL : NerdFontCaps.NONE;
        return NoAnsi.forcedAnsi(() -> raw(keys, caps, body));
    }

    private static <R> Run<R> raw(String keys, NerdFontCaps caps, Function<Asker, Optional<R>> body) {
        var screen = new LiveRegionScreen(24, 80);
        List<List<String>> frames = new ArrayList<>();
        var bytes = new FrameStream(screen, frames);
        try (MemoryTerminal tty = Terminals.memory(
                        new ByteArrayInputStream(keys.getBytes(StandardCharsets.UTF_8)), new ByteArrayOutputStream());
                var out = new PrintStream(bytes, false, StandardCharsets.UTF_8)) {
            var ctx = new RenderContext(Theme.active(), true, caps, 80, 0);
            var asker = new Asker(tty, out, new BufferedReader(new StringReader("")), ctx);
            Optional<R> answer = body.apply(asker);
            out.flush();
            return new Run<>(answer, frames, bytes.all.toString(StandardCharsets.UTF_8));
        }
    }

    private static <R> Run<R> cooked(String lines, Function<Asker, Optional<R>> body) {
        var bytes = new ByteArrayOutputStream();
        try (MemoryTerminal tty = Terminals.memory(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream());
                var out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            var ctx = new RenderContext(Theme.active(), false, NerdFontCaps.NONE, 80, 0);
            var asker = new Asker(tty, out, new BufferedReader(new StringReader(lines)), ctx);
            Optional<R> answer = body.apply(asker);
            String transcript = bytes.toString(StandardCharsets.UTF_8);
            return new Run<>(answer, List.of(List.of(transcript.split("\n", -1))), transcript);
        }
    }

    /** Feeds each flushed chunk to the screen and keeps the viewport it leaves. */
    private static final class FrameStream extends ByteArrayOutputStream {
        final ByteArrayOutputStream all = new ByteArrayOutputStream();
        private final LiveRegionScreen screen;
        private final List<List<String>> frames;

        FrameStream(LiveRegionScreen screen, List<List<String>> frames) {
            this.screen = screen;
            this.frames = frames;
        }

        @Override
        public void flush() {
            if (size() == 0) return;
            screen.write(toString(StandardCharsets.UTF_8));
            all.writeBytes(toByteArray());
            reset();
            List<String> rows = new ArrayList<>(screen.viewport());
            while (!rows.isEmpty() && rows.getLast().isEmpty()) rows.removeLast();
            frames.add(rows);
        }
    }
}
