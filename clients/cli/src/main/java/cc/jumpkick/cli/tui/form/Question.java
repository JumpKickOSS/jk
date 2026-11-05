// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui.form;

import cc.jumpkick.cli.tui.RenderContext;
import cc.jumpkick.terminal.Key;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One question as a state machine the {@link Asker} drives: keys in on a raw TTY, whole lines in
 * when the terminal is plain. A question paints only its body; the asker owns the label row, the
 * settled row and the cursor.
 *
 * @param <T> the answer: a {@code String}, a {@code Boolean}, or a {@code List<String>} of option ids
 */
public sealed interface Question<T> permits LinePrompt, ListSelect, ToggleList, Typeahead, PathPrompt, YesNo {

    /** What a key or a line did. */
    sealed interface Reply<T> {}

    /** Keep asking; repaint the body. */
    record Pending<T>() implements Reply<T> {}

    /** The answer. */
    record Done<T>(T value) implements Reply<T> {}

    /** The user backed out (Esc, Ctrl-C, end of input). */
    record Cancelled<T>() implements Reply<T> {}

    /** A cooked line that is not an answer: print {@code lines}, then ask again. */
    record Retry<T>(List<String> lines) implements Reply<T> {
        public Retry {
            lines = List.copyOf(lines);
        }
    }

    String label();

    @Nullable
    String help();

    /** The rows under the label while the question is active on a raw TTY. */
    List<String> body(RenderContext ctx);

    Reply<T> key(Key key);

    /** Lines printed once before the cooked prompt: a numbered menu, or nothing. */
    List<String> menu(RenderContext ctx);

    /** The cooked prompt's tail after the label, such as {@code [y/N]} or {@code Select [1]}. */
    String cue();

    /** Reads one cooked line; never {@link Pending}. */
    Reply<T> line(String line);

    /** How a settled answer reads. */
    String settled(T value);
}
