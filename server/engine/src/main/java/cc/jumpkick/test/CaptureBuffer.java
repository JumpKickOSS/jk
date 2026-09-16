// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Bounded, thread-safe capture of a worker's non-protocol output. Kept so a hard crash (uncaught
 * throwable / {@code System.exit} before any test event) can be explained — the runner prints the
 * stack to stderr, which is otherwise dropped unless {@code --verbose}. The first {@link
 * #HEAD_LINES} lines and the last {@link #TAIL_LINES} lines are kept and the middle is elided, so
 * a chatty-then-crashing worker cannot blow up memory and the first exception a framework printed
 * survives the thousands of frames that follow it, as does the JVM's own last word.
 */
@NullMarked
final class CaptureBuffer {
    static final int HEAD_LINES = 120;
    static final int TAIL_LINES = 280;
    private final List<String> head = new ArrayList<>();
    private final ArrayDeque<String> tail = new ArrayDeque<>();
    private long elided;

    synchronized void add(@Nullable String line) {
        if (line == null) return;
        if (head.size() < HEAD_LINES) {
            head.add(line);
            return;
        }
        tail.addLast(line);
        if (tail.size() > TAIL_LINES) {
            tail.removeFirst();
            elided++;
        }
    }

    synchronized boolean isEmpty() {
        return head.isEmpty();
    }

    /** The kept lines joined, with one {@code … N lines elided …} line where the middle was cut. */
    synchronized String text() {
        StringBuilder sb = new StringBuilder();
        for (String line : head) sb.append(line).append('\n');
        if (elided > 0)
            sb.append("… ")
                    .append(elided)
                    .append(elided == 1 ? " line" : " lines")
                    .append(" elided …\n");
        for (String line : tail) sb.append(line).append('\n');
        if (sb.length() > 0) sb.setLength(sb.length() - 1);
        return sb.toString();
    }
}
