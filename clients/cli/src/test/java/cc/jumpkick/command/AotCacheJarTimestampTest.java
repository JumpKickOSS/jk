// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.command.pipeline.AotCachePackage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.jar.Attributes;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk build --aot-cache} rewrites the app jar into {@code target/aot-cache/} with a
 * {@code Class-Path} manifest. That rewrite is an archive jk produces, so it obeys the same rule as
 * every other: entries carry the one pinned instant, 1980-02-01T00:00:00Z, via
 * {@link ZipEntry#setTimeLocal}. Left unpinned, the staged jar changed on every run — which both
 * breaks reproducibility and churns the trained cache's inputs.
 */
class AotCacheJarTimestampTest {

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
     * byte-equality assertion below would hold no matter what the rewrite did.
     */
    @Test
    void the_two_zones_disagree_about_the_pinned_instant() {
        assertThat(wallClockOf(TOKYO)).isNotEqualTo(wallClockOf(HONOLULU));
    }

    @Test
    void rewritten_app_jar_is_pinned_and_timezone_independent(@TempDir Path tmp) throws Exception {
        Path source = sourceJar(tmp.resolve("app-1.0.jar"));

        byte[] tokyo = rewriteUnder(TOKYO, source, tmp.resolve("tokyo.jar"));
        byte[] honolulu = rewriteUnder(HONOLULU, source, tmp.resolve("honolulu.jar"));

        assertThat(tokyo)
                .as("the staged app jar must not depend on the build host's $TZ")
                .isEqualTo(honolulu);
        assertThat(entries(tokyo))
                .as("every entry, manifest included, carries the pinned instant")
                .containsKeys("META-INF/MANIFEST.MF", "com/example/App.class")
                .allSatisfy((name, time) -> assertThat(time).as(name).isEqualTo(PINNED));
        assertThat(manifestOf(tokyo).getMainAttributes().getValue(Attributes.Name.CLASS_PATH))
                .isEqualTo("lib/dep-2.0.jar");
    }

    private static byte[] rewriteUnder(TimeZone zone, Path source, Path out) throws IOException {
        TimeZone.setDefault(zone);
        AotCachePackage.rewriteAppJar(source, out, List.of("dep-2.0.jar"), "com.example.App");
        return Files.readAllBytes(out);
    }

    /** A minimal input jar: a manifest with a Main-Class and one class entry. */
    private static Path sourceJar(Path path) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "com.example.App");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            jar.putNextEntry(new ZipEntry("com/example/App.class"));
            jar.write("class-bytes".getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return path;
    }

    private static Manifest manifestOf(byte[] archive) throws IOException {
        try (var in = new JarInputStream(new ByteArrayInputStream(archive))) {
            return in.getManifest();
        }
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
