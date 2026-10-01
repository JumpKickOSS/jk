// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import java.util.HashMap;
import java.util.Map;

/**
 * One stream's table of the test-failure texts (message and stack) it has carried, so a text that
 * repeats crosses the stream once. The first line to carry a text names it {@code textId}; every
 * later line with the same text omits message and stack and says {@code sameText} with that id. A
 * writer keeps one table per stream it writes and a reader one per stream it reads; ids are only
 * meaningful within their stream. Thread-safe.
 */
public final class FailureTextRefs {

    /** A failure's message and stack. */
    public record Text(String message, String stack) {}

    /**
     * How a line carries its text: {@code textId} when it introduces one, {@code sameText} when it
     * repeats one, both 0 when it carries none.
     */
    public record Ref(int textId, int sameText) {
        public static final Ref NONE = new Ref(0, 0);
    }

    /** Distinct texts a table holds; past it a new text is sent whole, never named. */
    static final int MAX_TEXTS = 4_096;

    private final Map<Text, Integer> ids = new HashMap<>();
    private final Map<Integer, Text> texts = new HashMap<>();

    /** The writer's ref for a line carrying {@code message} and {@code stack}. */
    public synchronized Ref ref(String message, String stack) {
        if (message.isEmpty() && stack.isEmpty()) return Ref.NONE;
        Text text = new Text(message, stack);
        Integer id = ids.get(text);
        if (id != null) return new Ref(0, id);
        if (ids.size() >= MAX_TEXTS) return Ref.NONE;
        int next = ids.size() + 1;
        ids.put(text, next);
        return new Ref(next, 0);
    }

    /**
     * The reader's text for a line: the one {@code sameText} names, else the line's own, held under
     * {@code textId} for later lines. A {@code sameText} this table never held reads as the line's.
     */
    public synchronized Text resolve(int textId, int sameText, String message, String stack) {
        if (sameText > 0) {
            Text held = texts.get(sameText);
            return held != null ? held : new Text(message, stack);
        }
        Text own = new Text(message, stack);
        if (textId > 0 && texts.size() < MAX_TEXTS) texts.put(textId, own);
        return own;
    }
}
