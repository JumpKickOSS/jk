// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JarClassReaderTest {

    /** Names whose directory order and full-name order disagree, so the reader's order is the sort of full names. */
    private static final List<String> TRICKY = List.of(
            "b/B.class", "a/b/cd.class", "a/b/c/X.class", "a/A.class", "a/bc.class", "a/b/C.class", "Top.class");

    @Test
    void classes_come_in_name_order_whatever_the_jar_order_or_window(@TempDir Path dir) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (String name : TRICKY) entries.put(name, name.getBytes(StandardCharsets.UTF_8));
        entries.put("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
        entries.put("a/", new byte[0]);
        entries.put("a/notes.txt", "text".getBytes(StandardCharsets.UTF_8));
        Path jar = dir.resolve("mixed.jar");
        writeJar(jar, entries, true);

        Map<String, byte[]> reference = referenceOrder(jar);
        assertThat(reference.keySet()).containsExactly(TRICKY.stream().sorted().toArray(String[]::new));
        for (int window : new int[] {1, 2, 3, JarClassReader.WINDOW}) {
            Map<String, byte[]> read = readAll(jar, window);
            assertThat(read.keySet()).as("window " + window).containsExactlyElementsOf(reference.keySet());
            for (String name : reference.keySet()) {
                assertThat(read.get(name)).as(name).isEqualTo(reference.get(name));
            }
        }
    }

    @Test
    void many_more_classes_than_the_window_are_each_read_once_in_order(@TempDir Path dir) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int i = 999; i >= 0; i--) {
            String name = "p" + (i % 7) + "/q" + (i % 13) + "/C" + i + ".class";
            entries.put(name, ("class " + i).getBytes(StandardCharsets.UTF_8));
        }
        Path jar = dir.resolve("many.jar");
        writeJar(jar, entries, false);

        Map<String, byte[]> read = readAll(jar, 16);
        assertThat(read).hasSize(1000);
        List<String> names = new ArrayList<>(read.keySet());
        assertThat(names).isSorted();
        assertThat(names).containsExactlyElementsOf(referenceOrder(jar).keySet());
        assertThat(read.get("p3/q3/C3.class")).isEqualTo("class 3".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void a_zip64_directory_is_read(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("big.jar");
        int total = 70_000;
        byte[] payload = {1};
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            out.setLevel(0);
            for (int i = 0; i < total; i++) {
                out.putNextEntry(new ZipEntry("z/E" + i + ".class"));
                out.write(payload);
                out.closeEntry();
            }
        }
        int[] count = {0};
        String[] first = {null};
        String[] last = {null};
        try (JarClassReader reader = JarClassReader.open(jar)) {
            reader.forEachClass((name, bytes) -> {
                if (first[0] == null) first[0] = name;
                last[0] = name;
                count[0]++;
                assertThat(bytes).isEqualTo(payload);
            });
        }
        assertThat(count[0]).isEqualTo(total);
        assertThat(first[0]).isEqualTo("z/E0.class");
        assertThat(last[0]).isEqualTo("z/E9999.class");
    }

    @Test
    void bytes_prepended_to_the_archive_are_skipped(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("plain.jar");
        writeJar(jar, Map.of("C.class", new byte[] {7, 7, 7}), false);
        Path shifted = dir.resolve("shifted.jar");
        try (OutputStream out = Files.newOutputStream(shifted)) {
            out.write(new byte[123]);
            out.write(Files.readAllBytes(jar));
        }
        assertThat(readAll(shifted, JarClassReader.WINDOW)).containsOnlyKeys("C.class");
        assertThat(readAll(shifted, JarClassReader.WINDOW).get("C.class")).isEqualTo(new byte[] {7, 7, 7});
    }

    @Test
    void a_file_that_is_not_a_zip_is_a_ZipException(@TempDir Path dir) throws Exception {
        Path notZip = dir.resolve("C.class");
        Files.write(notZip, new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 0});
        assertThatThrownBy(() -> JarClassReader.open(notZip)).isInstanceOf(ZipException.class);
    }

    private static Map<String, byte[]> readAll(Path jar, int window) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (JarClassReader reader = JarClassReader.open(jar, window)) {
            reader.forEachClass((name, bytes) -> {
                assertThat(out).as("each class once: " + name).doesNotContainKey(name);
                out.put(name, bytes);
            });
        }
        return out;
    }

    /** What the JDK's reader lists, sorted by name: the order the ABI token was defined over. */
    private static Map<String, byte[]> referenceOrder(Path jar) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            List<? extends ZipEntry> classes = zip.stream()
                    .filter(e -> !e.isDirectory() && e.getName().endsWith(".class"))
                    .sorted(Comparator.comparing(ZipEntry::getName))
                    .toList();
            for (ZipEntry e : classes) {
                try (var in = zip.getInputStream(e)) {
                    out.put(e.getName(), in.readAllBytes());
                }
            }
        }
        return out;
    }

    /** Writes {@code entries} in iteration order; every other entry stored rather than deflated when {@code mixStored}. */
    private static void writeJar(Path jar, Map<String, byte[]> entries, boolean mixStored) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            int i = 0;
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                ZipEntry entry = new ZipEntry(e.getKey());
                if (mixStored && i++ % 2 == 0 && !e.getKey().endsWith("/")) {
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(e.getValue().length);
                    entry.setCompressedSize(e.getValue().length);
                    CRC32 crc = new CRC32();
                    crc.update(e.getValue());
                    entry.setCrc(crc.getValue());
                }
                out.putNextEntry(entry);
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        assertThat(Arrays.equals(Files.readAllBytes(jar), new byte[0])).isFalse();
    }
}
