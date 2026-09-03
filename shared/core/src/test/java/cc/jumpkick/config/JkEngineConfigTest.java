// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JkEngineConfigTest {

    @Test
    void missing_file_yields_defaults() {
        assertThat(JkEngineConfig.fromToml(Path.of("/no/such/file"))).isEqualTo(JkEngineConfig.DEFAULTS);
        assertThat(JkEngineConfig.DEFAULTS.maxHeapMb()).isEqualTo(JkEngineConfig.DEFAULT_MAX_HEAP_MB);
    }

    /**
     * Non-CI engine coordinator heap stays 256 MiB (measured ~36 MiB peak on a 200-module build —
     * see docs/perf/engine-heap-monorepo.md). CI defaults to 512 MiB when unset.
     */
    @Test
    void default_max_heap_stays_256_mib_good_neighbor() {
        assertThat(JkEngineConfig.DEFAULT_MAX_HEAP_MB).isEqualTo(256);
        assertThat(JkEngineConfig.DEFAULTS.heapCapped()).isTrue();
        assertThat(JkEngineConfig.DEFAULTS.minHeapMb()).isLessThanOrEqualTo(256);
        assertThat(JkEngineConfig.defaultMaxHeapMb(k -> null)).isEqualTo(256);
    }

    @Test
    void ci_bumps_unset_heap_default_to_512(@TempDir Path tempDir) {
        assertThat(JkEngineConfig.CI_DEFAULT_MAX_HEAP_MB).isEqualTo(512);
        assertThat(JkEngineConfig.defaultMaxHeapMb(Map.of("CI", "1")::get)).isEqualTo(512);
        assertThat(JkEngineConfig.defaultMaxHeapMb(Map.of("CI", "true")::get)).isEqualTo(512);
        assertThat(JkEngineConfig.defaultMaxHeapMb(Map.of("CI", "TRUE")::get)).isEqualTo(512);

        JkEngineConfig c = JkEngineConfig.resolve(tempDir.resolve("none.toml"), Map.of("CI", "true")::get);
        assertThat(c.maxHeapMb()).isEqualTo(512);
    }

    @Test
    void ci_does_not_override_explicit_heap(@TempDir Path tempDir) throws IOException {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, "[engine]\nmax-heap-mb = 256\n");
        JkEngineConfig c = JkEngineConfig.resolve(toml, Map.of("CI", "1")::get);
        assertThat(c.maxHeapMb()).isEqualTo(256);
    }

    @Test
    void table_absent_yields_defaults(@TempDir Path tempDir) throws IOException {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, "group = \"x\"\n");
        assertThat(JkEngineConfig.fromToml(toml)).isEqualTo(JkEngineConfig.DEFAULTS);
    }

    @Test
    void parses_max_heap_mb(@TempDir Path tempDir) throws IOException {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, "[engine]\nmax-heap-mb = 1024\n");
        JkEngineConfig c = JkEngineConfig.fromToml(toml);
        assertThat(c.maxHeapMb()).isEqualTo(1024);
        assertThat(c.heapCapped()).isTrue();
    }

    @Test
    void zero_max_heap_means_uncapped(@TempDir Path tempDir) throws IOException {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, "[engine]\nmax-heap-mb = 0\n");
        JkEngineConfig c = JkEngineConfig.fromToml(toml);
        assertThat(c.maxHeapMb()).isZero();
        assertThat(c.heapCapped()).isFalse();
    }

    @Test
    void negative_max_heap_is_invalid_and_falls_back_to_default(@TempDir Path tempDir) throws IOException {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, "[engine]\nmax-heap-mb = -64\n");
        assertThat(JkEngineConfig.fromToml(toml).maxHeapMb()).isEqualTo(JkEngineConfig.DEFAULT_MAX_HEAP_MB);
    }

    @Test
    void vfs_max_mb_defaults_to_32_and_ci_does_not_bump(@TempDir Path tempDir) {
        assertThat(JkEngineConfig.DEFAULT_VFS_MAX_MB).isEqualTo(32);
        assertThat(JkEngineConfig.DEFAULTS.vfsMaxMb()).isEqualTo(32);
        assertThat(JkEngineConfig.resolve(tempDir.resolve("none.toml"), Map.of("CI", "1")::get)
                        .vfsMaxMb())
                .isEqualTo(32);
    }

    @Test
    void vfs_max_mb_zero_means_off(@TempDir Path tempDir) throws IOException {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, "[engine]\nvfs-max-mb = 0\n");
        assertThat(JkEngineConfig.fromToml(toml).vfsMaxMb()).isZero();
    }

    @Test
    void auto_warmup_defaults_on_and_file_can_turn_it_off(@TempDir Path tempDir) throws IOException {
        assertThat(JkEngineConfig.DEFAULTS.autoWarmup()).isTrue();
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, "[engine]\nauto-warmup = false\n");
        assertThat(JkEngineConfig.fromToml(toml).autoWarmup()).isFalse();
        JkEngineConfig envOff = JkEngineConfig.resolve(toml, Map.of("JK_AUTO_WARMUP", "off")::get);
        assertThat(envOff.autoWarmup()).isFalse();
        JkEngineConfig envOn =
                JkEngineConfig.resolve(tempDir.resolve("none.toml"), Map.of("JK_AUTO_WARMUP", "on")::get);
        assertThat(envOn.autoWarmup()).isTrue();
    }

    @Test
    void vfs_max_mb_env_overrides_file(@TempDir Path tempDir) throws IOException {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, "[engine]\nvfs-max-mb = 64\n");
        JkEngineConfig c = JkEngineConfig.resolve(toml, Map.of("JK_ENGINE_VFS_MAX_MB", "16")::get);
        assertThat(c.vfsMaxMb()).isEqualTo(16);
    }

    @Test
    void negative_vfs_max_mb_falls_back_to_default(@TempDir Path tempDir) throws IOException {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, "[engine]\nvfs-max-mb = -8\n");
        assertThat(JkEngineConfig.fromToml(toml).vfsMaxMb()).isEqualTo(JkEngineConfig.DEFAULT_VFS_MAX_MB);
    }

    @Test
    void max_heap_env_overrides_file() throws IOException {
        Path toml = Files.createTempFile("jk-engine-", ".toml");
        try {
            Files.writeString(toml, "[engine]\nmax-heap-mb = 1024\n");
            JkEngineConfig c = JkEngineConfig.resolve(toml, Map.of("JK_ENGINE_MAX_HEAP_MB", "256")::get);
            assertThat(c.maxHeapMb()).isEqualTo(256);
        } finally {
            Files.deleteIfExists(toml);
        }
    }
}
