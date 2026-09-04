// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class JkDirsTest {

    private static final Map<String, String> NO_ENV = Map.of();

    private static JkDirs linux(Map<String, String> env) {
        return JkDirs.of(env::get, "/home/me", "Linux");
    }

    @Test
    void every_root_hangs_off_the_home_dir() {
        JkDirs dirs = linux(NO_ENV);
        Path home = Path.of("/home/me/.jk");

        assertThat(dirs.homeDir()).isEqualTo(home);
        assertThat(dirs.binDirectory()).isEqualTo(home.resolve("bin"));
        assertThat(dirs.cacheDir()).isEqualTo(home.resolve("cache"));
        assertThat(dirs.configDir()).isEqualTo(home.resolve("config"));
        assertThat(dirs.credsDir()).isEqualTo(home.resolve("creds"));
        assertThat(dirs.productLibDir()).isEqualTo(home.resolve("lib"));
        assertThat(dirs.stateDir()).isEqualTo(home.resolve("state"));
        assertThat(dirs.storeDir()).isEqualTo(home.resolve("store"));
        assertThat(dirs.userConfigFilePath()).isEqualTo(home.resolve("config.toml"));
    }

    @Test
    void derived_paths_hang_off_their_root() {
        JkDirs dirs = linux(NO_ENV);
        assertThat(dirs.libraryRegistryFile()).isEqualTo(dirs.storeDir().resolve(JkDirs.LIBRARY_REGISTRY_FILE));
        assertThat(dirs.templatesDir()).isEqualTo(dirs.storeDir().resolve(JkDirs.TEMPLATES_DIR));
        assertThat(dirs.toolsDir()).isEqualTo(dirs.storeDir().resolve(JkDirs.TOOLS_DIR));
        assertThat(dirs.buildsDir()).isEqualTo(dirs.stateDir().resolve("builds"));
        assertThat(dirs.tmpDir()).isEqualTo(dirs.stateDir().resolve("tmp"));
    }

    /**
     * The point of the whole layout: the answer to "where is my cache" is one sentence, not a table
     * with a column per platform. Only {@link JkDirs#jdksDir()} may differ, and it has its own test.
     */
    @Test
    void the_layout_is_identical_on_every_platform() {
        for (String os : List.of("Linux", "Mac OS X", "Windows 11")) {
            JkDirs dirs = JkDirs.of(NO_ENV::get, "/home/me", os);
            assertThat(List.of(
                            dirs.homeDir(),
                            dirs.binDirectory(),
                            dirs.cacheDir(),
                            dirs.configDir(),
                            dirs.credsDir(),
                            dirs.productLibDir(),
                            dirs.stateDir(),
                            dirs.storeDir(),
                            dirs.userConfigFilePath()))
                    .as("layout on %s", os)
                    .isEqualTo(List.of(
                            Path.of("/home/me/.jk"),
                            Path.of("/home/me/.jk/bin"),
                            Path.of("/home/me/.jk/cache"),
                            Path.of("/home/me/.jk/config"),
                            Path.of("/home/me/.jk/creds"),
                            Path.of("/home/me/.jk/lib"),
                            Path.of("/home/me/.jk/state"),
                            Path.of("/home/me/.jk/store"),
                            Path.of("/home/me/.jk/config.toml")));
        }
    }

    /** Nothing may reintroduce a platform read for jk's own directories. */
    @Test
    void no_platform_or_xdg_variable_is_consulted() {
        Map<String, String> env = new HashMap<>(Map.of(
                "XDG_CONFIG_HOME", "/x/config",
                "XDG_CACHE_HOME", "/x/cache",
                "XDG_STATE_HOME", "/x/state",
                "XDG_DATA_HOME", "/x/data",
                "XDG_BIN_HOME", "/x/bin",
                "LOCALAPPDATA", "D:\\Local",
                "APPDATA", "D:\\Roaming"));
        JkDirs polluted = JkDirs.of(env::get, "/home/me", "Windows 11");
        JkDirs clean = JkDirs.of(NO_ENV::get, "/home/me", "Windows 11");

        assertThat(polluted.cacheDir()).isEqualTo(clean.cacheDir());
        assertThat(polluted.configDir()).isEqualTo(clean.configDir());
        assertThat(polluted.stateDir()).isEqualTo(clean.stateDir());
        assertThat(polluted.storeDir()).isEqualTo(clean.storeDir());
        assertThat(polluted.binDirectory()).isEqualTo(clean.binDirectory());
        assertThat(polluted.userConfigFilePath()).isEqualTo(clean.userConfigFilePath());
    }

    /** A resolver reads only the five names it documents. */
    @Test
    void only_five_environment_names_are_read() {
        List<String> seen = new ArrayList<>();
        Function<String, @Nullable String> recording = name -> {
            seen.add(name);
            return null;
        };
        JkDirs dirs = JkDirs.of(recording, "/home/me", "Linux");
        dirs.homeDir();
        dirs.binDirectory();
        dirs.cacheDir();
        dirs.configDir();
        dirs.credsDir();
        dirs.productLibDir();
        dirs.stateDir();
        dirs.storeDir();
        dirs.userConfigFilePath();
        dirs.buildsDir();
        dirs.tmpDir();
        dirs.toolsDir();
        dirs.templatesDir();
        dirs.libraryRegistryFile();
        dirs.jdksDir();

        assertThat(seen).containsOnly("JK_HOME", "JK_CACHE_DIR", "JK_STATE_DIR", "JK_STORE_DIR", "JK_JDKS_DIR");
    }

    @Test
    void jk_home_relocates_every_root_but_not_jdks() {
        JkDirs dirs = linux(Map.of("JK_HOME", "/opt/jk"));
        assertThat(dirs.binDirectory()).isEqualTo(Path.of("/opt/jk/bin"));
        assertThat(dirs.cacheDir()).isEqualTo(Path.of("/opt/jk/cache"));
        assertThat(dirs.configDir()).isEqualTo(Path.of("/opt/jk/config"));
        assertThat(dirs.credsDir()).isEqualTo(Path.of("/opt/jk/creds"));
        assertThat(dirs.productLibDir()).isEqualTo(Path.of("/opt/jk/lib"));
        assertThat(dirs.stateDir()).isEqualTo(Path.of("/opt/jk/state"));
        assertThat(dirs.storeDir()).isEqualTo(Path.of("/opt/jk/store"));
        assertThat(dirs.userConfigFilePath()).isEqualTo(Path.of("/opt/jk/config.toml"));
        // Shared IntelliJ root — not $JK_HOME/jdks
        assertThat(dirs.jdksDir()).isEqualTo(Path.of("/home/me/.jdks"));
    }

    @Test
    void the_global_config_file_sits_at_the_home_root_not_inside_the_config_dir() {
        JkDirs dirs = linux(Map.of("JK_HOME", "/opt/jk"));
        assertThat(dirs.userConfigFilePath()).isEqualTo(Path.of("/opt/jk/config.toml"));
        assertThat(dirs.userConfigFilePath().startsWith(dirs.configDir())).isFalse();
    }

    /**
     * Credentials are a root, not a corner of the store. That is what makes
     * {@code jk self nuke --store} unable to log the user out: the tokens are not in the tree it
     * deletes, so no exclusion has to be remembered.
     */
    @Test
    void creds_is_a_sibling_of_store_and_of_every_other_root() {
        JkDirs dirs = linux(Map.of("JK_HOME", "/opt/jk"));
        assertThat(dirs.credsDir().startsWith(dirs.storeDir())).isFalse();
        assertThat(dirs.credsDir().startsWith(dirs.cacheDir())).isFalse();
        assertThat(dirs.credsDir().startsWith(dirs.stateDir())).isFalse();
        assertThat(dirs.credsDir().startsWith(dirs.configDir())).isFalse();
        assertThat(dirs.credsDir().getParent()).isEqualTo(dirs.homeDir());
    }

    /** A relocated store must not drag credentials onto the same volume. */
    @Test
    void jk_store_dir_does_not_move_creds() {
        JkDirs plain = linux(Map.of("JK_HOME", "/opt/jk"));
        JkDirs relocated = linux(Map.of("JK_HOME", "/opt/jk", "JK_STORE_DIR", "/srv/artifacts"));
        assertThat(relocated.storeDir()).isEqualTo(Path.of("/srv/artifacts"));
        assertThat(relocated.credsDir()).isEqualTo(plain.credsDir());
    }

    @Test
    void macos_puts_managed_jdks_in_the_shared_library_root() {
        JkDirs dirs = JkDirs.of(NO_ENV::get, "/Users/me", "Mac OS X");
        assertThat(dirs.jdksDir()).isEqualTo(Path.of("/Users/me/Library/Java/JavaVirtualMachines"));
    }

    @Test
    void linux_and_windows_put_managed_jdks_in_the_shared_intellij_root() {
        assertThat(JkDirs.of(NO_ENV::get, "/home/me", "Linux").jdksDir()).isEqualTo(Path.of("/home/me/.jdks"));
        Path winHome = Path.of("C:\\Users\\me");
        assertThat(JkDirs.of(NO_ENV::get, winHome.toString(), "Windows 11").jdksDir())
                .isEqualTo(winHome.resolve(".jdks"));
    }

    @Test
    void jk_jdks_dir_required_for_a_hermetic_jdk_root_under_jk_home() {
        JkDirs dirs = linux(Map.of("JK_HOME", "/tmp/cold", "JK_JDKS_DIR", "/tmp/cold/jdks"));
        assertThat(dirs.jdksDir()).isEqualTo(Path.of("/tmp/cold/jdks"));
    }

    @Test
    void the_three_large_roots_win_over_jk_home() {
        Map<String, String> env = Map.of(
                "JK_HOME", "/opt/jk",
                "JK_CACHE_DIR", "/var/cache/jk",
                "JK_STORE_DIR", "/var/lib/jk/store",
                "JK_STATE_DIR", "/var/lib/jk/state");
        JkDirs dirs = linux(env);
        assertThat(dirs.cacheDir()).isEqualTo(Path.of("/var/cache/jk"));
        assertThat(dirs.storeDir()).isEqualTo(Path.of("/var/lib/jk/store"));
        assertThat(dirs.stateDir()).isEqualTo(Path.of("/var/lib/jk/state"));
        // Roots without an override still follow JK_HOME.
        assertThat(dirs.binDirectory()).isEqualTo(Path.of("/opt/jk/bin"));
        assertThat(dirs.credsDir()).isEqualTo(Path.of("/opt/jk/creds"));
        assertThat(dirs.productLibDir()).isEqualTo(Path.of("/opt/jk/lib"));
    }

    @Test
    void store_children_follow_the_store_when_only_store_is_overridden() {
        JkDirs dirs = linux(Map.of("JK_STORE_DIR", "/data/jk-store"));
        assertThat(dirs.libraryRegistryFile())
                .isEqualTo(Path.of("/data/jk-store").resolve(JkDirs.LIBRARY_REGISTRY_FILE));
        assertThat(dirs.templatesDir()).isEqualTo(Path.of("/data/jk-store").resolve(JkDirs.TEMPLATES_DIR));
        assertThat(dirs.toolsDir()).isEqualTo(Path.of("/data/jk-store").resolve(JkDirs.TOOLS_DIR));
    }

    @Test
    void state_children_follow_the_state_root_when_only_state_is_overridden() {
        JkDirs dirs = linux(Map.of("JK_STATE_DIR", "/run/jk-state"));
        assertThat(dirs.buildsDir()).isEqualTo(Path.of("/run/jk-state/builds"));
        assertThat(dirs.tmpDir()).isEqualTo(Path.of("/run/jk-state/tmp"));
    }

    /**
     * Provisioned build tools are artifacts, not cache. They lived under the cache root until the
     * retention pass — which deletes every top-level cache entry its table does not name, and the
     * table never named {@code tools} — started reclaiming an 83 MB Kotlin distribution an hour
     * after it landed. The relationship, not the spelling, is what must not regress.
     */
    @Test
    void provisioned_tools_live_under_the_store_and_never_under_the_cache() {
        JkDirs dirs = linux(Map.of("JK_HOME", "/opt/jk"));
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
        JkDirs plain = linux(Map.of("JK_HOME", "/opt/jk"));
        JkDirs relocated = linux(Map.of("JK_HOME", "/opt/jk", "JK_CACHE_DIR", "/tmp/isolated-cache"));
        assertThat(relocated.cacheDir()).isNotEqualTo(plain.cacheDir());
        assertThat(relocated.toolsDir()).isEqualTo(plain.toolsDir());
    }

    @Test
    void blank_env_values_are_ignored() {
        Map<String, String> env = new HashMap<>();
        env.put("JK_HOME", "  ");
        env.put("JK_CACHE_DIR", "");
        JkDirs dirs = linux(env);
        assertThat(dirs.homeDir()).isEqualTo(Path.of("/home/me/.jk"));
        assertThat(dirs.cacheDir()).isEqualTo(Path.of("/home/me/.jk/cache"));
    }

    @Test
    void every_root_override_must_be_absolute() {
        assertThatThrownBy(() -> linux(Map.of("JK_HOME", "relative")).homeDir())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> linux(Map.of("JK_CACHE_DIR", "relative")).cacheDir())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> linux(Map.of("JK_STATE_DIR", "relative")).stateDir())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> linux(Map.of("JK_STORE_DIR", "relative")).storeDir())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> linux(Map.of("JK_JDKS_DIR", "relative")).jdksDir())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
