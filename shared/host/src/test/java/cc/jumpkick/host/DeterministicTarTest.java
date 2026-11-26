// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The tar writer behind the distribution archives: ustar headers, pax for what ustar cannot hold. */
class DeterministicTarTest {

    private static final long TIME = 946_684_800L;

    @TempDir
    Path tmp;

    @Test
    void entries_keep_their_order_mode_time_and_no_owner() throws IOException {
        Path file = Files.writeString(tmp.resolve("start"), "#!/bin/sh\n");
        byte[] tar = write(t -> {
            t.directory("app", 0755);
            t.file("app/bin/start", 0755, file);
            t.file("app/release", 0644, "JAVA_VERSION=25\n".getBytes(UTF_8));
        });

        List<Header> entries = read(tar);
        assertThat(entries).extracting(Header::name).containsExactly("app/", "app/bin/start", "app/release");
        assertThat(entries).extracting(Header::type).containsExactly('5', '0', '0');
        assertThat(entries).extracting(Header::mode).containsExactly(0755, 0755, 0644);
        assertThat(entries).extracting(Header::mtime).containsOnly(TIME);
        assertThat(entries).extracting(Header::uid).containsOnly(0L);
        assertThat(entries).extracting(Header::owner).containsOnly("");
        assertThat(new String(entries.get(1).body(), UTF_8)).isEqualTo("#!/bin/sh\n");
        assertThat(tar.length % 512).isZero();
    }

    @Test
    void a_long_name_splits_at_a_slash_into_the_ustar_prefix() throws IOException {
        String name = "top/" + "d".repeat(80) + "/" + "f".repeat(90);
        byte[] tar = write(t -> t.file(name, 0644, new byte[] {1}));

        List<Header> entries = read(tar);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).name()).isEqualTo(name);
        assertThat(entries.get(0).type()).isEqualTo('0');
    }

    @Test
    void a_name_ustar_cannot_hold_gets_a_pax_path() throws IOException {
        String longLeaf = "top/" + "x".repeat(150);
        String unicode = "top/café.txt";
        byte[] tar = write(t -> {
            t.file(longLeaf, 0644, new byte[] {1});
            t.file(unicode, 0644, new byte[] {2});
        });

        List<Header> entries = read(tar);
        assertThat(entries).extracting(Header::name).containsExactly(longLeaf, unicode);
        assertThat(entries).extracting(h -> h.body()[0]).containsExactly((byte) 1, (byte) 2);
    }

    @Test
    void the_same_entries_give_the_same_bytes() throws IOException {
        Path file = Files.writeString(tmp.resolve("a"), "a");
        TarBody body = t -> {
            t.directory("top/", 0755);
            t.file("top/" + "n".repeat(120), 0644, file);
        };
        assertThat(write(body)).isEqualTo(write(body));
    }

    @Test
    void a_pax_record_counts_its_own_length() {
        String record = DeterministicTar.paxRecord("path", "a".repeat(93));
        assertThat(record).startsWith("103 path=");
        assertThat(record.getBytes(UTF_8)).hasSize(103);
        assertThat(DeterministicTar.paxRecord("path", "ab")).isEqualTo("11 path=ab\n");
    }

    @Test
    void the_split_refuses_a_leaf_over_a_hundred_bytes() {
        assertThat(DeterministicTar.split("short")).containsExactly("", "short");
        assertThat(DeterministicTar.split("p/" + "l".repeat(101))).isNull();
    }

    @FunctionalInterface
    private interface TarBody {
        void accept(DeterministicTar tar) throws IOException;
    }

    private static byte[] write(TarBody body) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeterministicTar tar = new DeterministicTar(out, TIME)) {
            body.accept(tar);
        }
        return out.toByteArray();
    }

    private record Header(String name, char type, int mode, long mtime, long uid, String owner, byte[] body) {}

    /** A reader for exactly what the writer emits: ustar headers, pax {@code path} records, checksums verified. */
    private static List<Header> read(byte[] tar) {
        List<Header> out = new ArrayList<>();
        String paxPath = null;
        int pos = 0;
        while (pos + 512 <= tar.length) {
            if (allZero(tar, pos)) break;
            assertThat(field(tar, pos + 257, 5)).isEqualTo("ustar");
            long sum = 0;
            for (int i = 0; i < 512; i++) sum += (i >= 148 && i < 156) ? ' ' : tar[pos + i] & 0xff;
            assertThat(octal(tar, pos + 148, 8)).as("checksum").isEqualTo(sum);
            String name = field(tar, pos, 100);
            String prefix = field(tar, pos + 345, 155);
            if (!prefix.isEmpty()) name = prefix + "/" + name;
            char type = (char) tar[pos + 156];
            int size = (int) octal(tar, pos + 124, 12);
            byte[] body = new byte[size];
            System.arraycopy(tar, pos + 512, body, 0, size);
            int h = pos;
            pos += 512 + ((size + 511) / 512) * 512;
            if (type == 'x') {
                String rec = new String(body, UTF_8);
                int at = rec.indexOf(" path=");
                paxPath = rec.substring(at + " path=".length(), rec.indexOf('\n', at));
                continue;
            }
            if (paxPath != null) {
                name = paxPath;
                paxPath = null;
            }
            out.add(new Header(
                    name,
                    type,
                    (int) octal(tar, h + 100, 8),
                    octal(tar, h + 136, 12),
                    octal(tar, h + 108, 8),
                    field(tar, h + 265, 32),
                    body));
        }
        return out;
    }

    private static boolean allZero(byte[] tar, int pos) {
        for (int i = 0; i < 512; i++) if (tar[pos + i] != 0) return false;
        return true;
    }

    private static String field(byte[] tar, int at, int len) {
        int end = at;
        while (end < at + len && tar[end] != 0) end++;
        return new String(tar, at, end - at, UTF_8);
    }

    private static long octal(byte[] tar, int at, int len) {
        String s = field(tar, at, len).trim();
        return s.isEmpty() ? 0 : Long.parseLong(s, 8);
    }
}
