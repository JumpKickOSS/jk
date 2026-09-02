// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppInstallConfigTest {

    @Test
    void path_under_jk_home_is_config_bin_config_toml(@TempDir Path tmp) {
        JkDirs dirs = JkDirs.of(Map.of("JK_HOME", tmp.resolve("home").toString())::get, tmp.toString());
        assertThat(AppInstallConfig.path(dirs, "myapp")).isEqualTo(tmp.resolve("home/config/myapp/config.toml"));
    }

    @Test
    void path_follows_jk_home(@TempDir Path tmp) {
        JkDirs dirs = JkDirs.of(Map.of("JK_HOME", tmp.resolve("cfg").toString())::get, tmp.toString());
        assertThat(AppInstallConfig.path(dirs, "myapp")).isEqualTo(tmp.resolve("cfg/config/myapp/config.toml"));
    }

    @Test
    void write_read_merge_and_template(@TempDir Path tmp) throws Exception {
        JkDirs dirs = JkDirs.of(Map.of("JK_HOME", tmp.resolve("home").toString())::get, tmp.toString());
        AppInstallConfig.write(dirs, "demo", Map.of("version", "1.0.0", "jar", "demo-1.0.0-all.jar"));
        assertThat(AppInstallConfig.read(dirs, "demo"))
                .containsEntry("version", "1.0.0")
                .containsEntry("jar", "demo-1.0.0-all.jar");

        AppInstallConfig.write(dirs, "demo", Map.of("jar", "demo-1.0.1-all.jar", "extra", "x"));
        assertThat(AppInstallConfig.read(dirs, "demo"))
                .containsEntry("version", "1.0.0")
                .containsEntry("jar", "demo-1.0.1-all.jar")
                .containsEntry("extra", "x");

        String template = "name = \"${name}\"\nnote = \"v=${version}\"\n";
        AppInstallConfig.writeTemplate(dirs, "demo", template, Map.of("name", "demo", "version", "2.0.0"));
        assertThat(AppInstallConfig.read(dirs, "demo"))
                .containsEntry("name", "demo")
                .containsEntry("note", "v=2.0.0")
                .containsEntry("version", "2.0.0");
    }

    @Test
    void jk_config_properties_strip_prefix() {
        Properties p = new Properties();
        p.setProperty("jk-config.version", "0.12.0");
        p.setProperty("jk-config.jar", "jk-engine-0.12.0.jar");
        p.setProperty("unrelated", "nope");
        assertThat(AppInstallConfig.jkConfigProperties(p))
                .containsExactly(Map.entry("version", "0.12.0"), Map.entry("jar", "jk-engine-0.12.0.jar"));
    }

    @Test
    void rejects_path_bin_names() {
        assertThatThrownBy(() -> AppInstallConfig.path("../x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missing_file_reads_empty(@TempDir Path tmp) throws Exception {
        JkDirs dirs = JkDirs.of(
                Map.of("JK_HOME", Files.createDirectories(tmp.resolve("home")).toString())::get, tmp.toString());
        assertThat(AppInstallConfig.read(dirs, "missing")).isEmpty();
    }

    @Test
    void render_round_trips_escapes() {
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("a", "say \"hi\" \\ ok");
        String body = AppInstallConfig.render(keys);
        assertThat(AppInstallConfig.parse(body)).containsEntry("a", "say \"hi\" \\ ok");
    }

    @Test
    void render_round_trips_controls_and_non_ascii() {
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("a", "tab\tnew\nline é");
        String body = AppInstallConfig.render(keys);
        assertThat(AppInstallConfig.parse(body)).containsEntry("a", "tab\tnew\nline é");
    }
}
