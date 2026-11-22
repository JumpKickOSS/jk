// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.savings;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * One column of a table in an SQLite database, read straight from the file and its write-ahead log
 * ({@code <file>-wal}) without SQLite and without writing anything, not even the shared-memory
 * index. Only what an agent's usage file needs: rowid tables, read whole, one column at a time.
 *
 * <p>The format is <a href="https://www.sqlite.org/fileformat2.html">sqlite.org/fileformat2</a>.
 * The log's frames count up to its last commit whose salts and checksums hold; a database written
 * while it is read may fail with an {@link IOException}, never with a wrong row.
 */
final class SqliteFile implements AutoCloseable {

    private static final byte[] MAGIC = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);

    /** Pages one table may span before the file is taken as broken. */
    private static final int MAX_PAGES = 1 << 20;

    private final FileChannel db;
    private final @Nullable FileChannel wal;
    private final int pageSize;
    private final int usable;

    /** Each page the log holds, by number: the offset of its newest committed copy in the log. */
    private final Map<Integer, Long> logged;

    private SqliteFile(FileChannel db, @Nullable FileChannel wal, int pageSize, int usable, Map<Integer, Long> logged) {
        this.db = db;
        this.wal = wal;
        this.pageSize = pageSize;
        this.usable = usable;
        this.logged = logged;
    }

    /** {@code file} and its log, when it has one. */
    static SqliteFile open(Path file) throws IOException {
        FileChannel db = FileChannel.open(file, StandardOpenOption.READ);
        FileChannel wal = null;
        try {
            ByteBuffer header = ByteBuffer.allocate(100);
            db.read(header, 0);
            byte[] h = header.array();
            boolean empty = header.position() < 100;
            if (!empty && !Arrays.equals(h, 0, MAGIC.length, MAGIC, 0, MAGIC.length)) {
                throw new IOException(file + ": not an SQLite database");
            }
            Path log = file.resolveSibling(file.getFileName() + "-wal");
            try {
                wal = FileChannel.open(log, StandardOpenOption.READ);
            } catch (NoSuchFileException e) {
                wal = null;
            }
            int pageSize = empty ? 0 : pageSize(((h[16] & 0xff) << 8) | (h[17] & 0xff));
            int reserved = empty ? 0 : h[20] & 0xff;
            Map<Integer, Long> logged = new HashMap<>();
            if (wal != null) pageSize = frames(wal, pageSize, logged);
            if (pageSize < 512) throw new IOException(file + ": no page size");
            return new SqliteFile(db, wal, pageSize, pageSize - reserved, logged);
        } catch (IOException | RuntimeException e) {
            db.close();
            if (wal != null) wal.close();
            throw e;
        }
    }

    private static int pageSize(int raw) {
        return raw == 1 ? 65_536 : raw;
    }

    /**
     * Reads the log's committed frames into {@code logged}; returns the page size, the log's when the
     * database itself has none yet. A log whose header or first frame does not check out holds nothing.
     */
    private static int frames(FileChannel wal, int pageSize, Map<Integer, Long> logged) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(32);
        wal.read(header, 0);
        if (header.position() < 32) return pageSize;
        header.flip();
        int magic = header.getInt(0);
        if (magic != 0x377f0682 && magic != 0x377f0683) return pageSize;
        ByteOrder order = (magic & 1) == 1 ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
        int size = pageSize(header.getInt(8));
        if (size < 512 || (pageSize != 0 && size != pageSize)) return pageSize;
        int salt1 = header.getInt(16);
        int salt2 = header.getInt(20);
        int[] sum = checksum(header.array(), 0, 24, order, 0, 0);
        if (sum[0] != header.getInt(24) || sum[1] != header.getInt(28)) return pageSize;
        Map<Integer, Long> pending = new HashMap<>();
        ByteBuffer frame = ByteBuffer.allocate(24 + size);
        for (long at = 32; ; at += 24 + size) {
            frame.clear();
            while (frame.hasRemaining() && wal.read(frame, at + frame.position()) > 0) {}
            if (frame.hasRemaining()) break;
            byte[] f = frame.array();
            if (frame.getInt(8) != salt1 || frame.getInt(12) != salt2) break;
            sum = checksum(f, 0, 8, order, sum[0], sum[1]);
            sum = checksum(f, 24, size, order, sum[0], sum[1]);
            if (sum[0] != frame.getInt(16) || sum[1] != frame.getInt(20)) break;
            pending.put(frame.getInt(0), at + 24);
            if (frame.getInt(4) != 0) {
                logged.putAll(pending);
                pending.clear();
            }
        }
        return size;
    }

    /** SQLite's log checksum of {@code len} bytes (a multiple of 8) from {@code off}, continuing {@code s0, s1}. */
    private static int[] checksum(byte[] b, int off, int len, ByteOrder order, int s0, int s1) {
        ByteBuffer in = ByteBuffer.wrap(b, off, len).order(order);
        for (int i = off; i < off + len; i += 8) {
            s0 += in.getInt(i) + s1;
            s1 += in.getInt(i + 4) + s0;
        }
        return new int[] {s0, s1};
    }

    /** Column {@code column} (0 is the first) of every row of {@code table}; empty when there is no such table. */
    List<@Nullable Object> column(String table, int column) throws IOException {
        int root = -1;
        for (List<@Nullable Object> row : rows(1, 4)) {
            if ("table".equals(row.get(0)) && table.equals(row.get(1)) && row.get(3) instanceof Long r) {
                root = r.intValue();
            }
        }
        if (root < 1) return List.of();
        List<@Nullable Object> out = new ArrayList<>();
        for (List<@Nullable Object> row : rows(root, column + 1)) out.add(row.get(column));
        return out;
    }

    /** The first {@code columns} columns of each row of the table rooted at page {@code root}. */
    private List<List<@Nullable Object>> rows(int root, int columns) throws IOException {
        List<List<@Nullable Object>> out = new ArrayList<>();
        Deque<Integer> todo = new ArrayDeque<>();
        Set<Integer> seen = new HashSet<>();
        todo.push(root);
        while (!todo.isEmpty()) {
            int n = todo.pop();
            if (!seen.add(n) || seen.size() > MAX_PAGES) throw new IOException("page " + n + ": a loop");
            ByteBuffer page = page(n);
            int h = n == 1 ? 100 : 0;
            int type = page.get(h) & 0xff;
            int cells = page.getShort(h + 3) & 0xffff;
            if (type == 0x05) {
                // Pushed right first so rows come out in rowid order.
                todo.push(page.getInt(h + 8));
                for (int i = cells - 1; i >= 0; i--) {
                    todo.push(page.getInt(page.getShort(h + 12 + 2 * i) & 0xffff));
                }
            } else if (type == 0x0d) {
                for (int i = 0; i < cells; i++) {
                    out.add(record(page, page.getShort(h + 8 + 2 * i) & 0xffff, columns));
                }
            } else {
                throw new IOException("page " + n + ": not a table page");
            }
        }
        return out;
    }

    /** The first {@code columns} columns of the leaf cell at {@code at}. */
    private List<@Nullable Object> record(ByteBuffer page, int at, int columns) throws IOException {
        long[] v = varint(page, at);
        int p = (int) varint(page, (int) v[1])[1]; // past the rowid
        if (v[0] < 0 || v[0] > Integer.MAX_VALUE) throw new IOException("a row too large");
        int size = (int) v[0];
        int local = local(size);
        Payload payload = new Payload(new byte[size], local, local < size ? page.getInt(p + local) : 0);
        page.get(p, payload.bytes, 0, local);
        ByteBuffer rec = ByteBuffer.wrap(payload.bytes);
        long[] header = varint(rec, 0);
        // The header, then the body only as far as the last column wanted.
        payload.need(header[0]);
        List<Long> types = new ArrayList<>();
        int q = (int) header[1];
        long end = header[0];
        while (q < header[0] && types.size() < columns) {
            long[] t = varint(rec, q);
            types.add(t[0]);
            q = (int) t[1];
            end += width(t[0]);
        }
        payload.need(end);
        List<@Nullable Object> out = new ArrayList<>();
        long off = header[0];
        for (long t : types) {
            out.add(value(payload.bytes, (int) off, t));
            off += width(t);
        }
        while (out.size() < columns) out.add(null);
        return out;
    }

    /** A row's payload as far as it has been read: {@code have} bytes, the rest from overflow page {@code next} on. */
    private final class Payload {
        final byte[] bytes;
        int have;
        int next;

        Payload(byte[] bytes, int have, int next) {
            this.bytes = bytes;
            this.have = have;
            this.next = next;
        }

        /** Reads overflow pages until the first {@code want} bytes are here. */
        void need(long want) throws IOException {
            if (want > bytes.length) throw new IOException("a row longer than its payload");
            int pages = 0;
            while (have < want) {
                if (next == 0 || ++pages > MAX_PAGES) throw new IOException("a row cut short");
                ByteBuffer o = page(next);
                int n = Math.min(usable - 4, bytes.length - have);
                o.get(4, bytes, have, n);
                have += n;
                next = o.getInt(0);
            }
        }
    }

    /** How many of a {@code size}-byte payload's bytes a table leaf page keeps itself. */
    private int local(int size) {
        int x = usable - 35;
        if (size <= x) return size;
        int m = ((usable - 12) * 32 / 255) - 23;
        int k = m + ((size - m) % (usable - 4));
        return k <= x ? k : m;
    }

    private static long width(long type) {
        if (type >= 12) return (type - 12) / 2;
        return switch ((int) type) {
            case 1 -> 1;
            case 2 -> 2;
            case 3 -> 3;
            case 4 -> 4;
            case 5 -> 6;
            case 6, 7 -> 8;
            default -> 0;
        };
    }

    private static @Nullable Object value(byte[] b, int at, long type) {
        if (type == 0) return null;
        if (type == 8) return 0L;
        if (type == 9) return 1L;
        if (type == 7) return Double.longBitsToDouble(signed(b, at, 8));
        if (type >= 1 && type <= 6) return signed(b, at, (int) width(type));
        int n = (int) width(type);
        if (type >= 12 && type % 2 == 0) return Arrays.copyOfRange(b, at, at + n);
        if (type >= 13) return new String(b, at, n, StandardCharsets.UTF_8);
        return null;
    }

    private static long signed(byte[] b, int at, int n) {
        long v = b[at];
        for (int i = 1; i < n; i++) v = (v << 8) | (b[at + i] & 0xff);
        return v;
    }

    /** SQLite's big-endian varint at {@code at}: its value and the offset after it. */
    private static long[] varint(ByteBuffer b, int at) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            int c = b.get(at + i) & 0xff;
            v = (v << 7) | (c & 0x7f);
            if (c < 0x80) return new long[] {v, at + i + 1};
        }
        return new long[] {(v << 8) | (b.get(at + 8) & 0xff), at + 9};
    }

    /** Page {@code n} (1 is the first): its newest committed copy in the log, else the database's. */
    private ByteBuffer page(int n) throws IOException {
        if (n < 1) throw new IOException("page " + n);
        ByteBuffer page = ByteBuffer.allocate(pageSize);
        Long at = logged.get(n);
        FileChannel from = at != null && wal != null ? wal : db;
        long off = at != null && wal != null ? at : (long) (n - 1) * pageSize;
        while (page.hasRemaining()) {
            if (from.read(page, off + page.position()) < 0) throw new IOException("page " + n + ": past the end");
        }
        return page.clear();
    }

    @Override
    public void close() throws IOException {
        try {
            db.close();
        } finally {
            if (wal != null) wal.close();
        }
    }

    /** {@code file}'s size and its log's together, and the later of their modification times. */
    static long[] stamp(Path file) throws IOException {
        long size = Files.size(file);
        long modified = Files.getLastModifiedTime(file).toMillis();
        Path log = file.resolveSibling(file.getFileName() + "-wal");
        if (Files.isRegularFile(log)) {
            size += Files.size(log);
            modified = Math.max(modified, Files.getLastModifiedTime(log).toMillis());
        }
        return new long[] {size, modified};
    }
}
