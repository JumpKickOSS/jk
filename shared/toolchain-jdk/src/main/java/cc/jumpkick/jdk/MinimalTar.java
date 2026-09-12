// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Minimal tar/tar.gz reader for JDK installs: regular files, hard/symlinks, directories, GNU long
 * name/link, and PAX path/linkpath. Other entry types are skipped.
 */
public final class MinimalTar {

    private MinimalTar() {}

    @FunctionalInterface
    public interface EntryHandler {
        /**
         * Called for each entry.
         *
         * @param name entry path (may contain subdirs)
         * @param linkName symlink target, or empty string for non-symlinks
         * @param mode POSIX permission bits (e.g. {@code 0755})
         * @param isDir true when the entry is a directory
         * @param isLink true when the entry is a symbolic link
         * @param data stream positioned at entry data ({@code size} bytes available); {@code null} for
         *     directories and symlinks
         * @param size uncompressed byte count of the entry data
         */
        void handle(
                String name,
                String linkName,
                int mode,
                boolean isDir,
                boolean isLink,
                @Nullable InputStream data,
                long size)
                throws IOException;
    }

    /** Stream through a raw (uncompressed) TAR archive, invoking {@code handler} for each entry. */
    public static void stream(InputStream tar, EntryHandler handler) throws IOException {
        byte[] header = new byte[512];
        String pendingName = null; // GNU 'L' or PAX 'path'
        String pendingLink = null; // GNU 'K' or PAX 'linkpath'

        while (true) {
            int read = readFully(tar, header, 0, 512);
            if (read < 512) break;

            // Two consecutive all-zero blocks = end of archive.
            if (isZeroBlock(header)) {
                readFully(tar, header, 0, 512); // consume second zero block
                break;
            }

            String name = nullTermStr(header, 0, 100);
            int mode = octalInt(header, 100, 8);
            long size = octalLong(header, 124, 12);
            char typeFlag = (char) (header[156] & 0xFF);
            String linkName = nullTermStr(header, 157, 100);

            // USTAR/POSIX prefix (bytes 345-499) extends the name.
            String prefix = nullTermStr(header, 345, 155);
            if (!prefix.isEmpty() && typeFlag != 'L' && typeFlag != 'K' && typeFlag != 'x' && typeFlag != 'g') {
                name = prefix + "/" + name;
            }

            // Apply any pending long name from a previous 'L'/'K'/PAX entry.
            if (pendingName != null) {
                name = pendingName;
                pendingName = null;
            }
            if (pendingLink != null) {
                linkName = pendingLink;
                pendingLink = null;
            }

            long dataBlocks = (size + 511) / 512;

            switch (typeFlag) {
                case 'L' -> {
                    // GNU long-name: data block contains the real file name.
                    pendingName = readString(tar, size);
                    skipPadding(tar, dataBlocks, size);
                }
                case 'K' -> {
                    // GNU long-link: data block contains the real link name.
                    pendingLink = readString(tar, size);
                    skipPadding(tar, dataBlocks, size);
                }
                case 'x', 'g' -> {
                    // PAX extended header: parse "size key=value\n" records.
                    String pax = readString(tar, size);
                    String[] lines = pax.split("\n");
                    for (String line : lines) {
                        int spaceIdx = line.indexOf(' ');
                        if (spaceIdx < 0) continue;
                        String kv = line.substring(spaceIdx + 1);
                        int eqIdx = kv.indexOf('=');
                        if (eqIdx < 0) continue;
                        String k = kv.substring(0, eqIdx);
                        String v = kv.substring(eqIdx + 1);
                        if ("path".equals(k)) pendingName = v;
                        if ("linkpath".equals(k)) pendingLink = v;
                    }
                    skipPadding(tar, dataBlocks, size);
                }
                case '5' -> {
                    // Directory.
                    handler.handle(name, linkName, mode, true, false, null, 0);
                    // Directories typically have zero data blocks.
                    skipBlocks(tar, dataBlocks);
                }
                case '2' -> {
                    // Symbolic link.
                    handler.handle(name, linkName, mode, false, true, null, 0);
                    skipBlocks(tar, dataBlocks);
                }
                case '0', '\0', '1' -> {
                    // Regular file or hard link — stream data bytes to handler.
                    LimitedInputStream limited = new LimitedInputStream(tar, size);
                    handler.handle(name, linkName, mode, false, false, limited, size);
                    // Drain any unread bytes + padding.
                    limited.skipRemaining();
                    skipPadding(tar, dataBlocks, size);
                }
                default -> {
                    // Unknown type — skip.
                    skipBlocks(tar, dataBlocks);
                }
            }
        }
    }

