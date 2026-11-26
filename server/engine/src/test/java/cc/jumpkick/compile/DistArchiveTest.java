// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.host.DeterministicZip;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A layout written as one reproducible archive under a single top-level directory. */
class DistArchiveTest {

    private static final long TIME = 946_684_800L;

    @TempDir
    Path tmp;

    @Test
    void a_tar_gz_holds_one_top_directory_in_name_order_with_modes_and_one_time() throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path image = image();
        Path archive = tmp.resolve("app-1.0-linux-x86_64.tar.gz");

        DistArchive.write(archive, "app-1.0", DistArchive.tree(image, ""), TIME);

        List<String[]> entries = tarEntries(archive);
        assertThat(entries)
                .extracting(e -> e[0])
                .containsExactly(
                        "app-1.0/",
                        "app-1.0/app/",
                        "app-1.0/app/app.jar",
                        "app-1.0/bin/",
                        "app-1.0/bin/java",
                        "app-1.0/bin/start",
                        "app-1.0/lib/",
                        "app-1.0/lib/jspawnhelper",
                        "app-1.0/lib/modules",
                        "app-1.0/release");
        assertThat(entries)
                .extracting(e -> e[0] + " " + e[1])
                .contains(
                        "app-1.0/ 755",
                        "app-1.0/bin/start 755",
                        "app-1.0/bin/java 755",
                        "app-1.0/lib/jspawnhelper 755",
                        "app-1.0/lib/modules 644",
                        "app-1.0/release 644");
        assertThat(entries).extracting(e -> e[2]).containsOnly(Long.toString(TIME));
    }

    @Test
    void the_same_layout_gives_the_same_bytes() throws IOException {
        Path image = image();
        Path first = tmp.resolve("one/app.tar.gz");
        Path second = tmp.resolve("two/app.tar.gz");
        DistArchive.write(first, "app-1.0", DistArchive.tree(image, ""), TIME);
        DistArchive.write(second, "app-1.0", DistArchive.tree(image, ""), TIME);
        assertThat(Files.readAllBytes(first)).isEqualTo(Files.readAllBytes(second));

        Path zipOne = tmp.resolve("one/app.zip");
        Path zipTwo = tmp.resolve("two/app.zip");
        DistArchive.write(zipOne, "app-1.0", DistArchive.tree(image, ""), TIME);
        DistArchive.write(zipTwo, "app-1.0", DistArchive.tree(image, ""), TIME);
        assertThat(Files.readAllBytes(zipOne)).isEqualTo(Files.readAllBytes(zipTwo));
    }

    @Test
    void a_zip_holds_the_jar_and_its_lib_directory_in_name_order_at_one_time() throws IOException {
        Path target = Files.createDirectories(tmp.resolve("target"));
        Path jar = Files.writeString(target.resolve("app.jar"), "jar");
        Path libs = Files.createDirectories(target.resolve("libs"));
        Files.writeString(libs.resolve("okio-3.9.0.jar"), "okio");
        Files.writeString(libs.resolve("guava-33.0.jar"), "guava");
        List<DistArchive.Entry> entries = new ArrayList<>();
        entries.add(DistArchive.file(jar, "app.jar"));
        entries.addAll(DistArchive.tree(libs, "libs/"));
        Path archive = target.resolve("app-1.0.zip");

        DistArchive.write(archive, "app-1.0", entries, DeterministicZip.EPOCH_SECONDS);

        try (ZipFile zip = new ZipFile(archive.toFile())) {
            List<String> names = Collections.list(zip.entries()).stream()
                    .map(ZipEntry::getName)
                    .toList();
            assertThat(names)
                    .containsExactly(
                            "app-1.0/",
                            "app-1.0/app.jar",
                            "app-1.0/libs/",
                            "app-1.0/libs/guava-33.0.jar",
                            "app-1.0/libs/okio-3.9.0.jar");
            LocalDateTime epoch = LocalDateTime.ofEpochSecond(DeterministicZip.EPOCH_SECONDS, 0, ZoneOffset.UTC);
            assertThat(Collections.list(zip.entries()))
                    .extracting(ZipEntry::getTimeLocal)
                    .containsOnly(epoch);
            try (InputStream in = zip.getInputStream(zip.getEntry("app-1.0/libs/okio-3.9.0.jar"))) {
                assertThat(new String(in.readAllBytes(), UTF_8)).isEqualTo("okio");
            }
        }
    }

    @Test
    void a_rewrite_replaces_the_archive_whole_and_leaves_no_staging_file() throws IOException {
        Path image = image();
        Path archive = tmp.resolve("out/app.tar.gz");
        DistArchive.write(archive, "app-1.0", DistArchive.tree(image, ""), TIME);
        Files.writeString(image.resolve("release"), "JAVA_VERSION=26\n");
        DistArchive.write(archive, "app-1.0", DistArchive.tree(image, ""), TIME);

        try (var listing = Files.list(archive.getParent())) {
            assertThat(listing.map(p -> p.getFileName().toString())).containsExactly("app.tar.gz");
        }
    }

    /** A runtime image's shape: launchers under bin/, an executable helper under lib/, plain files. */
    private Path image() throws IOException {
        Path image = Files.createDirectories(tmp.resolve("app-jri"));
        Files.createDirectories(image.resolve("bin"));
        Files.createDirectories(image.resolve("lib"));
        Files.createDirectories(image.resolve("app"));
        Files.writeString(image.resolve("bin/java"), "java");
        Files.writeString(image.resolve("bin/start"), "#!/bin/sh\n");
        Files.writeString(image.resolve("lib/modules"), "jimage");
        Files.writeString(image.resolve("lib/jspawnhelper"), "helper");
        Files.writeString(image.resolve("app/app.jar"), "jar");
        Files.writeString(image.resolve("release"), "JAVA_VERSION=25\n");
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(
                    image.resolve("lib/jspawnhelper"), PosixFilePermissions.fromString("rwxr-xr-x"));
            Files.setPosixFilePermissions(image.resolve("lib/modules"), PosixFilePermissions.fromString("rw-r--r--"));
        }
        return image;
    }

    /** {@code [name, octal mode, mtime]} per entry, read straight from the ustar headers. */
    private static List<String[]> tarEntries(Path archive) throws IOException {
        byte[] tar;
        try (InputStream in = new GZIPInputStream(Files.newInputStream(archive))) {
            tar = in.readAllBytes();
        }
        List<String[]> out = new ArrayList<>();
        for (int pos = 0; pos + 512 <= tar.length; ) {
            String name = field(tar, pos, 100);
            if (name.isEmpty()) break;
            String prefix = field(tar, pos + 345, 155);
            String mode = Integer.toOctalString(Integer.parseInt(field(tar, pos + 100, 8), 8));
            String mtime = Long.toString(Long.parseLong(field(tar, pos + 136, 12), 8));
            long size = Long.parseLong(field(tar, pos + 124, 12), 8);
            out.add(new String[] {prefix.isEmpty() ? name : prefix + "/" + name, mode, mtime});
            pos += 512 + (int) ((size + 511) / 512) * 512;
        }
        return out;
    }

    private static String field(byte[] tar, int at, int len) {
        int end = at;
        while (end < at + len && tar[end] != 0) end++;
        return new String(tar, at, end - at, UTF_8);
    }
}
