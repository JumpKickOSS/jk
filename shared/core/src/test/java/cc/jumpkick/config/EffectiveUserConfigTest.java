// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EffectiveUserConfigTest {

    @Test
    void missing_file_lists_defaults_with_no_overrides(@TempDir Path dir) {
        Path missing = dir.resolve("no-such-config.toml");
        var rows = EffectiveUserConfig.rows(missing, env());
        assertThat(rows).isNotEmpty();
        assertThat(rows).allMatch(r -> !r.overridden());
        assertThat(find(rows, "http.host").defaultValue()).isEqualTo("127.0.0.1");
        assertThat(find(rows, "http.host").effectiveValue()).isEqualTo("127.0.0.1");
        assertThat(find(rows, "cache.max-cache-size-mb").defaultValue()).isEqualTo("1024");
        assertThat(find(rows, "engine.jobs").effectiveValue()).isEqualTo("auto");
    }

    @Test
    void file_overrides_mark_only_changed_keys(@TempDir Path dir) throws Exception {
        Path toml = dir.resolve("config.toml");
        Files.writeString(toml, """
                [global]
                nerdfont = true

                [http]
                port = 9000

                [cache]
                max-cache-size-mb = 2048
                """);
        var rows = EffectiveUserConfig.rows(toml, env());
        assertThat(find(rows, "global.nerdfont").overridden()).isTrue();
        assertThat(find(rows, "global.nerdfont").effectiveValue()).isEqualTo("true");
        assertThat(find(rows, "http.port").overridden()).isTrue();
        assertThat(find(rows, "http.port").effectiveValue()).isEqualTo("9000");
        assertThat(find(rows, "http.host").overridden()).isFalse();
        assertThat(find(rows, "cache.max-cache-size-mb").effectiveValue()).isEqualTo("2048");
        assertThat(find(rows, "cache.max-store-size-mb").overridden()).isFalse();
    }

    @Test
    void env_overrides_file(@TempDir Path dir) throws Exception {
        Path toml = dir.resolve("config.toml");
        Files.writeString(toml, "[http]\nport = 9000\n");
        var rows = EffectiveUserConfig.rows(toml, env("JK_HTTP_PORT", "9100"));
        assertThat(find(rows, "http.port").effectiveValue()).isEqualTo("9100");
        assertThat(find(rows, "http.port").overridden()).isTrue();
    }

    private static EffectiveUserConfig.Row find(List<EffectiveUserConfig.Row> rows, String key) {
        return rows.stream()
                .filter(r -> key.equals(r.key()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing key " + key));
    }

    private static Function<String, String> env(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m::get;
    }
}