    // ---- helpers ----------------------------------------------------------

    /**
     * Create the symlink {@code out -> linkName}, refusing targets that leave {@code destDir}.
     *
     * <p>An archive is untrusted input, and the usual entry-name check is purely lexical — it does
     * not stop an entry named {@code lib} that is a symlink to {@code /etc}, followed by an entry
     * named {@code lib/passwd} whose write then lands outside the destination. Nor does it stop a
     * chain of in-tree links: {@code d/l -> ..} resolves inside, but a second link created
     * <em>through</em> it, {@code d/l/l2 -> ..}, physically lands at {@code <dest>/l2} and points
     * one level above the destination. The target is therefore resolved against the parent's
     * <em>real</em> path, following whatever links the archive has already planted, and the link
     * itself is created at that real location. Real JDK/tool archives only ever link within the
     * extracted tree ({@code jre/lib -> ../lib}), so every legitimate archive keeps working.
     */
    public static void createSymlinkInside(Path destDir, Path out, String linkName) throws IOException {
        if (linkName == null || linkName.isBlank()) {
            throw new IOException("tar symlink entry has no target: " + out.getFileName());
        }
        Path target = Path.of(linkName);
        if (target.isAbsolute()) {
            throw new IOException("tar symlink target is absolute: " + linkName);
        }
        Path parent = out.getParent() == null ? destDir : out.getParent();
        Path realParent = createDirectoryInside(destDir, parent);
        Path resolved = resolveFollowingLinks(realParent, target, linkName);
        if (!resolved.startsWith(destDir.toRealPath())) {
            throw new IOException("tar symlink escapes destination: " + out.getFileName() + " -> " + linkName);
        }
        Path link = realParent.resolve(out.getFileName());
        Files.deleteIfExists(link);
        Files.createSymbolicLink(link, target);
    }

    /**
     * Where {@code target}, read relative to {@code start} (a real path), lands: {@code ..} steps
     * up the real path and any existing link met on the way is followed, so a target that routes
     * through a link the archive planted earlier is judged by where it actually goes. Components
     * that do not exist yet stay lexical.
     */
    private static Path resolveFollowingLinks(Path start, Path target, String linkName) throws IOException {
        Path current = start;
        for (Path component : target) {
            String c = component.toString();
            if (c.isEmpty() || c.equals(".")) continue;
            if (c.equals("..")) {
                Path up = current.getParent();
                current = up == null ? current : up;
                continue;
            }
            current = current.resolve(c);
            if (Files.isSymbolicLink(current)) {
                try {
                    current = current.toRealPath();
                } catch (IOException dangling) {
                    throw new IOException("tar symlink target crosses a dangling link: " + linkName, dangling);
                }
            }
        }
        return current;
    }

