// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.TimeZone;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The archive contract every jk packager inherits: identical inputs produce byte-identical
 * archives, whatever the build host's default timezone is.
 */
class DeterministicZipTest {

    /** 19 hours apart, neither observing DST: no wall clock reading can agree between them. */
    private static final TimeZone TOKYO = TimeZone.getTimeZone("Asia/Tokyo");

    private static final TimeZone HONOLULU = TimeZone.getTimeZone("Pacific/Honolulu");

    private static final LocalDateTime PINNED_INSTANT = LocalDateTime.of(1980, 2, 1, 0, 0);

    private TimeZone ambient;

    @BeforeEach
    void captureDefaultZone() {
        ambient = TimeZone.getDefault();
    }

    @AfterEach
    void restoreDefaultZone() {
        TimeZone.setDefault(ambient);
    }

    /**
     * The harness has teeth: {@link ZipEntry#setTime}'s DOS conversion reads
     * {@link ZoneId#systemDefault()}, so if these two zones agreed on the wall clock the
     * byte-equality assertion below would hold no matter how entries were stamped.
     */
    @Test
    void the_two_zones_disagree_about_the_pinned_instant() {
        assertThat(wallClockOf(TOKYO)).isNotEqualTo(wallClockOf(HONOLULU));
    }

    @Test
    void every_entry_shape_carries_the_pinned_instant_whatever_the_zone(@TempDir Path tmp) throws IOException {
        Path file = Files.writeString(tmp.resolve("payload.txt"), "payload");

        byte[] tokyo = mixedArchiveUnder(TOKYO, file);
        byte[] honolulu = mixedArchiveUnder(HONOLULU, file);

        assertThat(tokyo)
                .as("an archive must not depend on the build host's $TZ")
                .isEqualTo(honolulu);
        assertThat(entryTimes(tokyo)).hasSize(8).allSatisfy(time -> assertThat(time)
                .isEqualTo(PINNED_INSTANT));
    }

    @Test
    void a_build_supplied_epoch_is_honoured_and_pre_1980_is_clamped() throws IOException {
        long epoch = 1_000_000_000L; // 2001-09-09T01:46:40Z
        assertThat(entryTimes(oneEntry(new DeterministicZip(epoch))))
                .containsExactly(LocalDateTime.ofEpochSecond(epoch, 0, ZoneOffset.UTC));
        // DOS time cannot hold anything before 1980; epoch 0 would cost an 18-byte extra field.
        assertThat(entryTimes(oneEntry(new DeterministicZip(0L)))).containsExactly(PINNED_INSTANT);
    }

    @Test
    void a_stored_entry_carries_its_own_size_and_crc(@TempDir Path tmp) throws IOException {
        Path file = Files.writeString(tmp.resolve("lib.jar"), "nested");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(out)) {
            DeterministicZip.PINNED.writeStored(jos, "BOOT-INF/lib/lib.jar", file);
        }
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            ZipEntry entry = in.getNextEntry();
            assertThat(entry.getMethod()).isEqualTo(ZipEntry.STORED);
            assertThat(entry.getSize()).isEqualTo(Files.size(file));
            assertThat(in.readAllBytes()).isEqualTo(Files.readAllBytes(file));
        }
    }

    @Test
    void parent_dirs_are_emitted_parents_first_and_only_once() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(out)) {
            HashSet<String> dirs = new HashSet<>();
            DeterministicZip.PINNED.writeParentDirs(jos, "META-INF/services/a.Provider", dirs);
            DeterministicZip.PINNED.writeParentDirs(jos, "META-INF/services/b.Provider", dirs);
        }
        assertThat(entryNames(out.toByteArray())).containsExactly("META-INF/", "META-INF/services/");
    }

    /** One archive exercising every writer the packagers use, built under {@code zone}. */
    private static byte[] mixedArchiveUnder(TimeZone zone, Path file) throws IOException {
        TimeZone.setDefault(zone);
        DeterministicZip zip = DeterministicZip.PINNED;
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(out)) {
            zip.writeManifest(jos, manifest);
            zip.writeParentDirs(jos, "com/example/App.class", new HashSet<>());
            zip.writeEntry(jos, "com/example/App.class", "class".getBytes(UTF_8));
            zip.writeEntry(jos, "resources.arsc", "arsc".getBytes(UTF_8), ZipEntry.STORED);
            zip.writeEntry(jos, "payload.txt", file);
            zip.writeEntryStreaming(jos, "streamed.txt", Files.newInputStream(file));
            zip.writeStored(jos, "BOOT-INF/lib/lib.jar", file);
        }
        return out.toByteArray();
    }

    private static byte[] oneEntry(DeterministicZip zip) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(out)) {
            zip.writeEntry(jos, "a.txt", "alpha".getBytes(UTF_8));
        }
        return out.toByteArray();
    }

    private static List<LocalDateTime> entryTimes(byte[] archive) throws IOException {
        List<LocalDateTime> times = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                times.add(e.getTimeLocal());
            }
        }
        return times;
    }

    private static List<String> entryNames(byte[] archive) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                names.add(e.getName());
            }
        }
        return names;
    }

    private static LocalDateTime wallClockOf(TimeZone zone) {
        TimeZone.setDefault(zone);
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(DeterministicZip.EPOCH_SECONDS), ZoneId.systemDefault());
    }
}
