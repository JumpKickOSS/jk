// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.DeterministicZip;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reproducible AAR/APK/AAB output. Every entry the android packagers write must carry the pinned
 * instant — 1980-02-01T00:00:00Z — stamped through {@link ZipEntry#setTimeLocal}. The other
 * spelling, {@code setTime}, converts to DOS time through the JVM's default timezone, so an
 * archive written with it is a function of the build host's {@code $TZ}; an unstamped entry is a
 * function of the wall clock as well.
 *
 * <p>Each case therefore builds its archive twice, under two default timezones 19 hours apart,
 * and demands byte equality — plus the exact pinned value, which byte equality alone would not
 * pin down.
 */
class ArchiveTimestampTest {

    /** The one writer behind every jk archive; the APK/AAB cases drive it the way they do. */
    private static final DeterministicZip ZIP = DeterministicZip.PINNED;

    /** 19 hours apart, neither observing DST: no wall clock reading can agree between them. */
    private static final TimeZone TOKYO = TimeZone.getTimeZone("Asia/Tokyo");

    private static final TimeZone HONOLULU = TimeZone.getTimeZone("Pacific/Honolulu");

    /** Epoch second 318_211_200 — what every jk archive writer pins entries to. */
    private static final LocalDateTime PINNED = LocalDateTime.of(1980, 2, 1, 0, 0);

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
     * {@link ZoneId#systemDefault()}, so if these two zones agreed on the wall clock every
     * byte-equality assertion below would hold no matter what the packagers did.
     */
    @Test
    void the_two_zones_disagree_about_the_pinned_instant() {
        assertThat(wallClockOf(TOKYO)).isNotEqualTo(wallClockOf(HONOLULU));
    }

    @Test
    void aar_classes_jar_is_pinned_and_timezone_independent(@TempDir Path tmp) throws Exception {
        Path classes = Files.createDirectories(tmp.resolve("classes/com/example"));
        Files.writeString(classes.resolve("App.class"), "app");
        Files.writeString(classes.resolve("R.class"), "generated — excluded from the classes jar");

        assertPinned(tmp, "classes.jar", out -> AarPackager.writeClassesJar(tmp.resolve("classes"), out));
    }

    @Test
    void apk_entries_are_pinned_and_timezone_independent(@TempDir Path tmp) throws Exception {
        // The real mix: aapt2's STORED resources.arsc, a DEFLATED dex, a STORED native lib.
        assertPinned(tmp, "app.apk", out -> {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(out))) {
                ZIP.writeEntry(zip, "resources.arsc", "arsc".getBytes(UTF_8), ZipEntry.STORED);
                ZIP.writeEntry(zip, "classes.dex", "dex".getBytes(UTF_8), ZipEntry.DEFLATED);
                ZIP.writeEntry(zip, "lib/arm64-v8a/libjk.so", "so".getBytes(UTF_8), ZipEntry.STORED);
            }
        });
    }

    @Test
    void aab_base_module_entries_are_pinned_and_timezone_independent(@TempDir Path tmp) throws Exception {
        assertPinned(tmp, "base.zip", out -> {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(out))) {
                ZIP.writeEntry(zip, "manifest/AndroidManifest.xml", "proto".getBytes(UTF_8), ZipEntry.STORED);
                ZIP.writeEntry(zip, "resources.pb", "pb".getBytes(UTF_8), ZipEntry.DEFLATED);
                ZIP.writeEntry(zip, "dex/classes.dex", "dex".getBytes(UTF_8), ZipEntry.DEFLATED);
            }
        });
    }

    /** Something that writes one archive to the given path. */
    private interface ArchiveWriter {
        void write(Path out) throws Exception;
    }

    /** Build {@code writer}'s archive under both zones; assert byte equality and the pinned value. */
    private void assertPinned(Path tmp, String name, ArchiveWriter writer) throws Exception {
        byte[] tokyo = buildUnder(TOKYO, tmp.resolve("tokyo-" + name), writer);
        byte[] honolulu = buildUnder(HONOLULU, tmp.resolve("honolulu-" + name), writer);

        assertThat(tokyo).as("%s must not depend on the build host's $TZ", name).isEqualTo(honolulu);
        assertThat(entryTimes(tokyo))
                .as("%s entry timestamps", name)
                .isNotEmpty()
                .allSatisfy(time -> assertThat(time).isEqualTo(PINNED));
    }

    private static byte[] buildUnder(TimeZone zone, Path out, ArchiveWriter writer) throws Exception {
        TimeZone.setDefault(zone);
        writer.write(out);
        return Files.readAllBytes(out);
    }

    /** Every entry's stored local timestamp, in encounter order. */
    private static List<LocalDateTime> entryTimes(byte[] archive) throws IOException {
        List<LocalDateTime> times = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                times.add(entry.getTimeLocal());
            }
        }
        return times;
    }

    private static LocalDateTime wallClockOf(TimeZone zone) {
        TimeZone.setDefault(zone);
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(318_211_200L), ZoneId.systemDefault());
    }
}
