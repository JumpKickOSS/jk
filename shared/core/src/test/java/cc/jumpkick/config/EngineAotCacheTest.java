// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A fresh engine maps the recorded cache when there is one, records when told to, and otherwise starts plain. */
class EngineAotCacheTest {

    @Test
    void a_recorded_cache_is_mapped_and_a_missing_one_is_not(@TempDir Path dir) throws Exception {
        Path jdk = jdk(dir.resolve("jdk"), "25.0.4", "Eclipse Adoptium");
        Path jar = Files.writeString(dir.resolve("jk-engine.jar"), "jar");
        Path state = dir.resolve("state");

        assertThat(EngineAotCache.spawnFlags(state, jdk, jar, Map.<String, String>of()::get))
                .as("nothing recorded yet")
                .isEmpty();

        Path cache = Objects.requireNonNull(EngineAotCache.cacheFile(state, jdk, jar));
        Files.createDirectories(cache.getParent());
        Files.writeString(cache, "aot");

        assertThat(EngineAotCache.spawnFlags(state, jdk, jar, Map.<String, String>of()::get))
                .containsExactly("-XX:AOTCache=" + cache, "-Xlog:aot=off");
        assertThat(EngineAotCache.spawnFlags(state, jdk, jar, Map.of(EngineAotCache.ENV, "off")::get))
                .isEmpty();
    }

    @Test
    void a_trainer_records_instead_of_mapping(@TempDir Path dir) throws Exception {
        Path jdk = jdk(dir.resolve("jdk"), "25", "Oracle Corporation");
        Path jar = Files.writeString(dir.resolve("jk-engine.jar"), "jar");
        Path out = dir.resolve("engine.aot");

        assertThat(EngineAotCache.spawnFlags(dir, jdk, jar, Map.of(EngineAotCache.RECORD_ENV, out.toString())::get))
                .containsExactly("-XX:AOTCacheOutput=" + out, "-Xlog:aot=error");
    }

    @Test
    void a_jar_rebuilt_in_place_is_a_new_cache(@TempDir Path dir) throws Exception {
        Path jdk = jdk(dir.resolve("jdk"), "25", "Eclipse Adoptium");
        Path jar = Files.writeString(dir.resolve("jk-engine.jar"), "one");
        Path before = EngineAotCache.cacheFile(dir, jdk, jar);

        Files.writeString(jar, "two, longer");

        assertThat(EngineAotCache.cacheFile(dir, jdk, jar)).isNotEqualTo(before);
    }

    @Test
    void only_hotspot_25_and_later_gets_a_cache(@TempDir Path dir) throws Exception {
        assertThat(EngineAotCache.eligible(jdk(dir.resolve("a"), "25.0.1", "Eclipse Adoptium")))
                .isTrue();
        assertThat(EngineAotCache.eligible(jdk(dir.resolve("b"), "21.0.5", "Eclipse Adoptium")))
                .isFalse();
        assertThat(EngineAotCache.eligible(jdk(dir.resolve("c"), "25", "GraalVM Community")))
                .isFalse();
        assertThat(EngineAotCache.eligible(dir.resolve("missing"))).isFalse();
    }

    private static Path jdk(Path home, String version, String implementor) throws Exception {
        Files.createDirectories(home);
        Files.writeString(
                home.resolve("release"), "JAVA_VERSION=\"" + version + "\"\nIMPLEMENTOR=\"" + implementor + "\"\n");
        return home;
    }
}
