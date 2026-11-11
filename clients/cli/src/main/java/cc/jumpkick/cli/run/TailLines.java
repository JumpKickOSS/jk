// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import java.util.AbstractList;
import java.util.ArrayList;

/**
 * A list of output lines that keeps the newest {@code cap} bytes of them (UTF-8, a newline each):
 * past that the oldest lines go, and the list opens with one line naming how many did. A module's
 * output held until it settles cannot grow the client without bound. Not thread-safe; wrap it as
 * any list.
 */
public final class TailLines extends AbstractList<String> {

    /** What a held module's output keeps: 8 MiB. */
    public static final long CAP = 8L * 1024 * 1024;

    private final long cap;
    private final ArrayList<String> lines = new ArrayList<>();
    /** Index of the oldest kept line in {@link #lines}; the ones before it are dropped. */
    private int head;

    private long bytes;
    private long dropped;

    public TailLines() {
        this(CAP);
    }

    TailLines(long cap) {
        this.cap = cap;
    }

    @Override
    public boolean add(String line) {
        lines.add(line);
        bytes += utf8Length(line) + 1;
        while (bytes > cap && head < lines.size() - 1) {
            bytes -= utf8Length(lines.get(head)) + 1;
            lines.set(head, null);
            head++;
            dropped++;
        }
        // Compact once the dropped prefix is the larger part, so the array tracks what is kept.
        if (head > 1024 && head > lines.size() / 2) {
            lines.subList(0, head).clear();
            head = 0;
        }
        modCount++;
        return true;
    }

    @Override
    public String get(int index) {
        if (dropped > 0) {
            if (index == 0) return "(" + dropped + " earlier lines of output dropped)";
            index--;
        }
        if (index < 0 || index >= lines.size() - head) throw new IndexOutOfBoundsException(index);
        return lines.get(head + index);
    }

    @Override
    public int size() {
        return lines.size() - head + (dropped > 0 ? 1 : 0);
    }

    @Override
    public void clear() {
        lines.clear();
        head = 0;
        bytes = 0;
        dropped = 0;
        modCount++;
    }

    /** Lines dropped from the head so far. */
    public long dropped() {
        return dropped;
    }

    private static long utf8Length(String s) {
        long n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x80) n++;
            else if (c < 0x800) n += 2;
            else if (Character.isHighSurrogate(c)) {
                n += 4;
                i++;
            } else n += 3;
        }
        return n;
    }
}