    /**
     * Create {@code dir} and any missing parents, refusing when the path resolves — through links
     * the archive planted earlier — outside {@code destDir}. Returns {@code dir}'s real path.
     *
     * <p>The check runs on the nearest ancestor that already exists, <em>before</em> anything is
     * created: creating first and comparing real paths afterwards leaves the escaped directory in
     * place, and every entry beneath it is then written outside the tree.
     */
    public static Path createDirectoryInside(Path destDir, Path dir) throws IOException {
        Path destReal = destDir.toRealPath();
        Path existing = dir;
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            Path up = existing.getParent();
            if (up == null) throw new IOException("tar entry has no existing ancestor: " + dir);
            existing = up;
        }
        Path existingReal;
        try {
            existingReal = existing.toRealPath();
        } catch (IOException dangling) {
            throw new IOException("tar entry resolves through a dangling link: " + describe(destDir, dir), dangling);
        }
        if (!existingReal.startsWith(destReal)) {
            throw new IOException(
                    "tar entry resolves through a link outside the destination: " + describe(destDir, dir));
        }
        if (!Files.isDirectory(existingReal)) {
            throw new IOException("tar entry resolves through a file: " + describe(destDir, dir));
        }
        Files.createDirectories(dir);
        Path real = dir.toRealPath();
        if (!real.startsWith(destReal)) {
            throw new IOException(
                    "tar entry resolves through a link outside the destination: " + describe(destDir, dir));
        }
        return real;
    }

    private static String describe(Path destDir, Path path) {
        return path.startsWith(destDir) ? destDir.relativize(path).toString() : path.toString();
    }

    /**
     * Fail when {@code out}'s parent directories resolve (through symlinks) outside {@code
     * destDir} — the second half of the tar-symlink escape: a link entry planted earlier in the
     * same archive must not become a write path out of the tree. Creates the parent when the
     * check passes.
     */
    public static void requireParentInside(Path destDir, Path out) throws IOException {
        Path parent = out.getParent();
        if (parent == null) return;
        createDirectoryInside(destDir, parent);
    }

    public static void applyMode(Path file, int mode) {
        try {
            Set<PosixFilePermission> perms = EnumSet.noneOf(PosixFilePermission.class);
            if ((mode & 0400) != 0) perms.add(PosixFilePermission.OWNER_READ);
            if ((mode & 0200) != 0) perms.add(PosixFilePermission.OWNER_WRITE);
            if ((mode & 0100) != 0) perms.add(PosixFilePermission.OWNER_EXECUTE);
            if ((mode & 0040) != 0) perms.add(PosixFilePermission.GROUP_READ);
            if ((mode & 0020) != 0) perms.add(PosixFilePermission.GROUP_WRITE);
            if ((mode & 0010) != 0) perms.add(PosixFilePermission.GROUP_EXECUTE);
            if ((mode & 0004) != 0) perms.add(PosixFilePermission.OTHERS_READ);
            if ((mode & 0002) != 0) perms.add(PosixFilePermission.OTHERS_WRITE);
            if ((mode & 0001) != 0) perms.add(PosixFilePermission.OTHERS_EXECUTE);
            if (!perms.isEmpty()) Files.setPosixFilePermissions(file, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX filesystem — skip silently.
        }
    }

    private static String readString(InputStream in, long size) throws IOException {
        byte[] buf = new byte[(int) Math.min(size, 1 << 20)]; // 1 MB cap
        readFully(in, buf, 0, buf.length);
        return new String(buf, StandardCharsets.UTF_8).trim();
    }

    private static void skipPadding(InputStream in, long blocks, long size) throws IOException {
        long padding = blocks * 512 - size;
        if (padding > 0) in.skipNBytes(padding);
    }

    private static void skipBlocks(InputStream in, long blocks) throws IOException {
        in.skipNBytes(blocks * 512);
    }

    private static String nullTermStr(byte[] buf, int off, int len) {
        int end = off;
        while (end < off + len && buf[end] != 0) end++;
        return new String(buf, off, end - off, StandardCharsets.ISO_8859_1);
    }

    private static int octalInt(byte[] buf, int off, int len) {
        try {
            return Integer.parseInt(nullTermStr(buf, off, len).strip(), 8);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long octalLong(byte[] buf, int off, int len) {
        // GNU extension: if high bit of first byte is set, the field is binary.
        if ((buf[off] & 0x80) != 0) {
            long v = 0;
            for (int i = off + 3; i < off + len; i++) v = (v << 8) | (buf[i] & 0xFF);
            return v;
        }
        try {
            return Long.parseLong(nullTermStr(buf, off, len).strip(), 8);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean isZeroBlock(byte[] buf) {
        for (byte b : buf) if (b != 0) return false;
        return true;
    }

    private static int readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int n = in.read(buf, off + total, len - total);
            if (n < 0) break;
            total += n;
        }
        return total;
    }

    /**
     * Wraps an {@link InputStream} and limits reads to exactly {@code size} bytes — prevents the
     * handler from reading beyond the current TAR entry.
     */
    static final class LimitedInputStream extends InputStream {
        private final InputStream delegate;
        private long remaining;

        LimitedInputStream(InputStream delegate, long size) {
            this.delegate = delegate;
            this.remaining = size;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) return -1;
            int b = delegate.read();
            if (b >= 0) remaining--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            int n = delegate.read(b, off, (int) Math.min(len, remaining));
            if (n > 0) remaining -= n;
            return n;
        }

        void skipRemaining() throws IOException {
            if (remaining > 0) {
                delegate.skipNBytes(remaining);
                remaining = 0;
            }
        }

        @Override
        public void close() {
            /* don't close the underlying stream */
        }
    }
}
