// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.terminal.Styled;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * One radio/multi-select option. Optional {@code hint}/{@code hintFn} suffix; optional
 * {@code richLabelFn} for multi-style labels (focused flag in the {@code Boolean} arg).
 */
public record Choice(
        @Nullable String id,
        @Nullable String label,
        String hint,
        @Nullable Function<Answers, String> hintFn,
        @Nullable Function<Boolean, Styled> richLabelFn) {

    public Choice {
        if (hint == null) hint = "";
    }

    public Choice(String id, @Nullable String label) {
        this(id, label, "", null, null);
    }

    public Choice(@Nullable String id, @Nullable String label, String hint) {
        this(id, label, hint, null, null);
    }

    public Choice(@Nullable String id, @Nullable String label, Function<Answers, String> hintFn) {
        this(id, label, "", hintFn, null);
    }

    /** Rich-label factory — caller supplies focused/unfocused renderings. */
    public static Choice rich(String id, @Nullable String fallbackLabel, Function<Boolean, Styled> richLabelFn) {
        return new Choice(id, fallbackLabel, "", null, richLabelFn);
    }

    /** Rich-label factory with a hint suffix. */
    public static Choice rich(
            String id, @Nullable String fallbackLabel, String hint, Function<Boolean, Styled> richLabelFn) {
        return new Choice(id, fallbackLabel, hint, null, richLabelFn);
    }

    /** Resolved hint at render time. Dynamic {@code hintFn} wins over static {@code hint}. */
    public String hintFor(Answers answers) {
        if (hintFn != null) {
            var v = hintFn.apply(answers);
            return v == null ? "" : v;
        }
        return hint;
    }
}
