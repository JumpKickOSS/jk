// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import java.util.ArrayDeque;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Bounded, thread-safe tail of a worker's non-protocol output. Kept so a hard crash (uncaught
 * throwable / {@code System.exit} before any test event) can be explained — the runner prints the
 * stack to stderr, which is otherwise dropped unless {@code --verbose}. Capped to the last {@link
 * #MAX_LINES} lines so a chatty-then-crashing worker can't blow up memory.
 */
@NullMarked
final class CaptureBuffer {
    private static final int MAX_LINES = 400;
    private final ArrayDeque<String> lines = new ArrayDeque<>();

    synchronized void add(@Nullable String line) {
        if (line == null) return;
        lines.addLast(line);
        if (lines.size() > MAX_LINES) lines.removeFirst();
    }

    synchronized boolean isEmpty() {
        return lines.isEmpty();
    }

    synchronized String text() {
        return String.join("\n", lines);
    }
}
