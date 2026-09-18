// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.ZipException;
import org.jspecify.annotations.Nullable;

/**
 * A jar's {@code .class} entries in name order, read from the central directory as a stream.
 * {@link java.util.zip.ZipFile} holds the whole directory in heap and a {@code ZipEntry} per entry
 * it lists, which on a bundle jar of four hundred thousand classes is a hundred megabytes before
 * the first class is read — more than the engine's whole heap has to spare. Here the directory is
 * scanned once to count the classes and sample their names, and then once per window of {@link
 * #WINDOW} names, each window collected, sorted and read before the next is scanned, so a jar of
 * any size holds a few thousand names at a time and the order is the one a sort of every name
 * would give.
 *
 * <p>Handles ZIP64 directories and a jar with bytes prepended (a self-extracting header); a
 * file with no end-of-central-directory record is a {@link ZipException}, as it is for {@code
 * ZipFile}. Names are decoded as UTF-8, as {@code ZipFile} decodes them. Not thread-safe.
 */
final class JarClassReader implements Closeable {

    /** Names held at once. */
    static final int WINDOW = 16_384;

    /** One name in this many is kept from the counting scan to place the windows' boundaries (fewer for a small window). */
    private static final int SAMPLE_EVERY = 64;

    private static final int EOCD_SIG = 0x06054b50;
    private static final int ZIP64_LOCATOR_SIG = 0x07064b50;
    private static final int ZIP64_EOCD_SIG = 0x06064b50;
    private static final int CEN_SIG = 0x02014b50;
    private static final int LOC_SIG = 0x04034b50;
    private static final int EOCD_LEN = 22;
    private static final int LOC_LEN = 30;
    private static final int MAX_COMMENT = 0xFFFF;
    private static final long U32_MAX = 0xFFFFFFFFL;
    private static final int METHOD_STORED = 0;
    private static final int METHOD_DEFLATED = 8;
    private static final byte[] CLASS_SUFFIX = ".class".getBytes(StandardCharsets.US_ASCII);

    /** What a class costs to find again: where its local header is and how its payload is stored. */
    record ClassEntry(String name, long localOffset, long compressedSize, long size, int method) {}

    /** Receives each class with its bytes, in name order. */
    @FunctionalInterface
    interface ClassSink {
        void accept(String name, byte[] bytes) throws IOException;
    }

    private final FileChannel channel;
    private final long cenOffset;
    private final long cenSize;
    private final long prepended;
    private final int window;

    private JarClassReader(FileChannel channel, long cenOffset, long cenSize, long prepended, int window) {
        this.channel = channel;
        this.cenOffset = cenOffset;
        this.cenSize = cenSize;
        this.prepended = prepended;
        this.window = window;
    }

    static JarClassReader open(Path jar) throws IOException {
        return open(jar, WINDOW);
    }

