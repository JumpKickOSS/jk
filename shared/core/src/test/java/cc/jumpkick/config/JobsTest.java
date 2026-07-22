// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class JobsTest {

    @Test
    void effective_null_or_zero_is_cores() {
        assertThat(Jobs.effective(null, () -> 8)).isEqualTo(8);
        assertThat(Jobs.effective(0, () -> 8)).isEqualTo(8);
        assertThat(Jobs.effective(-3, () -> 4)).isEqualTo(4);
    }

    @Test
    void effective_one_is_serial() {
        assertThat(Jobs.effective(1, () -> 16)).isEqualTo(1);
    }

    @Test
    void effective_n_caps() {
        assertThat(Jobs.effective(3, () -> 16)).isEqualTo(3);
        assertThat(Jobs.effective(32, () -> 4)).isEqualTo(32); // user may oversubscribe; HeapPlan may still veto
    }

    @Test
    void resolve_cli_wins_over_env_and_file() {
        JkEngineConfig file = new JkEngineConfig(256, 2);
        Map<String, String> env = Map.of("JK_JOBS", "4");
        assertThat(Jobs.resolve(Optional.of(1), file, env::get)).isEqualTo(1);
        assertThat(Jobs.resolve(Optional.empty(), file, env::get)).isEqualTo(4);
        assertThat(Jobs.resolve(Optional.empty(), file, k -> null)).isEqualTo(2);
    }

    @Test
    void from_toml_jobs() throws Exception {
        var dir = Files.createTempDirectory("jk-jobs");
        var toml = dir.resolve("config.toml");
        Files.writeString(toml, "[engine]\nmax-heap-mb = 256\njobs = 2\n");
        JkEngineConfig c = JkEngineConfig.fromToml(toml);
        assertThat(c.jobs()).isEqualTo(2);
        assertThat(Jobs.effective(c.jobs(), () -> 8)).isEqualTo(2);
    }
}
