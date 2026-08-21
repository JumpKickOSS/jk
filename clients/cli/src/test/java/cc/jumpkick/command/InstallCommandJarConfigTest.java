// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.util.AppInstallConfig;
import cc.jumpkick.util.Hashing;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstallCommandJarConfigTest {

    @Test
    void engine_install_keys_include_digest_of_installed_jar(@TempDir Path tmp) throws Exception {
        Path jar = Files.writeString(tmp.resolve("jk-engine-0.12.0-all.jar"), "engine-bytes-v2");
        Map<String, String> keys = new LinkedHashMap<>();
        InstallCommand.putInstalledJarKeys(keys, jar, EngineInstall.BIN_NAME);
        assertThat(keys)
                .containsEntry("jar", "jk-engine-0.12.0-all.jar")
                .containsEntry("engine-sha256", Hashing.sha256Hex(jar));
    }

    @Test
    void non_engine_install_keys_do_not_set_engine_digest(@TempDir Path tmp) throws Exception {
        Path jar = Files.writeString(tmp.resolve("widget-1.0.0-all.jar"), "app");
        Map<String, String> keys = new LinkedHashMap<>();
        InstallCommand.putInstalledJarKeys(keys, jar, "widget");
        assertThat(keys).containsOnly(Map.entry("jar", "widget-1.0.0-all.jar"));
    }

    @Test
    void writing_engine_keys_overwrites_stale_digest_beside_new_jar_name(@TempDir Path tmp) throws Exception {
        JkDirs dirs = JkDirs.of(Map.of("JK_HOME", tmp.resolve("home").toString())::get, tmp.toString());
        AppInstallConfig.write(
                dirs,
                EngineInstall.BIN_NAME,
                Map.of(
                        "version", "0.12.0",
                        "jar", "jk-engine-0.12.0.jar",
                        "engine-sha256", "deadbeef",
                        "protocol", "1",
                        "name", "jk-engine"));

        Path jar = Files.writeString(tmp.resolve("jk-engine-0.12.0-all.jar"), "fresh-engine-bytes");
        Map<String, String> keys = new LinkedHashMap<>();
        InstallCommand.putInstalledJarKeys(keys, jar, EngineInstall.BIN_NAME);
        keys.putIfAbsent("name", EngineInstall.BIN_NAME);
        keys.putIfAbsent("version", "0.12.0");
        AppInstallConfig.write(dirs, EngineInstall.BIN_NAME, keys);

        assertThat(AppInstallConfig.read(dirs, EngineInstall.BIN_NAME))
                .containsEntry("jar", "jk-engine-0.12.0-all.jar")
                .containsEntry("engine-sha256", Hashing.sha256Hex(jar))
                .containsEntry("version", "0.12.0")
                .containsEntry("protocol", "1")
                .doesNotContainEntry("engine-sha256", "deadbeef");
    }
}
