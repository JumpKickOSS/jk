// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Bounded record of a resident worker's non-protocol lines, surfaced when the worker dies: the
 * first lines (where a stack trace names its exception) and the most recent ones (where it ends).
 * A tail alone kept fifty frames of scalac internals and dropped the one line that said why.
 *
 * <p>The record is per work item, not per worker: {@link #reset} runs at each dispatch, because a
 * head that filled during the worker's first compile would otherwise describe every later crash
 * with the JVM's startup chatter instead of the exception that ended the item in flight.
 *
 * <p>Lines are recorded from the process reader thread; {@link #render} may run on another thread
 * after the process has exited, which is what the concurrent deques are for.
 */
final class WorkerTranscript {

    static final int HEAD_MAX = 25;
    static final int TAIL_MAX = 40;

    private final ConcurrentLinkedDeque<String> head = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<String> tail = new ConcurrentLinkedDeque<>();
    private volatile long dropped;

    /** Keep the first {@link #HEAD_MAX} and the last {@link #TAIL_MAX} lines; count the rest. */
    void record(String line) {
        if (head.size() < HEAD_MAX) {
            head.addLast(line);
            return;
        }
        tail.addLast(line);
        while (tail.size() > TAIL_MAX) {
            tail.pollFirst();
            dropped++;
        }
    }

    /** Forget everything: the next line recorded opens a new item's head. */
    void reset() {
        head.clear();
        tail.clear();
        dropped = 0;
    }

    boolean isEmpty() {
        return head.isEmpty();
    }

    /** Head, an elision marker when lines were dropped between them, then the tail. */
    String render() {
        StringBuilder sb = new StringBuilder(String.join("\n", head));
        long elided = dropped;
        if (elided > 0) sb.append("\n... ").append(elided).append(" lines elided ...");
        if (!tail.isEmpty()) sb.append('\n').append(String.join("\n", tail));
        return sb.toString();
    }
}
