// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JkDirsTest {

    @Test
    void jk_home_config_is_only_the_config_root_never_the_umbrella_root(@TempDir Path home) throws Exception {
        // No old-layout support: a stray $JK_HOME/config.toml is not a config file, even when
        // $JK_HOME/config/config.toml does not exist.
        Map<String, String> env = Map.of("JK_HOME", home.toString());
        JkDirs dirs = JkDirs.of(env::get, "/home/me", "Linux");

        Files.writeString(home.resolve("config.toml"), "# stray\n");
        assertThat(dirs.userConfigFilePath()).isEqualTo(home.resolve("config/config.toml"));
    }

    @Test
    void linux_xdg_defaults() {
        JkDirs dirs = JkDirs.of(Map.<String, String>of()::get, "/home/me", "Linux");
        assertThat(dirs.dataDir()).isEqualTo(Path.of("/home/me/.local/share/jk"));
        assertThat(dirs.userConfigFilePath()).isEqualTo(Path.of("/home/me/.config/jk/config.toml"));
        assertThat(dirs.cacheDir()).isEqualTo(Path.of("/home/me/.cache/jk"));
        assertThat(dirs.stateDir()).isEqualTo(Path.of("/home/me/.local/state/jk"));
        assertThat(dirs.binDirectory()).isEqualTo(Path.of("/home/me/.local/bin"));
        assertThat(dirs.storeDir()).isEqualTo(Path.of("/home/me/.local/share/jk/store"));
        assertThat(dirs.libraryRegistryFile())
                .isEqualTo(Path.of("/home/me/.local/share/jk/store").resolve(JkDirs.LIBRARY_REGISTRY_FILE));
        assertThat(dirs.templatesDir())
                .isEqualTo(Path.of("/home/me/.local/share/jk/store").resolve(JkDirs.TEMPLATES_DIR));
        assertThat(dirs.productLibDir()).isEqualTo(Path.of("/home/me/.local/share/jk/lib"));
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
    void windows_jk_home_umbrella_mirrors_the_xdg_shape() {
        // install.ps1 resolves the same roots by hand and cannot be run here; this is that contract.
        Path jkHome = Path.of("C:\\opt\\jk");
        JkDirs dirs = JkDirs.of(Map.of("JK_HOME", jkHome.toString())::get, "C:\\Users\\me", "Windows 11");
        assertThat(dirs.dataDir()).isEqualTo(jkHome.resolve("data"));
        assertThat(dirs.cacheDir()).isEqualTo(jkHome.resolve("cache"));
        assertThat(dirs.stateDir()).isEqualTo(jkHome.resolve("state"));
        assertThat(dirs.configDir()).isEqualTo(jkHome.resolve("config"));
        assertThat(dirs.binDirectory()).isEqualTo(jkHome.resolve("bin"));
        assertThat(dirs.storeDir()).isEqualTo(jkHome.resolve("data").resolve("store"));
        assertThat(dirs.libraryRegistryFile())
                .isEqualTo(jkHome.resolve("data").resolve("store").resolve(JkDirs.LIBRARY_REGISTRY_FILE));
        assertThat(dirs.templatesDir())
                .isEqualTo(jkHome.resolve("data").resolve("store").resolve(JkDirs.TEMPLATES_DIR));
        assertThat(dirs.productLibDir()).isEqualTo(jkHome.resolve("data").resolve("lib"));
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
    void jk_home_relocates_the_five_roots_but_not_jdks() {
        Map<String, String> env = Map.of("JK_HOME", "/opt/jk");
        JkDirs dirs = JkDirs.of(env::get, "/home/me", "Linux");
        assertThat(dirs.binDirectory()).isEqualTo(Path.of("/opt/jk/bin"));
        assertThat(dirs.cacheDir()).isEqualTo(Path.of("/opt/jk/cache"));
        assertThat(dirs.configDir()).isEqualTo(Path.of("/opt/jk/config"));
        assertThat(dirs.dataDir()).isEqualTo(Path.of("/opt/jk/data"));
        assertThat(dirs.stateDir()).isEqualTo(Path.of("/opt/jk/state"));
        // Shared IntelliJ root — not $JK_HOME/jdks
        assertThat(dirs.jdksDir()).isEqualTo(Path.of("/home/me/.jdks"));
    }

    @Test
    void jk_home_derives_everything_else_exactly_as_xdg_does() {
        JkDirs xdg = JkDirs.of(Map.<String, String>of()::get, "/home/me", "Linux");
        JkDirs umbrella = JkDirs.of(Map.of("JK_HOME", "/opt/jk")::get, "/home/me", "Linux");

        // <data>/store, <data>/lib, <config>/config.toml, <state>/builds, <state>/tmp
        assertThat(umbrella.storeDir()).isEqualTo(Path.of("/opt/jk/data/store"));
        assertThat(umbrella.libraryRegistryFile())
                .isEqualTo(Path.of("/opt/jk/data/store").resolve(JkDirs.LIBRARY_REGISTRY_FILE));
        assertThat(umbrella.templatesDir())
                .isEqualTo(Path.of("/opt/jk/data/store").resolve(JkDirs.TEMPLATES_DIR));
        assertThat(umbrella.productLibDir()).isEqualTo(Path.of("/opt/jk/data/lib"));
        assertThat(umbrella.userConfigFilePath()).isEqualTo(Path.of("/opt/jk/config/config.toml"));
        assertThat(umbrella.buildsDir()).isEqualTo(Path.of("/opt/jk/state/builds"));
        assertThat(umbrella.tmpDir()).isEqualTo(Path.of("/opt/jk/state/tmp"));

        // Same derivation both ways: each path is its root plus an identical relative tail.
        assertThat(umbrella.dataDir().relativize(umbrella.storeDir()))
                .isEqualTo(xdg.dataDir().relativize(xdg.storeDir()));
        assertThat(umbrella.dataDir().relativize(umbrella.productLibDir()))
                .isEqualTo(xdg.dataDir().relativize(xdg.productLibDir()));
        assertThat(umbrella.storeDir().relativize(umbrella.libraryRegistryFile()))
                .isEqualTo(xdg.storeDir().relativize(xdg.libraryRegistryFile()));
        assertThat(umbrella.storeDir().relativize(umbrella.templatesDir()))
                .isEqualTo(xdg.storeDir().relativize(xdg.templatesDir()));
        assertThat(umbrella.stateDir().relativize(umbrella.buildsDir()))
                .isEqualTo(xdg.stateDir().relativize(xdg.buildsDir()));
        assertThat(umbrella.stateDir().relativize(umbrella.tmpDir()))
                .isEqualTo(xdg.stateDir().relativize(xdg.tmpDir()));
        assertThat(umbrella.configDir().relativize(umbrella.userConfigFilePath()))
                .isEqualTo(xdg.configDir().relativize(xdg.userConfigFilePath()));
    }

    /**
     * Provisioned build tools are artifacts, not cache. They lived under the cache root until the
     * retention pass — which deletes every top-level cache entry its table does not name, and the
     * table never named {@code tools} — started reclaiming an 83 MB Kotlin distribution an hour
     * after it landed. The relationship, not the spelling, is what must not regress: the tools root
     * is under the store and shares no prefix with the cache.
     */
    @Test
    void provisioned_tools_live_under_the_store_and_never_under_the_cache() {
        Map<String, String> env = Map.of("JK_HOME", "/opt/jk");
        JkDirs dirs = JkDirs.of(env::get, "/home/me", "Linux");

        assertThat(dirs.toolsDir()).isEqualTo(dirs.storeDir().resolve("tools"));
        assertThat(dirs.toolsDir().startsWith(dirs.storeDir())).isTrue();
        assertThat(dirs.toolsDir().startsWith(dirs.cacheDir()))
                .as("the cache sweep reclaims unknown top-level entries; tools must be out of its reach")
                .isFalse();
    }

    /**
     * {@code JK_CACHE_DIR} isolates the action cache. It must not move a fetched distribution,
     * which is the coupling that put seven Groovy jars and a Kotlin compiler inside the sweep's
     * scope in the first place.
     */
    @Test
    void jk_cache_dir_does_not_move_the_tools_root() {
        JkDirs plain = JkDirs.of(Map.of("JK_HOME", "/opt/jk")::get, "/home/me", "Linux");
        JkDirs relocated = JkDirs.of(
                Map.of("JK_HOME", "/opt/jk", "JK_CACHE_DIR", "/tmp/isolated-cache")::get, "/home/me", "Linux");

        assertThat(relocated.cacheDir()).isNotEqualTo(plain.cacheDir());
        assertThat(relocated.toolsDir()).isEqualTo(plain.toolsDir());
    }

    @Test
    void jk_store_dir_moves_the_tools_root() {
        JkDirs dirs = JkDirs.of(Map.of("JK_STORE_DIR", "/srv/artifacts")::get, "/home/me", "Linux");
        assertThat(dirs.toolsDir()).isEqualTo(Path.of("/srv/artifacts/tools"));
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
                "JK_JDKS_DIR", "/opt/jdks");
        JkDirs dirs = JkDirs.of(env::get, "/home/me", "Linux");
        assertThat(dirs.userConfigFilePath()).isEqualTo(Path.of("/etc/jk-config.toml"));
        assertThat(dirs.cacheDir()).isEqualTo(Path.of("/var/cache/jk"));
        assertThat(dirs.storeDir()).isEqualTo(Path.of("/var/lib/jk/store"));
        assertThat(dirs.libraryRegistryFile())
                .isEqualTo(Path.of("/var/lib/jk/store").resolve(JkDirs.LIBRARY_REGISTRY_FILE));
        assertThat(dirs.templatesDir()).isEqualTo(Path.of("/var/lib/jk/store").resolve(JkDirs.TEMPLATES_DIR));
        assertThat(dirs.stateDir()).isEqualTo(Path.of("/var/lib/jk/state"));
        assertThat(dirs.dataDir()).isEqualTo(Path.of("/var/lib/jk/data"));
        assertThat(dirs.binDirectory()).isEqualTo(Path.of("/usr/local/bin"));
        assertThat(dirs.jdksDir()).isEqualTo(Path.of("/opt/jdks"));
    }

    @Test
    void store_children_follow_the_store_when_only_store_is_overridden() {
        Map<String, String> env = Map.of("JK_STORE_DIR", "/data/jk-store");
        JkDirs dirs = JkDirs.of(env::get, "/home/me", "Linux");
        assertThat(dirs.storeDir()).isEqualTo(Path.of("/data/jk-store"));
        assertThat(dirs.libraryRegistryFile())
                .isEqualTo(Path.of("/data/jk-store").resolve(JkDirs.LIBRARY_REGISTRY_FILE));
        assertThat(dirs.templatesDir()).isEqualTo(Path.of("/data/jk-store").resolve(JkDirs.TEMPLATES_DIR));
    }

    @Test
    void blank_env_values_are_ignored() {
        Map<String, String> env = new HashMap<>();
        env.put("JK_HOME", "  ");
        env.put("JK_CACHE_DIR", "");
        JkDirs dirs = JkDirs.of(env::get, "/home/me", "Linux");
        assertThat(dirs.dataDir()).isEqualTo(Path.of("/home/me/.local/share/jk"));
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
