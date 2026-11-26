// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * A POSIX tar writer for reproducible archives: every entry is owned by uid and gid 0 with no owner
 * names, carries the one modification time the writer was made with, and the mode its caller
 * gives. A name that does not fit the ustar {@code name}/{@code prefix} fields, or is not ASCII,
 * and a file of 8 GiB or more, get a pax extended header ({@code path=}, {@code size=}). The caller
 * decides the entry order.
 */
public final class DeterministicTar implements Closeable {

    private static final int BLOCK = 512;
    private static final long USTAR_MAX_SIZE = 077777777777L;

    private final OutputStream out;
    private final long mtime;
    private final byte[] zeros = new byte[BLOCK];
    private boolean closed;

    /** A writer over {@code out}, which {@link #close} closes, stamping entries at {@code epochSeconds}. */
    public DeterministicTar(OutputStream out, long epochSeconds) {
        this.out = out;
        this.mtime = epochSeconds;
    }

    /** A directory entry; {@code name} gets a trailing {@code /} when it lacks one. */
    public void directory(String name, int mode) throws IOException {
        header(name.endsWith("/") ? name : name + "/", mode, 0, '5');
    }

    /** A regular file holding {@code data}. */
    public void file(String name, int mode, byte[] data) throws IOException {
        header(name, mode, data.length, '0');
        out.write(data);
        pad(data.length);
    }

    /** A regular file holding {@code file}'s bytes, which must not change size while it is copied. */
    public void file(String name, int mode, Path file) throws IOException {
        long size = Files.size(file);
        header(name, mode, size, '0');
        long copied;
        try (InputStream in = Files.newInputStream(file)) {
            copied = in.transferTo(new BoundedOutput(out, size));
        }
        if (copied != size) throw new IOException(file + " changed size while it was archived");
        pad(size);
    }

    /** Write the end-of-archive marker (two zero blocks) and close the stream. */
    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        try (out) {
            out.write(zeros);
            out.write(zeros);
        }
    }

    private void header(String name, int mode, long size, char type) throws IOException {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        String[] split = ascii(name, nameBytes) ? split(name) : null;
        boolean bigSize = size > USTAR_MAX_SIZE;
        if (split == null || bigSize) {
            StringBuilder pax = new StringBuilder();
            if (split == null) pax.append(paxRecord("path", name));
            if (bigSize) pax.append(paxRecord("size", Long.toString(size)));
            byte[] body = pax.toString().getBytes(StandardCharsets.UTF_8);
            out.write(block(paxName(name), 0644, body.length, 'x', ""));
            out.write(body);
            pad(body.length);
            if (split == null) split = new String[] {"", fallbackName(name)};
        }
        out.write(block(split[1], mode, bigSize ? 0 : size, type, split[0]));
    }

    /** {@code [prefix, name]} for the ustar fields, or null when {@code name} fits neither way. */
    static String @Nullable [] split(String name) {
        if (name.length() <= 100) return new String[] {"", name};
        // The prefix ends at a slash, which the split drops; ustar readers put it back.
        int from = Math.max(0, name.length() - 101);
        for (int slash = name.indexOf('/', from); slash >= 0; slash = name.indexOf('/', slash + 1)) {
            if (slash > 155) return null;
            String rest = name.substring(slash + 1);
            if (!rest.isEmpty() && rest.length() <= 100) return new String[] {name.substring(0, slash), rest};
        }
        return null;
    }

    private static boolean ascii(String name, byte[] utf8) {
        return utf8.length == name.length();
    }

    /** The ustar name a reader without pax support sees: the last 100 bytes of an ASCII name, else a stand-in. */
    private static String fallbackName(String name) {
        String ascii = name.replaceAll("[^\\x20-\\x7e]", "_");
        return ascii.length() <= 100 ? ascii : ascii.substring(ascii.length() - 100);
    }

    private static String paxName(String name) {
        String base = fallbackName(name);
        int slash = base.lastIndexOf('/', base.length() - 2);
        String leaf = slash < 0 ? base : base.substring(slash + 1);
        String paxName = "PaxHeaders/" + leaf;
        return paxName.length() <= 100 ? paxName : paxName.substring(0, 100);
    }

    /** One pax record, {@code "<len> <key>=<value>\n"}, whose length counts its own digits. */
    static String paxRecord(String key, String value) {
        int body = (" " + key + "=" + value + "\n").getBytes(StandardCharsets.UTF_8).length;
        int len = body + Integer.toString(body).length();
        if (Integer.toString(len).length() != Integer.toString(body).length()) len++;
        return len + " " + key + "=" + value + "\n";
    }

    private byte[] block(String name, int mode, long size, char type, String prefix) {
        byte[] h = new byte[BLOCK];
        put(h, 0, 100, name);
        octal(h, 100, 8, mode);
        octal(h, 108, 8, 0);
        octal(h, 116, 8, 0);
        octal(h, 124, 12, size);
        octal(h, 136, 12, mtime);
        h[156] = (byte) type;
        put(h, 257, 6, "ustar");
        put(h, 263, 2, "00");
        octal(h, 329, 8, 0);
        octal(h, 337, 8, 0);
        put(h, 345, 155, prefix);
        for (int i = 148; i < 156; i++) h[i] = ' ';
        long sum = 0;
        for (byte b : h) sum += b & 0xff;
        octal(h, 148, 7, sum);
        h[155] = ' ';
        return h;
    }

    private static void put(byte[] h, int at, int len, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(bytes, 0, h, at, Math.min(len, bytes.length));
    }

    /** {@code value} as zero-padded octal in {@code len - 1} digits and a NUL. */
    private static void octal(byte[] h, int at, int len, long value) {
        String digits = Long.toOctalString(value);
        String padded = "0".repeat(Math.max(0, len - 1 - digits.length())) + digits;
        put(h, at, len - 1, padded);
        h[at + len - 1] = 0;
    }

    private void pad(long size) throws IOException {
        int rem = (int) (size % BLOCK);
        if (rem != 0) out.write(zeros, 0, BLOCK - rem);
    }

    /** Refuses bytes past the size the header promised, so a growing file cannot corrupt the archive. */
    private static final class BoundedOutput extends OutputStream {
        private final OutputStream out;
        private long left;

        BoundedOutput(OutputStream out, long limit) {
            this.out = out;
            this.left = limit;
        }

        @Override
        public void write(int b) throws IOException {
            if (left <= 0) throw new IOException("a file grew while it was archived");
            out.write(b);
            left--;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (len > left) throw new IOException("a file grew while it was archived");
            out.write(b, off, len);
            left -= len;
        }
    }
}
