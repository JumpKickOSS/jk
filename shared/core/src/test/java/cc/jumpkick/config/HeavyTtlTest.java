// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** How long a heavy output stays cached after its last use: 12 hours, off on CI, never past a day. */
class HeavyTtlTest {

    @Test
    void twelve_hours_by_default_and_off_on_ci(@TempDir Path tmp) {
        Path none = tmp.resolve("config.toml");
        assertThat(HeavyTtl.resolve(none, Map.<String, String>of()::get)).isEqualTo(Duration.ofHours(12));
        assertThat(HeavyTtl.resolve(none, Map.of("CI", "true")::get)).isEqualTo(Duration.ZERO);
        assertThat(HeavyTtl.caches(Duration.ZERO)).isFalse();
    }

    @Test
    void the_file_and_then_the_environment_set_it_and_a_day_is_the_most(@TempDir Path tmp) throws Exception {
        Path config = Files.writeString(tmp.resolve("config.toml"), "[cache]\nheavy-ttl = \"6h\"\n");
        assertThat(HeavyTtl.resolve(config, Map.<String, String>of()::get)).isEqualTo(Duration.ofHours(6));
        assertThat(HeavyTtl.resolve(config, Map.of("CI", "1")::get))
                .as("an explicit setting wins over the CI default")
                .isEqualTo(Duration.ofHours(6));
        assertThat(HeavyTtl.resolve(config, Map.of("JK_HEAVY_TTL", "30m")::get)).isEqualTo(Duration.ofMinutes(30));
        assertThat(HeavyTtl.resolve(config, Map.of("JK_HEAVY_TTL", "3d")::get))
                .as("clamped to a day")
                .isEqualTo(Duration.ofHours(24));
        Path off = Files.writeString(tmp.resolve("off.toml"), "[cache]\nheavy-ttl = \"0\"\n");
        assertThat(HeavyTtl.resolve(off, Map.<String, String>of()::get)).isEqualTo(Duration.ZERO);
    }
}
