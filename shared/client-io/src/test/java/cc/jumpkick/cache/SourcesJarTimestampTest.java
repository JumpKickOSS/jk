// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A published {@code -sources.jar} is a reproducible artifact: the same sources must produce the
 * same bytes on any host. Every entry — the manifest included — carries the one pinned instant,
 * 1980-02-01T00:00:00Z, stamped through {@link ZipEntry#setTimeLocal} rather than {@code setTime},
 * whose DOS-time conversion goes through the JVM's default timezone.
 */
class SourcesJarTimestampTest {

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
     * {@link ZoneId#systemDefault()}, so if these two zones agreed on the wall clock the
     * byte-equality assertion below would hold no matter what {@link SourcesJar} did.
     */
    @Test
    void the_two_zones_disagree_about_the_pinned_instant() {
        assertThat(wallClockOf(TOKYO)).isNotEqualTo(wallClockOf(HONOLULU));
    }

    @Test
    void sources_jar_is_pinned_and_timezone_independent(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        Files.writeString(root.resolve("App.java"), "package com.example; class App {}\n");
        Files.writeString(root.resolve("Util.java"), "package com.example; class Util {}\n");
        List<Path> roots = List.of(tmp.resolve("src/main/java"));

        byte[] tokyo = buildUnder(TOKYO, roots);
        byte[] honolulu = buildUnder(HONOLULU, roots);

        assertThat(tokyo)
                .as("a sources jar must not depend on the build host's $TZ")
                .isEqualTo(honolulu);
        assertThat(entries(tokyo))
                .as("every entry, manifest included, carries the pinned instant")
                .containsKeys("META-INF/MANIFEST.MF", "com/example/App.java", "com/example/Util.java")
                .allSatisfy((name, time) -> assertThat(time).as(name).isEqualTo(PINNED));
    }

    private static byte[] buildUnder(TimeZone zone, List<Path> roots) throws IOException {
        TimeZone.setDefault(zone);
        return SourcesJar.build(roots);
    }

    /** Entry name to stored local timestamp, in encounter order. */
    private static Map<String, LocalDateTime> entries(byte[] archive) throws IOException {
        Map<String, LocalDateTime> found = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                found.put(entry.getName(), entry.getTimeLocal());
            }
        }
        return found;
    }

    private static LocalDateTime wallClockOf(TimeZone zone) {
        TimeZone.setDefault(zone);
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(318_211_200L), ZoneId.systemDefault());
    }
}
