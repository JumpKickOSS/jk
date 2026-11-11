// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * An in-memory output stream that keeps the last {@code cap} bytes written: past that the oldest
 * go, so a command whose output is held until it ends cannot grow the client without bound.
 * {@link #toByteArray} starts at a line boundary once anything was dropped, under a line naming
 * how many bytes went.
 */
public final class TailBuffer extends OutputStream {

    /** What a held stream keeps: 8 MiB. */
    public static final int CAP = 8 * 1024 * 1024;

    private final int cap;
    private byte[] ring;
    private int start;
    private int size;
    private long dropped;

    public TailBuffer() {
        this(CAP);
    }

    TailBuffer(int cap) {
        this.cap = cap;
        this.ring = new byte[Math.min(8192, cap)];
    }

    @Override
    public synchronized void write(int b) {
        put((byte) b);
    }

    @Override
    public synchronized void write(byte[] b, int off, int len) {
        // Only the last cap bytes of a single write can survive it.
        if (len > cap) {
            int skip = len - cap;
            dropped += size + skip;
            start = 0;
            size = 0;
            off += skip;
            len = cap;
        }
        for (int i = 0; i < len; i++) put(b[off + i]);
    }

    private void put(byte b) {
        // The array grows to the cap before it wraps, so a small output costs a small array.
        if (size == ring.length && ring.length < cap) ring = Arrays.copyOf(ring, Math.min(cap, ring.length * 2));
        if (size == ring.length) {
            ring[start] = b;
            start = (start + 1) % ring.length;
            dropped++;
            return;
        }
        ring[(start + size) % ring.length] = b;
        size++;
    }

    /** Bytes dropped from the head so far. */
    public synchronized long dropped() {
        return dropped;
    }

    /** What is kept, oldest first; once bytes were dropped, from the first whole line on, under a note. */
    public synchronized byte[] toByteArray() {
        int from = 0;
        if (dropped > 0) {
            // A cut line, and a cut UTF-8 sequence with it, is not shown.
            while (from < size && ring[(start + from) % ring.length] != '\n') from++;
            if (from < size) from++;
        }
        byte[] note = dropped > 0
                ? ("(" + (dropped + from) + " earlier bytes of output dropped)\n").getBytes(StandardCharsets.UTF_8)
                : new byte[0];
        byte[] out = new byte[note.length + size - from];
        System.arraycopy(note, 0, out, 0, note.length);
        for (int i = from; i < size; i++) out[note.length + i - from] = ring[(start + i) % ring.length];
        return out;
    }
}
