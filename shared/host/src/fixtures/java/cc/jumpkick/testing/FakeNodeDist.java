// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.jspecify.annotations.Nullable;

/**
 * A Node.js release archive in the shape nodejs.org publishes for a platform: a {@code zip} with
 * {@code node.exe}, {@code npm.cmd}, {@code npx.cmd} and {@code node_modules/npm} at its root for
 * {@code win-*}, else a {@code tar.gz} with {@code bin/} and {@code lib/node_modules/npm}. Its
 * {@code node} and {@code npx} are {@link FakePrograms} fakes; by default {@code node} prints its
 * version and {@code npx} prints {@link #NPM}.
 */
public final class FakeNodeDist {

    /** The npm version the default {@code npx} prints. */
    public static final String NPM = "11.6.0";

    private FakeNodeDist() {}

    /** The archive of {@code version} for {@code platformKey} ({@code linux-x64}, {@code win-x64}, …). */
    public static byte[] archive(String version, String platformKey) {
        return archive(
                version, platformKey, FakePrograms.Script.printing("v" + version), FakePrograms.Script.printing(NPM));
    }

    /** As {@link #archive(String, String)}, with {@code node} and {@code npx} running these scripts. */
    public static byte[] archive(
            String version, String platformKey, FakePrograms.Script node, FakePrograms.Script npx) {
        String top = "node-v" + version + "-" + platformKey + "/";
        try {
            if (platformKey.startsWith("win-")) {
                return zip(List.of(
                        Entry.dir(top),
                        new Entry(top + "node.exe", FakePrograms.windowsShim()),
                        new Entry(top + FakePrograms.shimScript("node.exe"), FakePrograms.batch(node), false),
                        new Entry(top + "npx.cmd", FakePrograms.batch(npx), false),
                        new Entry(top + "npm.cmd", FakePrograms.batch(FakePrograms.Script.printing(NPM)), false),
                        new Entry(top + "node_modules/npm/bin/npm-cli.js", "// npm\n", false),
                        new Entry(top + "node_modules/npm/bin/npx-cli.js", "// npx\n", false)));
            }
            return tarGz(List.of(
                    Entry.dir(top),
                    Entry.dir(top + "bin/"),
                    new Entry(top + "bin/node", "#!/bin/sh\n" + node.sh() + "\n", true),
                    new Entry(top + "bin/npx", "#!/bin/sh\n" + npx.sh() + "\n", true),
                    new Entry(top + "lib/node_modules/npm/bin/npm-cli.js", "// npm\n", false),
                    new Entry(top + "lib/node_modules/npm/bin/npx-cli.js", "// npx\n", false),
                    Entry.link(top + "bin/npm", "../lib/node_modules/npm/bin/npm-cli.js")));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A file ({@code body}), a directory (no body, no link) or a symbolic link to {@code link}. */
    private record Entry(
            String name,
            byte @Nullable [] body,
            boolean executable,
            @Nullable String link) {

        Entry(String name, String text, boolean executable) {
            this(name, text.getBytes(StandardCharsets.UTF_8), executable, null);
        }

        Entry(String name, byte[] bytes) {
            this(name, bytes, false, null);
        }

        static Entry dir(String name) {
            return new Entry(name, null, false, null);
        }

        static Entry link(String name, String target) {
            return new Entry(name, null, false, target);
        }
    }

    private static byte[] zip(List<Entry> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Entry e : entries) {
                zip.putNextEntry(new ZipEntry(e.name()));
                byte[] body = e.body();
                if (body != null) zip.write(body);
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static byte[] tarGz(List<Entry> entries) throws IOException {
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        for (Entry e : entries) {
            byte[] body = e.body();
            String link = e.link();
            boolean dir = body == null && link == null;
            byte[] data = body == null ? new byte[0] : body;
            byte[] h = new byte[512];
            byte[] name = e.name().getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(name, 0, h, 0, Math.min(name.length, 99));
            octal(h, 100, 8, dir || e.executable() ? 0755 : 0644);
            octal(h, 108, 8, 0);
            octal(h, 116, 8, 0);
            octal(h, 124, 12, data.length);
            octal(h, 136, 12, 0);
            h[156] = (byte) (link != null ? '2' : dir ? '5' : '0');
            if (link != null) {
                byte[] l = link.getBytes(StandardCharsets.US_ASCII);
                System.arraycopy(l, 0, h, 157, Math.min(l.length, 99));
            }
            System.arraycopy("ustar ".getBytes(StandardCharsets.US_ASCII), 0, h, 257, 6);
            h[263] = ' ';
            Arrays.fill(h, 148, 156, (byte) ' ');
            int sum = 0;
            for (byte b : h) sum += b & 0xFF;
            octal(h, 148, 8, sum);
            tar.write(h);
            tar.write(data);
            if (data.length % 512 != 0) tar.write(new byte[512 - data.length % 512]);
        }
        tar.write(new byte[1024]);
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gz)) {
            out.write(tar.toByteArray());
        }
        return gz.toByteArray();
    }

    private static void octal(byte[] buf, int off, int len, long value) {
        String s = Long.toOctalString(value);
        byte[] b = ("0".repeat(Math.max(0, len - 1 - s.length())) + s).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, buf, off, Math.min(b.length, len - 1));
    }
}
