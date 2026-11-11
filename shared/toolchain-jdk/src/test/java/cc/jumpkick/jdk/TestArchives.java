// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** In-memory JDK-shaped archives for installer tests, built without any external tool. */
final class TestArchives {

    private TestArchives() {}

    /** A tar.gz with every entry under {@code topLevelDir/}; values are file bodies. */
    static byte[] tarGz(String topLevelDir, Map<String, String> entries) throws IOException {
        String[][] raw = new String[entries.size() + 1][];
        raw[0] = new String[] {topLevelDir + "/", null};
        int i = 1;
        for (var e : entries.entrySet()) {
            raw[i++] = new String[] {topLevelDir + "/" + e.getKey(), e.getValue()};
        }
        return tarGzRaw(raw);
    }

    /**
     * Build a tar.gz using hand-rolled 512-byte TAR blocks — no external library. {@code null} body =
     * directory entry; a third element makes the entry a symlink to that target.
     */
    static byte[] tarGzRaw(String[][] rawEntries) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        for (String[] e : rawEntries) {
            String name = e[0];
            String linkTarget = e.length > 2 ? e[2] : null;
            byte[] data = e[1] != null ? e[1].getBytes(StandardCharsets.UTF_8) : null;
            boolean isLink = linkTarget != null;
            boolean isDir = data == null && !isLink;
            byte[] header = new byte[512];
            // name (0-99)
            byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(nameBytes, 0, header, 0, Math.min(nameBytes.length, 99));
            // mode (100-107)
            putOctal(header, 100, 8, isDir ? 0755 : 0644);
            // uid/gid (108-115, 116-123)
            putOctal(header, 108, 8, 0);
            putOctal(header, 116, 8, 0);
            // size (124-135)
            putOctal(header, 124, 12, data != null ? data.length : 0);
            // mtime (136-147)
            putOctal(header, 136, 12, 0);
            // type (156)
            header[156] = (byte) (isLink ? '2' : isDir ? '5' : '0');
            // link target (157-256)
            if (linkTarget != null) {
                byte[] linkBytes = linkTarget.getBytes(StandardCharsets.US_ASCII);
                System.arraycopy(linkBytes, 0, header, 157, Math.min(linkBytes.length, 99));
            }
            // ustar magic (257-262)
            System.arraycopy("ustar ".getBytes(StandardCharsets.US_ASCII), 0, header, 257, 6);
            header[263] = ' ';
            header[264] = 0;
            // checksum (148-155): fill with spaces first, then compute
            Arrays.fill(header, 148, 156, (byte) ' ');
            int sum = 0;
            for (byte b : header) sum += (b & 0xFF);
            putOctal(header, 148, 8, sum);
            raw.write(header);
            if (data != null && data.length > 0) {
                raw.write(data);
                int pad = 512 - (data.length % 512);
                if (pad < 512) raw.write(new byte[pad]);
            }
        }
        raw.write(new byte[1024]); // two zero blocks = end of archive
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
            gz.write(raw.toByteArray());
        }
        return bytes.toByteArray();
    }

    /** A zip with the entries named verbatim; a {@code null} body is a directory entry. */
    static byte[] zip(String[][] entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (String[] e : entries) {
                zip.putNextEntry(new ZipEntry(e[0]));
                if (e[1] != null) zip.write(e[1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static void putOctal(byte[] buf, int off, int len, long value) {
        String octal = String.format("%0" + (len - 1) + "o", value);
        byte[] oBytes = octal.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(oBytes, 0, buf, off, Math.min(oBytes.length, len - 1));
        buf[off + len - 1] = 0;
    }
}
