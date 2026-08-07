// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.util.AotManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The relatime-style throttle on the cache-hit manifest touch (JK-1429): a hit must not pay a
 * full {@code aot.toml} lock/read/rewrite per worker fork just to refresh {@code last_used}.
 */
class PluginAotRecordUseTest {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC);

    @TempDir
    Path tmp;

    private static PluginAot.CacheMeta meta(String tool, String key) {
        PluginAot.JdkId id = new PluginAot.JdkId(Path.of("/opt/jdk"), cc.jumpkick.jdk.JdkVendor.TEMURIN, "25.0.3");
        return new PluginAot.CacheMeta(tool, key, id, "serialgc", "a.jar", List.of());
    }

    private Path cacheFile(String name) throws IOException {
        Path cache = tmp.resolve(name);
        Files.writeString(cache, "aot-bytes");
        return cache;
    }

    @Test
    void second_immediate_hit_does_not_rewrite_the_manifest() throws IOException {
        Path cache = cacheFile("kotlinc-0000000000000001.aot");
        PluginAot.recordUse(cache, meta("kotlinc", "0000000000000001"));
        Path manifest = AotManifest.path(tmp);
        assertThat(manifest).exists();

        // A rewrite would regenerate the file; a sentinel proves no write happened at all.
        Files.writeString(manifest, "# sentinel — must survive the second hit\n");
        PluginAot.recordUse(cache, meta("kotlinc", "0000000000000001"));
        assertThat(Files.readString(manifest)).isEqualTo("# sentinel — must survive the second hit\n");
    }

    @Test
    void first_hit_with_a_fresh_manifest_last_used_skips_the_rewrite() throws IOException {
        // Simulates a fresh engine process (empty in-JVM throttle) hitting a cache whose
        // manifest row was refreshed under an hour ago by a previous engine.
        Path cache = cacheFile("kotlinc-0000000000000002.aot");
        AotManifest.upsert(
                tmp,
                AotManifest.Entry.builder(cache.getFileName().toString())
                        .tool("kotlinc")
                        .key("0000000000000002")
                        .status("ready")
                        .lastUsed(AotManifest.nowIso())
                        .build());
        byte[] before = Files.readAllBytes(AotManifest.path(tmp));

        PluginAot.recordUse(cache, meta("kotlinc", "0000000000000002"));
        assertThat(Files.readAllBytes(AotManifest.path(tmp))).isEqualTo(before);
    }

    @Test
    void a_stale_last_used_is_refreshed_on_hit() throws IOException {
        Path cache = cacheFile("kotlinc-0000000000000003.aot");
        String twoHoursAgo = ISO.format(Instant.now().minusSeconds(2 * 60 * 60));
        AotManifest.upsert(
                tmp,
                AotManifest.Entry.builder(cache.getFileName().toString())
                        .tool("kotlinc")
                        .key("0000000000000003")
                        .status("ready")
                        .lastUsed(twoHoursAgo)
                        .build());

        PluginAot.recordUse(cache, meta("kotlinc", "0000000000000003"));

        AotManifest.Entry row = AotManifest.load(tmp).stream()
                .filter(e -> e.file().equals(cache.getFileName().toString()))
                .findFirst()
                .orElseThrow();
        assertThat(row.status()).isEqualTo("ready");
        Instant refreshed = OffsetDateTime.parse(row.lastUsed()).toInstant();
        assertThat(refreshed).isAfter(OffsetDateTime.parse(twoHoursAgo).toInstant());
    }
}
