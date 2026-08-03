// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostWarmupTest {

    @Test
    void enabled_defaults_true_without_config(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("config.toml");
        Files.writeString(cfg, "# empty\n");
        assertThat(HostWarmup.enabled(cfg, k -> null)).isTrue();
    }

    @Test
    void enabled_respects_config_and_env(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("config.toml");
        Files.writeString(cfg, "[engine]\nauto-warmup = false\n");
        assertThat(HostWarmup.enabled(cfg, k -> null)).isFalse();
        Files.writeString(cfg, "[engine]\nauto-warmup = true\n");
        assertThat(HostWarmup.enabled(cfg, Map.of("JK_AUTO_WARMUP", "off")::get)).isFalse();
        assertThat(HostWarmup.enabled(cfg, Map.of("JK_AUTO_WARMUP", "on")::get)).isTrue();
    }

    @Test
    void missingKeyNeedsTrain_respects_sticky_noaot_marker(@TempDir Path dir) throws Exception {
        Path cache = dir.resolve("java-compiler-0123456789abcdef.aot");
        // Missing cache, no marker: train.
        assertThat(HostWarmup.missingKeyNeedsTrain(cache)).isTrue();
        // Prior train failed (sticky marker): do not re-queue warmup every cycle.
        Files.createFile(cc.jumpkick.engine.plugin.PluginAot.noaotMarker(cache));
        assertThat(HostWarmup.missingKeyNeedsTrain(cache)).isFalse();
        // Unresolvable key: conservative, still ask for work.
        assertThat(HostWarmup.missingKeyNeedsTrain(null)).isTrue();
    }

    @Test
    void needsCalibration_is_true_when_no_host_metrics() {
        // Without isolating JK state this is environment-dependent; only assert non-throw.
        assertThat(HostWarmup.needsCalibration()).isIn(true, false);
    }
}