    /** As {@link #open(Path)} with the names held at once set to {@code window}; for tests of the windowed path. */
    static JarClassReader open(Path jar, int window) throws IOException {
        if (window < 1) throw new IllegalArgumentException("window must be positive");
        FileChannel channel = FileChannel.open(jar, StandardOpenOption.READ);
        try {
            long size = channel.size();
            int tail = (int) Math.min(size, EOCD_LEN + MAX_COMMENT);
            ByteBuffer end = read(channel, size - tail, tail);
            int at = -1;
            for (int i = tail - EOCD_LEN; i >= 0; i--) {
                if (end.getInt(i) == EOCD_SIG) {
                    at = i;
                    break;
                }
            }
            if (at < 0) throw new ZipException("no end-of-central-directory record in " + jar);
            long eocdPos = size - tail + at;
            long entries = end.getShort(at + 10) & 0xFFFF;
            long cenSize = end.getInt(at + 12) & U32_MAX;
            long cenOffset = end.getInt(at + 16) & U32_MAX;
            long endPos = eocdPos;
            if (entries == 0xFFFF || cenSize == U32_MAX || cenOffset == U32_MAX) {
                long locatorPos = eocdPos - 20;
                if (locatorPos >= 0) {
                    ByteBuffer locator = read(channel, locatorPos, 20);
                    if (locator.getInt(0) == ZIP64_LOCATOR_SIG) {
                        long zip64Pos = locator.getLong(8);
                        ByteBuffer zip64 = read(channel, zip64Pos, 56);
                        if (zip64.getInt(0) != ZIP64_EOCD_SIG) throw new ZipException("bad ZIP64 end record in " + jar);
                        cenSize = zip64.getLong(40);
                        cenOffset = zip64.getLong(48);
                        endPos = zip64Pos;
                    }
                }
            }
            long prepended = endPos - cenSize - cenOffset;
            if (prepended < 0 || cenSize < 0) throw new ZipException("central directory out of bounds in " + jar);
            return new JarClassReader(channel, cenOffset + prepended, cenSize, prepended, window);
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    /**
     * Every {@code .class} entry, in {@link String#compareTo} order of its name, with the bytes
     * the entry holds. A jar of at most {@link #WINDOW} classes is one scan; a larger one is one
     * scan more per window.
     */
    void forEachClass(ClassSink sink) throws IOException {
        List<ClassEntry> first = new ArrayList<>();
        List<String> sample = new ArrayList<>();
        int stride = Math.max(1, Math.min(SAMPLE_EVERY, window / 4));
        long[] count = {0};
        scan(e -> {
            if (count[0] % stride == 0) sample.add(e.name());
            if (count[0] < window) first.add(e);
            count[0]++;
        });
        if (count[0] <= window) {
            emit(first, sink);
            return;
        }
        first.clear();
        List<String> sorted = new ArrayList<>(new TreeSet<>(sample));
        int step = Math.max(1, window / stride);
        List<@Nullable String> bounds = new ArrayList<>();
        for (int i = step; i < sorted.size(); i += step) bounds.add(sorted.get(i));
        bounds.add(null);
        @Nullable String lo = null;
        for (@Nullable String hi : bounds) {
            List<ClassEntry> bucket = new ArrayList<>();
            @Nullable String from = lo;
            scan(e -> {
                if ((from == null || e.name().compareTo(from) >= 0)
                        && (hi == null || e.name().compareTo(hi) < 0)) {
                    bucket.add(e);
                }
            });
            emit(bucket, sink);
            lo = hi;
        }
    }

    private void emit(List<ClassEntry> bucket, ClassSink sink) throws IOException {
        bucket.sort(Comparator.comparing(ClassEntry::name));
        for (ClassEntry e : bucket) sink.accept(e.name(), read(e));
    }

    /** One pass over the central directory, handing every non-directory {@code .class} entry to {@code sink}. */
    private void scan(Consumer<ClassEntry> sink) throws IOException {
        Cursor in = new Cursor(channel, cenOffset, cenOffset + cenSize);
        while (in.remaining() >= 46) {
            if (in.s32() != CEN_SIG) throw new ZipException("bad central directory entry");
            in.skip(6);
            int method = in.u16();
            in.skip(8);
            long compressed = in.u32();
            long size = in.u32();
            int nameLen = in.u16();
            int extraLen = in.u16();
            int commentLen = in.u16();
            in.skip(8);
            long localOffset = in.u32();
            byte[] name = in.bytes(nameLen);
            boolean isClass = endsWithClass(name);
            if (isClass && (compressed == U32_MAX || size == U32_MAX || localOffset == U32_MAX)) {
                byte[] extra = in.bytes(extraLen);
                long[] wide = zip64(extra, size, compressed, localOffset);
                size = wide[0];
                compressed = wide[1];
                localOffset = wide[2];
            } else {
                in.skip(extraLen);
            }
            in.skip(commentLen);
            if (!isClass) continue;
            sink.accept(new ClassEntry(
                    new String(name, StandardCharsets.UTF_8), localOffset + prepended, compressed, size, method));
        }
    }

    private static boolean endsWithClass(byte[] name) {
        if (name.length < CLASS_SUFFIX.length) return false;
        for (int i = 0; i < CLASS_SUFFIX.length; i++) {
            if (name[name.length - CLASS_SUFFIX.length + i] != CLASS_SUFFIX[i]) return false;
        }
        return true;
    }

    /** The ZIP64 extra field's wide sizes and offset, each present only where the narrow field is at its ceiling. */
    private static long[] zip64(byte[] extra, long size, long compressed, long localOffset) throws ZipException {
        ByteBuffer b = ByteBuffer.wrap(extra).order(ByteOrder.LITTLE_ENDIAN);
        while (b.remaining() >= 4) {
            int id = b.getShort() & 0xFFFF;
            int len = b.getShort() & 0xFFFF;
            if (len > b.remaining()) throw new ZipException("bad extra field");
            if (id != 0x0001) {
                b.position(b.position() + len);
                continue;
            }
            int end = b.position() + len;
            if (size == U32_MAX && b.position() + 8 <= end) size = b.getLong();
            if (compressed == U32_MAX && b.position() + 8 <= end) compressed = b.getLong();
            if (localOffset == U32_MAX && b.position() + 8 <= end) localOffset = b.getLong();
            break;
        }
        return new long[] {size, compressed, localOffset};
    }

    /** The entry's bytes: the payload behind its local header, inflated when it is deflated. */
    private byte[] read(ClassEntry e) throws IOException {
        ByteBuffer head = read(channel, e.localOffset(), LOC_LEN);
        if (head.getInt(0) != LOC_SIG) throw new ZipException("bad local header for " + e.name());
        int nameLen = head.getShort(26) & 0xFFFF;
        int extraLen = head.getShort(28) & 0xFFFF;
        long data = e.localOffset() + LOC_LEN + nameLen + extraLen;
        if (e.compressedSize() > Integer.MAX_VALUE - 8 || e.size() > Integer.MAX_VALUE - 8) {
            throw new ZipException("entry too large: " + e.name());
        }
        byte[] payload = read(channel, data, (int) e.compressedSize()).array();
        if (e.method() == METHOD_STORED) return payload;
        if (e.method() != METHOD_DEFLATED) {
            throw new ZipException("unsupported compression method " + e.method() + " for " + e.name());
        }
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(payload);
            byte[] out = new byte[(int) e.size()];
            int n = 0;
            while (n < out.length) {
                int k = inflater.inflate(out, n, out.length - n);
                if (k == 0 && (inflater.finished() || inflater.needsInput())) break;
                n += k;
            }
            if (n != out.length) throw new ZipException("short entry " + e.name() + ": " + n + " of " + out.length);
            return out;
        } catch (DataFormatException ex) {
            throw new ZipException("corrupt entry " + e.name() + ": " + ex.getMessage());
        } finally {
            inflater.end();
        }
    }

    private static ByteBuffer read(FileChannel channel, long position, int length) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        long at = position;
        while (b.hasRemaining()) {
            int n = channel.read(b, at);
            if (n < 0) throw new ZipException("truncated archive at " + at);
            at += n;
        }
        b.flip();
        return b;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    /** A little-endian reader over one byte range of the channel, refilled from positional reads. */
    private static final class Cursor {
        private static final int BUFFER = 1 << 16;
        private final FileChannel channel;
        private final long end;
        private long position;
        private final ByteBuffer buf =
                ByteBuffer.allocate(BUFFER).order(ByteOrder.LITTLE_ENDIAN).limit(0);

        Cursor(FileChannel channel, long start, long end) {
            this.channel = channel;
            this.position = start;
            this.end = end;
        }

        long remaining() {
            return end - position + buf.remaining();
        }

        private void need(int n) throws IOException {
            if (buf.remaining() >= n) return;
            buf.compact();
            long filePos = position;
            while (buf.position() < n && filePos < end) {
                int limit = (int) Math.min(buf.remaining(), end - filePos);
                ByteBuffer slice = buf.slice(buf.position(), limit);
                int got = channel.read(slice, filePos);
                if (got < 0) break;
                buf.position(buf.position() + got);
                filePos += got;
            }
            position = filePos;
            buf.flip();
            if (buf.remaining() < n) throw new ZipException("truncated central directory");
        }

        int u16() throws IOException {
            need(2);
            return buf.getShort() & 0xFFFF;
        }

        long u32() throws IOException {
            need(4);
            return buf.getInt() & U32_MAX;
        }

        int s32() throws IOException {
            need(4);
            return buf.getInt();
        }

        void skip(int n) throws IOException {
            while (n > 0) {
                need(1);
                int k = Math.min(n, buf.remaining());
                buf.position(buf.position() + k);
                n -= k;
            }
        }

        byte[] bytes(int n) throws IOException {
            byte[] out = new byte[n];
            int done = 0;
            while (done < n) {
                need(1);
                int k = Math.min(n - done, buf.remaining());
                buf.get(out, done, k);
                done += k;
            }
            return out;
        }
    }
}
