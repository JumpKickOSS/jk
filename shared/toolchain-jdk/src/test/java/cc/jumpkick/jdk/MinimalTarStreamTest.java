// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The tar reader's header walk: every entry kind lands on the handler and nothing desynchronises the next header. */
class MinimalTarStreamTest {

    @Test
    void a_hard_link_entry_names_the_entry_it_links(@TempDir Path tmp) throws Exception {
        byte[] tar = tar(file("jdk/lib/libjli.so", "bytes"), hardLink("jdk/bin/libjli.so", "jdk/lib/libjli.so"));
        List<String> seen = new ArrayList<>();

        MinimalTar.stream(new ByteArrayInputStream(tar), (name, linkName, mode, kind, data, size) -> {
            seen.add(kind + " " + name + (linkName.isEmpty() ? "" : " -> " + linkName));
            assertThat(data == null).isEqualTo(kind != MinimalTar.Kind.FILE);
        });

        assertThat(seen).containsExactly("FILE jdk/lib/libjli.so", "HARD_LINK jdk/bin/libjli.so -> jdk/lib/libjli.so");
    }

    @Test
    void a_hard_link_extracts_with_the_bytes_of_its_target(@TempDir Path tmp) throws Exception {
        Path archive = Files.write(
                tmp.resolve("jdk.tar"),
                tar(
                        dir("jdk/"),
                        dir("jdk/lib/"),
                        dir("jdk/bin/"),
                        file("jdk/lib/libjli.so", "the launcher library"),
                        hardLink("jdk/bin/libjli.so", "jdk/lib/libjli.so")));
        Path dest = Files.createDirectories(tmp.resolve("dest"));

        JdkInstaller.extract(archive, dest, "tar");

        assertThat(dest.resolve("jdk/bin/libjli.so")).isRegularFile().hasContent("the launcher library");
    }

    @Test
    void a_pax_record_over_the_cap_does_not_desynchronise_the_next_header() throws Exception {
        // One record of a little over 1 MiB, then an ordinary file: the file must still be read whole.
        String filler = "x".repeat((1 << 20) + 100);
        String record = "comment=" + filler + "\n";
        String pax = (record.length() + 8) + " " + record;
        byte[] tar = tar(
                entry('x', "./PaxHeaders/hello", "", pax.getBytes(StandardCharsets.UTF_8)), file("hello.txt", "hello"));
        List<String> seen = new ArrayList<>();

        MinimalTar.stream(new ByteArrayInputStream(tar), (name, linkName, mode, kind, data, size) -> {
            seen.add(kind + " " + name + " "
                    + new String(Objects.requireNonNull(data).readAllBytes(), StandardCharsets.UTF_8));
        });

        assertThat(seen).containsExactly("FILE hello.txt hello");
    }

    // ---- a hand-rolled ustar writer ---------------------------------------

    private static byte[] file(String name, String body) {
        return entry('0', name, "", body.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] dir(String name) {
        return entry('5', name, "", new byte[0]);
    }

    private static byte[] hardLink(String name, String target) {
        return entry('1', name, target, new byte[0]);
    }

    private static byte[] entry(char type, String name, String linkName, byte[] data) {
        byte[] header = new byte[512];
        put(header, 0, 100, name);
        putOctal(header, 100, 8, type == '5' ? 0755 : 0644);
        putOctal(header, 108, 8, 0);
        putOctal(header, 116, 8, 0);
        putOctal(header, 124, 12, data.length);
        putOctal(header, 136, 12, 0);
        header[156] = (byte) type;
        put(header, 157, 100, linkName);
        put(header, 257, 6, "ustar ");
        header[263] = ' ';
        Arrays.fill(header, 148, 156, (byte) ' ');
        int sum = 0;
        for (byte b : header) sum += b & 0xFF;
        putOctal(header, 148, 8, sum);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(header);
        out.writeBytes(data);
        int pad = (512 - data.length % 512) % 512;
        out.writeBytes(new byte[pad]);
        return out.toByteArray();
    }

    private static byte[] tar(byte[]... entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] e : entries) out.write(e);
        out.write(new byte[1024]);
        return out.toByteArray();
    }

    private static void put(byte[] buf, int off, int len, String text) {
        byte[] b = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, buf, off, Math.min(b.length, len - 1));
    }

    private static void putOctal(byte[] buf, int off, int len, long value) {
        put(buf, off, len, String.format("%0" + (len - 1) + "o", value));
    }
}
