// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.util.AppInstallConfig;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstallCommandJarConfigTest {

    @Test
    void install_keys_record_the_jar_basename(@TempDir Path tmp) throws Exception {
        Path jar = Files.writeString(tmp.resolve("widget-1.0.0-all.jar"), "app");
        Map<String, String> keys = new LinkedHashMap<>();
        InstallCommand.putInstalledJarKeys(keys, jar);
        assertThat(keys).containsOnly(Map.entry("jar", "widget-1.0.0-all.jar"));
    }

    @Test
    void writing_install_keys_overwrites_a_stale_jar_name(@TempDir Path tmp) throws Exception {
        JkDirs dirs = JkDirs.of(Map.of("JK_HOME", tmp.resolve("home").toString())::get, tmp.toString());
        AppInstallConfig.write(
                dirs,
                "widget",
                Map.of(
                        "version", "1.0.0",
                        "jar", "widget-1.0.0-all.jar",
                        "name", "widget"));

        Path jar = Files.writeString(tmp.resolve("widget-1.1.0-all.jar"), "fresh");
        Map<String, String> keys = new LinkedHashMap<>();
        InstallCommand.putInstalledJarKeys(keys, jar);
        keys.putIfAbsent("name", "widget");
        keys.putIfAbsent("version", "1.1.0");
        AppInstallConfig.write(dirs, "widget", keys);

        assertThat(AppInstallConfig.read(dirs, "widget"))
                .containsEntry("jar", "widget-1.1.0-all.jar")
                .containsEntry("version", "1.1.0")
                .containsEntry("name", "widget");
    }
}
