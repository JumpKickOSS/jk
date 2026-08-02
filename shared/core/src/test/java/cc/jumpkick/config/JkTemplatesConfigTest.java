// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tomlj.Toml;

class JkTemplatesConfigTest {

    @Test
    void defaults_point_at_jk_templates() {
        var d = JkTemplatesConfig.defaults();
        assertThat(d.officialUrl()).isEqualTo("https://github.com/jkbuild/jk-templates");
        assertThat(d.sources()).isEmpty();
    }

    @Test
    void parses_official_and_sources_map(@TempDir Path tmp) throws Exception {
        Path cfg = tmp.resolve("config.toml");
        Files.writeString(
                cfg,
                """
                [templates]
                official = "https://github.com/example/official-g8"

                [templates.sources]
                acme = "https://github.com/acme/jk-g8"
                corp = { url = "https://git.example/corp/t.git", rev = "main" }
                """);
        var t = JkTemplatesConfig.resolve(cfg);
        assertThat(t.officialUrl()).isEqualTo("https://github.com/example/official-g8");
        assertThat(t.sources()).hasSize(2);
        assertThat(t.sources().get(0).name()).isEqualTo("acme");
        assertThat(t.sources().get(0).gitRef()).isEqualTo("https://github.com/acme/jk-g8");
        assertThat(t.sources().get(1).name()).isEqualTo("corp");
        assertThat(t.sources().get(1).gitRef()).isEqualTo("https://git.example/corp/t.git#main");
    }

    @Test
    void missing_file_is_defaults(@TempDir Path tmp) {
        assertThat(JkTemplatesConfig.resolve(tmp.resolve("nope.toml"))).isEqualTo(JkTemplatesConfig.defaults());
    }

    @Test
    void fromTomlRoot_empty_templates_table() {
        var root = Toml.parse("[config]\ncolor = \"auto\"\n");
        assertThat(JkTemplatesConfig.fromTomlRoot(root).officialUrl())
                .isEqualTo(JkTemplatesConfig.DEFAULT_OFFICIAL);
    }
}
