// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JkDirsTest {

    @Test
    void linux_xdg_defaults() {
        JkDirs dirs = JkDirs.of(Map.<String, String>of()::get, "/home/me", "Linux");
        assertThat(dirs.homeDir()).isEqualTo(Path.of("/home/me/.local/share/jk"));
        assertThat(dirs.dataDir()).isEqualTo(Path.of("/home/me/.local/share/jk"));
        assertThat(dirs.userConfigFilePath()).isEqualTo(Path.of("/home/me/.config/jk/config.toml"));
        assertThat(dirs.cacheDir()).isEqualTo(Path.of("/home/me/.cache/jk"));
        assertThat(dirs.stateDir()).isEqualTo(Path.of("/home/me/.local/state/jk"));
        assertThat(dirs.binDirectory()).isEqualTo(Path.of("/home/me/.local/bin"));
        assertThat(dirs.storeDir()).isEqualTo(Path.of("/home/me/.local/share/jk/store"));
        assertThat(dirs.libDir()).isEqualTo(Path.of("/home/me/.local/share/jk/store/lib"));
        assertThat(dirs.productLibDir()).isEqualTo(Path.of("/home/me/.local/share/jk/lib"));
        assertThat(dirs.versionsDir()).isEqualTo(Path.of("/home/me/.local/share/jk/versions"));
        assertThat(dirs.jdksDir()).isEqualTo(Path.of("/home/me/.jdks"));
        assertThat(dirs.buildsDir()).isEqualTo(Path.of("/home/me/.local/state/jk/builds"));
        assertThat(dirs.tmpDir()).isEqualTo(Path.of("/home/me/.local/state/jk/tmp"));
    }

    @Test
    void macos_uses_xdg_product_dirs_and_library_jvms_for_jdks() {
        JkDirs dirs = JkDirs.of(Map.<String, String>of()::get, "/Users/me", "Mac OS X");
        assertThat(dirs.dataDir()).isEqualTo(Path.of("/Users/me/.local/share/jk"));
        assertThat(dirs.cacheDir()).isEqualTo(Path.of("/Users/me/.cache/jk"));
        assertThat(dirs.userConfigFilePath()).isEqualTo(Path.of("/Users/me/.config/jk/config.toml"));
        assertThat(dirs.binDirectory()).isEqualTo(Path.of("/Users/me/.local/bin"));
        assertThat(dirs.jdksDir()).isEqualTo(Path.of("/Users/me/Library/Java/JavaVirtualMachines"));
        assertThat(dirs.storeDir()).isEqualTo(Path.of("/Users/me/.local/share/jk/store"));
    }

    @Test
    void windows_known_folder_defaults() {
        // Build expected paths with Path.resolve so host separator matches production.
        Path home = Path.of("C:\\Users\\me");
        JkDirs dirs = JkDirs.of(Map.<String, String>of()::get, home.toString(), "Windows 11");
        Path local = home.resolve("AppData").resolve("Local");
        Path roaming = home.resolve("AppData").resolve("Roaming");
        assertThat(dirs.dataDir()).isEqualTo(local.resolve("jk").resolve("data"));
        assertThat(dirs.cacheDir()).isEqualTo(local.resolve("jk").resolve("cache"));
        assertThat(dirs.stateDir()).isEqualTo(local.resolve("jk").resolve("state"));
        assertThat(dirs.userConfigFilePath()).isEqualTo(roaming.resolve("jk").resolve("config.toml"));
        assertThat(dirs.binDirectory()).isEqualTo(home.resolve(".local").resolve("bin"));
        assertThat(dirs.jdksDir()).isEqualTo(home.resolve(".jdks"));
        assertThat(dirs.storeDir())
                .isEqualTo(local.resolve("jk").resolve("data").resolve("store"));
        assertThat(dirs.productLibDir())
                .isEqualTo(local.resolve("jk").resolve("data").resolve("lib"));
        assertThat(dirs.versionsDir())
                .isEqualTo(local.resolve("jk").resolve("data").resolve("versions"));
    }

    @Test
    void windows_honors_localappdata_and_appdata_env() {
        Path local = Path.of("D:\\Local");
        Path roaming = Path.of("D:\\Roaming");
        Map<String, String> env = Map.of(
                "LOCALAPPDATA", local.toString(),
                "APPDATA", roaming.toString());
        JkDirs dirs = JkDirs.of(env::get, "C:\\Users\\me", "Windows 11");
        assertThat(dirs.dataDir()).isEqualTo(local.resolve("jk").resolve("data"));
        assertThat(dirs.cacheDir()).isEqualTo(local.resolve("jk").resolve("cache"));
        assertThat(dirs.userConfigFilePath()).isEqualTo(roaming.resolve("jk").resolve("config.toml"));
    }

    @Test
    void xdg_env_vars_are_honored_on_unix() {
        Map<String, String> env = Map.of(
                "XDG_CONFIG_HOME", "/x/config",
                "XDG_CACHE_HOME", "/x/cache",
                "XDG_STATE_HOME", "/x/state",
                "XDG_DATA_HOME", "/x/data",
                "XDG_BIN_HOME", "/x/bin");
        JkDirs dirs = JkDirs.of(env::get, "/home/me", "Linux");
        assertThat(dirs.userConfigFilePath()).isEqualTo(Path.of("/x/config/jk/config.toml"));
        assertThat(dirs.cacheDir()).isEqualTo(Path.of("/x/cache/jk"));
        assertThat(dirs.stateDir()).isEqualTo(Path.of("/x/state/jk"));
        assertThat(dirs.dataDir()).isEqualTo(Path.of("/x/data/jk"));
        assertThat(dirs.binDirectory()).isEqualTo(Path.of("/x/bin"));
        assertThat(dirs.storeDir()).isEqualTo(Path.of("/x/data/jk/store"));
    }

    @Test
    void xdg_bin_falls_back_to_sibling_of_data_home() {
        Map<String, String> env = Map.of("XDG_DATA_HOME", "/opt/share");
        JkDirs dirs = JkDirs.of(env::get, "/home/me", "Linux");
        assertThat(dirs.binDirectory()).isEqualTo(Path.of("/opt/bin"));
    }

    @Test
    void jk_home_relocates_product_tree_but_not_jdks() {
        Map<String, String> env = Map.of("JK_HOME", "/opt/jk");
        JkDirs dirs = JkDirs.of(env::get, "/home/me", "Linux");
        assertThat(dirs.homeDir()).isEqualTo(Path.of("/opt/jk"));
        assertThat(dirs.configDir()).isEqualTo(Path.of("/opt/jk/config"));
        assertThat(dirs.userConfigFilePath()).isEqualTo(Path.of("/opt/jk/config/config.toml"));
        assertThat(dirs.cacheDir()).isEqualTo(Path.of("/opt/jk/cache"));
        assertThat(dirs.stateDir()).isEqualTo(Path.of("/opt/jk/state"));
        assertThat(dirs.dataDir()).isEqualTo(Path.of("/opt/jk/data"));
        assertThat(dirs.binDirectory()).isEqualTo(Path.of("/opt/jk/bin"));
        assertThat(dirs.storeDir()).isEqualTo(Path.of("/opt/jk/store"));
        assertThat(dirs.libDir()).isEqualTo(Path.of("/opt/jk/store/lib"));
        assertThat(dirs.productLibDir()).isEqualTo(Path.of("/opt/jk/lib"));
        assertThat(dirs.versionsDir()).isEqualTo(Path.of("/opt/jk/versions"));
        // Shared IntelliJ root — not $JK_HOME/jdks
        assertThat(dirs.jdksDir()).isEqualTo(Path.of("/home/me/.jdks"));
    }

    @Test
    void jk_config_dir_wins_over_jk_home_config_segment() {
        Map<String, String> env = Map.of("JK_HOME", "/opt/jk", "JK_CONFIG_DIR", "/etc/jk-config");
        JkDirs dirs = JkDirs.of(env::get, "/home/me", "Linux");
        assertThat(dirs.configDir()).isEqualTo(Path.of("/etc/jk-config"));
        assertThat(dirs.userConfigFilePath()).isEqualTo(Path.of("/etc/jk-config/config.toml"));
    }

    @Test
    void per_dir_env_vars_win_over_jk_home_and_platform() {
        Map<String, String> env = Map.of(
                "JK_HOME", "/opt/jk",
                "JK_CONFIG_FILE", "/etc/jk-config.toml",
                "JK_CACHE_DIR", "/var/cache/jk",
                "JK_STORE_DIR", "/var/lib/jk/store",
                "JK_STATE_DIR", "/var/lib/jk/state",
                "JK_DATA_DIR", "/var/lib/jk/data",
                "JK_BIN_DIR", "/usr/local/bin",
                "JK_LIB_DIR", "/opt/shared/lib",
                "JK_JDKS_DIR", "/opt/jdks");
        JkDirs dirs = JkDirs.of(env::get, "/home/me", "Linux");
        assertThat(dirs.homeDir()).isEqualTo(Path.of("/opt/jk"));
        assertThat(dirs.userConfigFilePath()).isEqualTo(Path.of("/etc/jk-config.toml"));
        assertThat(dirs.cacheDir()).isEqualTo(Path.of("/var/cache/jk"));
        assertThat(dirs.storeDir()).isEqualTo(Path.of("/var/lib/jk/store"));
        assertThat(dirs.stateDir()).isEqualTo(Path.of("/var/lib/jk/state"));
        assertThat(dirs.dataDir()).isEqualTo(Path.of("/var/lib/jk/data"));
        assertThat(dirs.binDirectory()).isEqualTo(Path.of("/usr/local/bin"));
        assertThat(dirs.libDir()).isEqualTo(Path.of("/opt/shared/lib"));
        assertThat(dirs.jdksDir()).isEqualTo(Path.of("/opt/jdks"));
    }

    @Test
    void lib_follows_store_when_only_store_is_overridden() {
        Map<String, String> env = Map.of("JK_STORE_DIR", "/data/jk-store");
        JkDirs dirs = JkDirs.of(env::get, "/home/me", "Linux");
        assertThat(dirs.storeDir()).isEqualTo(Path.of("/data/jk-store"));
        assertThat(dirs.libDir()).isEqualTo(Path.of("/data/jk-store/lib"));
    }

    @Test
    void blank_env_values_are_ignored() {
        Map<String, String> env = new HashMap<>();
        env.put("JK_HOME", "  ");
        env.put("JK_CACHE_DIR", "");
        JkDirs dirs = JkDirs.of(env::get, "/home/me", "Linux");
        assertThat(dirs.homeDir()).isEqualTo(Path.of("/home/me/.local/share/jk"));
        assertThat(dirs.cacheDir()).isEqualTo(Path.of("/home/me/.cache/jk"));
    }

    @Test
    void jk_jdks_dir_required_for_hermetic_jdk_root_under_jk_home() {
        Map<String, String> env = Map.of(
                "JK_HOME", "/tmp/cold",
                "JK_JDKS_DIR", "/tmp/cold/jdks");
        JkDirs dirs = JkDirs.of(env::get, "/home/me", "Linux");
        assertThat(dirs.jdksDir()).isEqualTo(Path.of("/tmp/cold/jdks"));
    }
}
