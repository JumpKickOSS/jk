// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Reads named fields of named events out of a JFR recording file, streaming, in memory bounded by
 * the reader's own caps and never by the recording's size, its chunk count or what else its JVM
 * recorded.
 *
 * <p>{@code jdk.jfr.consumer.RecordingFile} resolves every constant pool of a chunk before it hands
 * out the chunk's first event: thread, class, method, symbol and above all stack-trace pools. A
 * suite JVM that also runs an in-process recording with stack traces (a test watching its own reads)
 * writes those stack traces into every recording of the JVM, and one such chunk took 160 MB of the
 * engine's 256 MiB heap (2.3 million pool references, 19,276 stack traces) and ended it. This reader
 * decodes a chunk's metadata (the type and field layout), keeps only its {@code java.lang.String}
 * pool, skips every other pool and every field it was not asked for without building anything, and
 * hands each wanted event to a callback as it passes. What it holds is one chunk's metadata, capped
 * at {@link #MAX_METADATA_BYTES} on disk, and one chunk's string pool, capped at {@link
 * #MAX_STRING_POOL_BYTES} of heap; a chunk past either cap is refused as {@link TooLarge}, and the
 * caller treats the recording as unread.
 *
 * <p>The file format is the one the JDK's own parser reads (jdk.jfr.internal.consumer, format 2.x):
 * a chunk is a 68-byte big-endian header (magic {@code FLR\0}, version, chunk size, constant-pool
 * offset, metadata offset, start nanos, duration, start ticks, ticks per second, state) followed by
 * events, each a LEB128 size and type id; type 0 is the metadata event and type 1 a checkpoint. Every
 * integer past the header is LEB128 encoded.
 */
public final class RecordingReader {

    /** The most bytes a chunk's metadata event may take. The JDK's own is about 115 kB. */
    public static final long MAX_METADATA_BYTES = 2L << 20;

    /** The most heap a chunk's string pool may take, as {@link #accounted} counts it. */
    public static final long MAX_STRING_POOL_BYTES = 16L << 20;

    /**
     * The most heap one read can hold beyond its own buffers: a chunk's metadata decoded (a few
     * objects per type and field, well under four times its bytes) and its string pool.
     */
    public static final long PEAK_BYTES = 4 * MAX_METADATA_BYTES + MAX_STRING_POOL_BYTES;

    private static final int HEADER_SIZE = 68;
    private static final long METADATA_TYPE = 0;
    private static final long CHECKPOINT_TYPE = 1;
    private static final String EVENT_SUPER_TYPE = "jdk.jfr.Event";

    private RecordingReader() {}

    /**
     * Whether {@code file} begins as a recording does: the {@code FLR\0} magic and room for a chunk
     * header, read through a channel this closes. A file that does not is no recording any reader
     * will make sense of (a torn write, a test's stand-in), so it is not handed to the JDK's parser
     * in a child JVM either.
     */
    public static boolean framed(Path file) {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            if (ch.size() < HEADER_SIZE) return false;
            ByteBuffer head = ByteBuffer.allocate(4);
            while (head.hasRemaining() && ch.read(head, head.position()) > 0) {
                // read the magic whole
            }
            return head.position() == 4
                    && head.get(0) == 'F'
                    && head.get(1) == 'L'
                    && head.get(2) == 'R'
                    && head.get(3) == 0;
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }

    /** A chunk too large for the reader's caps: the recording is not read. */
    public static final class TooLarge extends IOException {
        TooLarge(String what) {
            super(what);
        }
    }

    /** Receives each wanted event: its name, its start in epoch nanos and the asked-for fields. */
    @FunctionalInterface
    public interface Sink {
        /**
         * @param values the fields in the order {@link #read} was asked for them: a string field as
         *     its value, a boolean as {@code "true"} or {@code "false"}, a number in decimal, a field
         *     the event type does not have or that was null as {@code null}
         */
        void event(String name, long startNanos, @Nullable String[] values);
    }

    /**
     * Read {@code file}: every event whose type name is a key of {@code wanted}, its fields named by
     * the value, given to {@code sink} in file order. Throws on a file that is not a complete
     * recording ({@link EOFException}, {@link IOException}) and on a chunk past the caps ({@link
     * TooLarge}).
     */
    public static void read(Path file, Map<String, List<String>> wanted, Sink sink) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            In in = new In(ch);
            long size = ch.size();
            long chunkStart = 0;
            while (chunkStart < size) {
                chunkStart = chunk(in, chunkStart, size, wanted, sink);
            }
        }
    }

    /** One chunk from {@code start}; returns where the next one starts. */
    private static long chunk(In in, long start, long fileSize, Map<String, List<String>> wanted, Sink sink)
            throws IOException {
        if (fileSize - start < HEADER_SIZE) throw new EOFException("a chunk header is cut short at " + start);
        in.position(start);
        if (in.rawByte() != 'F' || in.rawByte() != 'L' || in.rawByte() != 'R' || in.rawByte() != 0) {
            throw new IOException("not a recording chunk at " + start);
        }
        int major = in.rawShort();
        in.rawShort(); // minor
        if (major != 2) throw new IOException("recording format " + major + ".x is not read");
        long chunkSize = in.rawLong();
        long cpOffset = in.rawLong();
        long metaOffset = in.rawLong();
        long startNanos = in.rawLong();
        in.rawLong(); // duration
        long startTicks = in.rawLong();
        long ticksPerSecond = in.rawLong();
        long end = start + chunkSize;
        if (chunkSize < HEADER_SIZE || end > fileSize || cpOffset <= 0 || metaOffset <= 0) {
            throw new EOFException("chunk at " + start + " is unfinished");
        }
        Metadata meta = Metadata.read(in, start + metaOffset);
        Map<Long, String> strings = strings(in, start, start + cpOffset, meta);
        Map<Long, Want> byType = meta.wanted(wanted);
        in.position(start + HEADER_SIZE);
        while (in.position() < end) {
            long pos = in.position();
            long eventSize = in.varLong();
            if (eventSize <= 0) throw new IOException("an event of size " + eventSize + " at " + pos);
            long typeId = in.varLong();
            Want want = byType.get(typeId);
            if (want != null) {
                @Nullable String[] values = new String[want.slots];
                long ticks = 0;
                boolean first = true;
                for (Field f : want.type.fields) {
                    Integer slot = want.slotOf.get(f.name);
                    if (first) {
                        // startTime: every event's first field, in ticks
                        ticks = in.varLong();
                        first = false;
                        if (slot != null) values[slot] = Long.toString(ticks);
                    } else if (slot != null) {
                        values[slot] = value(in, f, meta, strings);
                    } else {
                        skip(in, f, meta);
                    }
                }
                long nanos = ticksPerSecond <= 0
                        ? startNanos
                        : startNanos + (long) ((ticks - startTicks) * (1_000_000_000.0 / ticksPerSecond));
                sink.event(want.type.name, nanos, values);
            }
            in.position(pos + eventSize);
        }
        return end;
    }

    /** The chunk's string pool, read through its chain of checkpoints; every other pool skipped. */
    private static Map<Long, String> strings(In in, long chunkStart, long firstCheckpoint, Metadata meta)
            throws IOException {
        Map<Long, String> strings = new HashMap<>();
        long accounted = 0;
        long at = firstCheckpoint;
        long delta = -1;
        int guard = 0;
        while (delta != 0) {
            if (++guard > 1_000_000) throw new IOException("checkpoint chain does not end");
            in.position(at);
            long size = in.varLong();
            if (in.varLong() != CHECKPOINT_TYPE) throw new IOException("no checkpoint at " + at);
            in.varLong(); // start time
            in.varLong(); // duration
            delta = in.varLong();
            in.rawByte(); // checkpoint kind
            int pools = (int) in.varLong();
            for (int p = 0; p < pools; p++) {
                long typeId = in.varLong();
                Type type = meta.type(typeId);
                long count = in.varLong();
                boolean isString = "java.lang.String".equals(type.name);
                for (long i = 0; i < count; i++) {
                    long key = in.varLong();
                    if (isString) {
                        String s = in.string(strings);
                        if (s != null && strings.put(key, s) == null) {
                            accounted += accounted(s);
                            if (accounted > MAX_STRING_POOL_BYTES) {
                                throw new TooLarge(
                                        "a chunk's string pool passes " + (MAX_STRING_POOL_BYTES >> 20) + " MiB");
                            }
                        }
                    } else {
                        skipValue(in, type, meta, 0);
                    }
                }
            }
            if (in.position() != at + size) throw new IOException("checkpoint at " + at + " does not match its size");
            at += delta;
            if (at < chunkStart) throw new IOException("checkpoint chain leaves its chunk");
        }
        return strings;
    }

    /** The heap a pooled string is counted at: its object, its bytes and its map entry. */
    static long accounted(String s) {
        return 96L + 2L * s.length();
    }

    private static @Nullable String value(In in, Field f, Metadata meta, Map<Long, String> strings) throws IOException {
        if (f.dimension > 0 || f.constantPool) {
            skip(in, f, meta);
            return null;
        }
        Type t = meta.type(f.typeId);
        return switch (t.name) {
            case "java.lang.String" -> in.string(strings);
            case "boolean" -> Boolean.toString(in.rawByte() != 0);
            case "byte" -> Byte.toString(in.rawByte());
            case "short" -> Short.toString((short) in.varLong());
            case "int" -> Integer.toString((int) in.varLong());
            case "long" -> Long.toString(in.varLong());
            case "char" -> Integer.toString((char) in.varLong());
            case "float" -> Float.toString(Float.intBitsToFloat(in.rawInt()));
            case "double" -> Double.toString(Double.longBitsToDouble(in.rawLong()));
            default -> {
                skipValue(in, t, meta, 0);
                yield null;
            }
        };
    }

    private static void skip(In in, Field f, Metadata meta) throws IOException {
        if (f.dimension > 0) {
            long n = in.varLong();
            for (long i = 0; i < n; i++) skipScalar(in, f, meta);
            return;
        }
        skipScalar(in, f, meta);
    }

    private static void skipScalar(In in, Field f, Metadata meta) throws IOException {
        if (f.constantPool) {
            in.varLong();
            return;
        }
        skipValue(in, meta.type(f.typeId), meta, 0);
    }

    private static void skipValue(In in, Type t, Metadata meta, int depth) throws IOException {
        if (depth > 64) throw new IOException("type nesting past 64 at " + t.name);
        switch (t.name) {
            case "boolean", "byte" -> in.rawByte();
            case "short", "int", "long", "char" -> in.varLong();
            case "float" -> in.skip(4);
            case "double" -> in.skip(8);
            case "java.lang.String" -> in.skipString();
            default -> {
                for (Field f : t.fields) {
                    if (f.dimension > 0) {
                        long n = in.varLong();
                        for (long i = 0; i < n; i++) skipField(in, f, meta, depth);
                    } else {
                        skipField(in, f, meta, depth);
                    }
                }
            }
        }
    }

    private static void skipField(In in, Field f, Metadata meta, int depth) throws IOException {
        if (f.constantPool) in.varLong();
        else skipValue(in, meta.type(f.typeId), meta, depth + 1);
    }

    /** One field of a type, as the metadata declares it. */
    record Field(String name, long typeId, boolean constantPool, int dimension) {}

    /** One type of a chunk: its name, whether it is an event, its fields in order. */
    static final class Type {
        final String name;
        final boolean event;
        final List<Field> fields = new ArrayList<>(4);

        Type(String name, boolean event) {
            this.name = name;
            this.event = event;
        }
    }

    /** What to take from one event type: a slot per asked-for field. */
    private record Want(Type type, Map<String, Integer> slotOf, int slots) {}

    /** A chunk's metadata: its types by id. */
    static final class Metadata {
        private final Map<Long, Type> types = new HashMap<>();

        Type type(long id) throws IOException {
            Type t = types.get(id);
            if (t == null) throw new IOException("type " + id + " is not declared");
            return t;
        }

        Map<Long, Want> wanted(Map<String, List<String>> wanted) {
            Map<Long, Want> out = new HashMap<>();
            types.forEach((id, t) -> {
                List<String> fields = t.event ? wanted.get(t.name) : null;
                if (fields == null) return;
                Map<String, Integer> slots = new HashMap<>();
                for (int i = 0; i < fields.size(); i++) slots.put(fields.get(i), i);
                out.put(id, new Want(t, slots, fields.size()));
            });
            return out;
        }

        /**
         * The metadata event at {@code at}: a string table, then an element tree whose {@code
         * metadata} element holds a {@code class} element per type, each with its {@code field}
         * elements.
         */
        static Metadata read(In in, long at) throws IOException {
            in.position(at);
            long size = in.varLong();
            if (size > MAX_METADATA_BYTES) {
                throw new TooLarge("a chunk's metadata takes " + size + " bytes");
            }
            if (in.varLong() != METADATA_TYPE) throw new IOException("no metadata event at " + at);
            in.varLong(); // start time
            in.varLong(); // duration
            in.varLong(); // metadata id
            int count = (int) in.varLong();
            if (count < 0 || count > size) throw new IOException("metadata string table of " + count);
            @Nullable String[] table = new String[count];
            for (int i = 0; i < count; i++) table[i] = in.string(null);
            Metadata m = new Metadata();
            m.element(in, table, null, null, 0);
            if (in.position() > at + size) throw new IOException("metadata runs past its size");
            return m;
        }

        /** One element and its children; {@code owner} is the type a {@code field} element belongs to. */
        private void element(In in, @Nullable String[] table, @Nullable String parent, @Nullable Type owner, int depth)
                throws IOException {
            if (depth > 32) throw new IOException("metadata nesting past 32");
            String name = at(table, in.varLong());
            int attributes = (int) in.varLong();
            Map<String, String> attrs = new HashMap<>();
            for (int i = 0; i < attributes; i++) attrs.put(at(table, in.varLong()), at(table, in.varLong()));
            Type type = null;
            if ("class".equals(name) && "metadata".equals(parent)) {
                long id = Long.parseLong(attrs.getOrDefault("id", "-1"));
                type = new Type(attrs.getOrDefault("name", ""), EVENT_SUPER_TYPE.equals(attrs.get("superType")));
                types.put(id, type);
            } else if ("field".equals(name) && "class".equals(parent) && owner != null) {
                owner.fields.add(new Field(
                        attrs.getOrDefault("name", ""),
                        Long.parseLong(attrs.getOrDefault("class", "-1")),
                        attrs.containsKey("constantPool"),
                        Integer.parseInt(attrs.getOrDefault("dimension", "0"))));
            }
            int children = (int) in.varLong();
            for (int i = 0; i < children; i++) element(in, table, name, type != null ? type : owner, depth + 1);
        }

        private static String at(@Nullable String[] table, long index) throws IOException {
            String s = index < 0 || index >= table.length ? null : table[(int) index];
            if (s == null) throw new IOException("metadata names string " + index);
            return s;
        }
    }

    /** Positioned reads over a file channel through one small window. */
    static final class In {
        private final FileChannel ch;
        private final ByteBuffer buf = ByteBuffer.allocate(64 << 10);
        private long bufStart;
        private byte[] bytes = new byte[256];

        In(FileChannel ch) {
            this.ch = ch;
            buf.limit(0);
        }

        long position() {
            return bufStart + buf.position();
        }

        void position(long p) throws IOException {
            if (p >= bufStart && p <= bufStart + buf.limit()) {
                buf.position((int) (p - bufStart));
                return;
            }
            bufStart = p;
            buf.clear();
            buf.limit(0);
        }

        private void fill() throws IOException {
            long p = position();
            buf.clear();
            bufStart = p;
            int n = 0;
            while (n == 0) {
                n = ch.read(buf, p);
                if (n < 0) throw new EOFException("recording ends at " + p);
            }
            buf.flip();
        }

        byte rawByte() throws IOException {
            if (!buf.hasRemaining()) fill();
            return buf.get();
        }

        int rawShort() throws IOException {
            return ((rawByte() & 0xff) << 8) | (rawByte() & 0xff);
        }

        int rawInt() throws IOException {
            return (rawShort() << 16) | rawShort();
        }

        long rawLong() throws IOException {
            return ((long) rawInt() << 32) | (rawInt() & 0xffffffffL);
        }

        void skip(long n) throws IOException {
            position(position() + n);
        }

        /** A LEB128 integer as the JDK writes it: seven bits a byte, the ninth byte whole. */
        long varLong() throws IOException {
            long ret = 0;
            for (int i = 0; i < 8; i++) {
                byte b = rawByte();
                ret += (b & 0x7FL) << (7 * i);
                if (b >= 0) return ret;
            }
            return ret + ((rawByte() & 0xFFL) << 56);
        }

        /** A string in any of its encodings; a pool reference is looked up in {@code pool}. */
        @Nullable
        String string(@Nullable Map<Long, String> pool) throws IOException {
            byte encoding = rawByte();
            switch (encoding) {
                case 0 -> {
                    return null;
                }
                case 1 -> {
                    return "";
                }
                case 2 -> {
                    long key = varLong();
                    return pool == null ? null : pool.get(key);
                }
                case 3, 5 -> {
                    int n = length();
                    if (bytes.length < n) bytes = new byte[Math.max(n, bytes.length * 2)];
                    for (int i = 0; i < n; i++) bytes[i] = rawByte();
                    return new String(
                            bytes, 0, n, encoding == 3 ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1);
                }
                case 4 -> {
                    int n = length();
                    StringBuilder sb = new StringBuilder(n);
                    for (int i = 0; i < n; i++) sb.append((char) varLong());
                    return sb.toString();
                }
                default -> throw new IOException("string encoding " + encoding + " at " + (position() - 1));
            }
        }

        void skipString() throws IOException {
            byte encoding = rawByte();
            switch (encoding) {
                case 0, 1 -> {}
                case 2 -> varLong();
                case 3, 5 -> skip(length());
                case 4 -> {
                    int n = length();
                    for (int i = 0; i < n; i++) varLong();
                }
                default -> throw new IOException("string encoding " + encoding + " at " + (position() - 1));
            }
        }

        /** A string's length, refused past what one chunk's caps allow. */
        private int length() throws IOException {
            long n = varLong();
            if (n < 0 || n > MAX_STRING_POOL_BYTES) throw new TooLarge("a string of " + n);
            return (int) n;
        }
    }
}
