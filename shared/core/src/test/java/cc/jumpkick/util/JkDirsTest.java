// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.testing.ShortTempDirs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class JkDirsTest {

    private static final Map<String, String> NO_ENV = Map.of();

    private static JkDirs linux(Map<String, String> env) {
        return JkDirs.of(env::get, "/home/me", "Linux");
    }

    /**
     * Host-absolute override fixture under {@link ShortTempDirs#path()} ({@code ~/.jk-test-tmp}).
     * Never {@code /opt} or a drive root.
     */
    private static Path tmp(String name) {
        return ShortTempDirs.path().resolve(name);
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
        assertThat(dirs.toolEnvsDir()).isEqualTo(dirs.stateDir().resolve("tools/envs"));
        assertThat(JkDirs.toolEnvsDir(Path.of("/elsewhere"))).isEqualTo(Path.of("/elsewhere/tools/envs"));
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
        dirs.toolEnvsDir();
        dirs.toolsDir();
        dirs.templatesDir();
        dirs.libraryRegistryFile();
        dirs.jdksDir();

        assertThat(seen).containsOnly("JK_HOME", "JK_CACHE_DIR", "JK_STATE_DIR", "JK_STORE_DIR", "JK_JDKS_DIR");
    }

    @Test
    void jk_home_relocates_every_root_but_not_jdks() {
        Path home = tmp("jk-home");
        JkDirs dirs = linux(Map.of("JK_HOME", home.toString()));
        assertThat(dirs.binDirectory()).isEqualTo(home.resolve("bin"));
        assertThat(dirs.cacheDir()).isEqualTo(home.resolve("cache"));
        assertThat(dirs.configDir()).isEqualTo(home.resolve("config"));
        assertThat(dirs.credsDir()).isEqualTo(home.resolve("creds"));
        assertThat(dirs.productLibDir()).isEqualTo(home.resolve("lib"));
        assertThat(dirs.stateDir()).isEqualTo(home.resolve("state"));
        assertThat(dirs.storeDir()).isEqualTo(home.resolve("store"));
        assertThat(dirs.userConfigFilePath()).isEqualTo(home.resolve("config.toml"));
        // Shared IntelliJ root — not $JK_HOME/jdks
        assertThat(dirs.jdksDir()).isEqualTo(Path.of("/home/me/.jdks"));
    }

    @Test
    void the_global_config_file_sits_at_the_home_root_not_inside_the_config_dir() {
        Path home = tmp("jk-home");
        JkDirs dirs = linux(Map.of("JK_HOME", home.toString()));
        assertThat(dirs.userConfigFilePath()).isEqualTo(home.resolve("config.toml"));
        assertThat(dirs.userConfigFilePath().startsWith(dirs.configDir())).isFalse();
    }

    /**
     * Credentials are a root, not a corner of the store. That is what makes
     * {@code jk self nuke --store} unable to log the user out: the tokens are not in the tree it
     * deletes, so no exclusion has to be remembered.
     */
    @Test
    void creds_is_a_sibling_of_store_and_of_every_other_root() {
        JkDirs dirs = linux(Map.of("JK_HOME", tmp("jk-home").toString()));
        assertThat(dirs.credsDir().startsWith(dirs.storeDir())).isFalse();
        assertThat(dirs.credsDir().startsWith(dirs.cacheDir())).isFalse();
        assertThat(dirs.credsDir().startsWith(dirs.stateDir())).isFalse();
        assertThat(dirs.credsDir().startsWith(dirs.configDir())).isFalse();
        assertThat(dirs.credsDir().getParent()).isEqualTo(dirs.homeDir());
    }

    /** A relocated store must not drag credentials onto the same volume. */
    @Test
    void jk_store_dir_does_not_move_creds() {
        Path home = tmp("jk-home");
        Path store = tmp("jk-artifacts");
        JkDirs plain = linux(Map.of("JK_HOME", home.toString()));
        JkDirs relocated = linux(Map.of("JK_HOME", home.toString(), "JK_STORE_DIR", store.toString()));
        assertThat(relocated.storeDir()).isEqualTo(store);
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
        Path cold = tmp("cold");
        JkDirs dirs = linux(Map.of(
                "JK_HOME", cold.toString(), "JK_JDKS_DIR", cold.resolve("jdks").toString()));
        assertThat(dirs.jdksDir()).isEqualTo(cold.resolve("jdks"));
    }

    @Test
    void the_three_large_roots_win_over_jk_home() {
        Path home = tmp("jk-home");
        Path cache = tmp("jk-cache");
        Path store = tmp("jk-store");
        Path state = tmp("jk-state");
        Map<String, String> env = Map.of(
                "JK_HOME",
                home.toString(),
                "JK_CACHE_DIR",
                cache.toString(),
                "JK_STORE_DIR",
                store.toString(),
                "JK_STATE_DIR",
                state.toString());
        JkDirs dirs = linux(env);
        assertThat(dirs.cacheDir()).isEqualTo(cache);
        assertThat(dirs.storeDir()).isEqualTo(store);
        assertThat(dirs.stateDir()).isEqualTo(state);
        // Roots without an override still follow JK_HOME.
        assertThat(dirs.binDirectory()).isEqualTo(home.resolve("bin"));
        assertThat(dirs.credsDir()).isEqualTo(home.resolve("creds"));
        assertThat(dirs.productLibDir()).isEqualTo(home.resolve("lib"));
    }

    @Test
    void store_children_follow_the_store_when_only_store_is_overridden() {
        Path store = tmp("jk-store");
        JkDirs dirs = linux(Map.of("JK_STORE_DIR", store.toString()));
        assertThat(dirs.libraryRegistryFile()).isEqualTo(store.resolve(JkDirs.LIBRARY_REGISTRY_FILE));
        assertThat(dirs.templatesDir()).isEqualTo(store.resolve(JkDirs.TEMPLATES_DIR));
        assertThat(dirs.toolsDir()).isEqualTo(store.resolve(JkDirs.TOOLS_DIR));
    }

    @Test
    void state_children_follow_the_state_root_when_only_state_is_overridden() {
        Path state = tmp("jk-state");
        JkDirs dirs = linux(Map.of("JK_STATE_DIR", state.toString()));
        assertThat(dirs.buildsDir()).isEqualTo(state.resolve("builds"));
        assertThat(dirs.tmpDir()).isEqualTo(state.resolve("tmp"));
        assertThat(dirs.toolEnvsDir()).isEqualTo(state.resolve("tools/envs"));
    }

    /**
     * Provisioned build tools are artifacts, not cache. They lived under the cache root until the
     * retention pass — which deletes every top-level cache entry its table does not name, and the
     * table never named {@code tools} — started reclaiming an 83 MB Kotlin distribution an hour
     * after it landed. The relationship, not the spelling, is what must not regress.
     */
    @Test
    void provisioned_tools_live_under_the_store_and_never_under_the_cache() {
        JkDirs dirs = linux(Map.of("JK_HOME", tmp("jk-home").toString()));
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
        Path home = tmp("jk-home");
        JkDirs plain = linux(Map.of("JK_HOME", home.toString()));
        JkDirs relocated = linux(Map.of(
                "JK_HOME",
                home.toString(),
                "JK_CACHE_DIR",
                tmp("isolated-cache").toString()));
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

    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rwx------");

    /** The engine socket is trusted on these directories' modes alone, so they are never left to the umask. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void securing_the_roots_creates_the_home_and_state_owner_only(@TempDir Path tmp) throws Exception {
        Path home = tmp.resolve("jk");
        JkDirs dirs = linux(Map.of("JK_HOME", home.toString()));

        dirs.secureRoots();

        assertThat(Files.getPosixFilePermissions(home)).isEqualTo(OWNER_ONLY);
        assertThat(Files.getPosixFilePermissions(home.resolve("state"))).isEqualTo(OWNER_ONLY);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void securing_the_roots_tightens_a_pre_existing_755_home_and_leaves_the_store_alone(@TempDir Path tmp)
            throws Exception {
        Path home = tmp.resolve("jk");
        Set<PosixFilePermission> loose = PosixFilePermissions.fromString("rwxr-xr-x");
        for (Path dir : List.of(home, home.resolve("state"), home.resolve("store"))) {
            Files.createDirectories(dir);
            Files.setPosixFilePermissions(dir, loose);
        }
        JkDirs dirs = linux(Map.of("JK_HOME", home.toString()));

        dirs.secureRoots();

        assertThat(Files.getPosixFilePermissions(home)).isEqualTo(OWNER_ONLY);
        assertThat(Files.getPosixFilePermissions(home.resolve("state"))).isEqualTo(OWNER_ONLY);
        assertThat(Files.getPosixFilePermissions(home.resolve("store")))
                .as("the store holds nothing secret")
                .isEqualTo(loose);
    }

    // ---- the overlay over the environment ----------------------------------

    private static JkDirs layered(Map<String, String> overlay, Map<String, String> env) {
        return JkDirs.of(overlay::get, env::get, "/home/me");
    }

    /**
     * The incident this pins: a test overlays its throwaway home while the shell that started the
     * gate exports {@code JK_STORE_DIR} for the real one. The store, cache and state of an overlay
     * home are the overlay's — the environment's overrides describe another home and never reach in.
     */
    @Test
    void an_overlay_home_takes_no_root_from_the_environment() {
        Path overlayHome = tmp("jk-overlay-home");
        Path sentinel = tmp("jk-shell-store");
        JkDirs dirs = layered(
                Map.of("JK_HOME", overlayHome.toString()),
                Map.of(
                        "JK_HOME", tmp("jk-shell-home").toString(),
                        "JK_STORE_DIR", sentinel.toString(),
                        "JK_CACHE_DIR", tmp("jk-shell-cache").toString(),
                        "JK_STATE_DIR", tmp("jk-shell-state").toString()));

        assertThat(dirs.homeDir()).isEqualTo(overlayHome);
        assertThat(dirs.storeDir()).isEqualTo(overlayHome.resolve("store")).isNotEqualTo(sentinel);
        assertThat(dirs.cacheDir()).isEqualTo(overlayHome.resolve("cache"));
        assertThat(dirs.stateDir()).isEqualTo(overlayHome.resolve("state"));
    }

    @Test
    void an_overlay_root_relocates_its_root_under_an_overlay_home() {
        Path overlayHome = tmp("jk-overlay-home");
        Path overlayStore = tmp("jk-overlay-store");
        JkDirs dirs = layered(
                Map.of("JK_HOME", overlayHome.toString(), "JK_STORE_DIR", overlayStore.toString()),
                Map.of("JK_STORE_DIR", tmp("jk-shell-store").toString()));

        assertThat(dirs.storeDir()).isEqualTo(overlayStore);
        assertThat(dirs.stateDir()).isEqualTo(overlayHome.resolve("state"));
    }

    /** The per-method isolation of one root: an overlay root over an environment home. */
    @Test
    void an_overlay_root_alone_relocates_that_root_under_the_environments_home() {
        Path shellHome = tmp("jk-shell-home");
        Path overlayState = tmp("jk-overlay-state");
        JkDirs dirs = layered(
                Map.of("JK_STATE_DIR", overlayState.toString()),
                Map.of(
                        "JK_HOME",
                        shellHome.toString(),
                        "JK_STORE_DIR",
                        tmp("jk-shell-store").toString()));

        assertThat(dirs.homeDir()).isEqualTo(shellHome);
        assertThat(dirs.stateDir()).isEqualTo(overlayState);
        assertThat(dirs.storeDir())
                .as("the environment's store still belongs to the environment's home")
                .isEqualTo(tmp("jk-shell-store"));
    }

    /** {@code JK_HOME=/scratch JK_STORE_DIR=~/.jk/store} in one shell keeps sharing the store. */
    @Test
    void an_environment_home_keeps_the_environments_root_overrides() {
        Path shellHome = tmp("jk-shell-home");
        Path shared = tmp("jk-shared-store");
        JkDirs dirs = layered(Map.of(), Map.of("JK_HOME", shellHome.toString(), "JK_STORE_DIR", shared.toString()));

        assertThat(dirs.homeDir()).isEqualTo(shellHome);
        assertThat(dirs.storeDir()).isEqualTo(shared);
    }

    @Test
    void the_managed_jdk_root_reads_the_overlay_over_the_environment() {
        Path overlayJdks = tmp("jk-overlay-jdks");
        assertThat(layered(Map.of("JK_JDKS_DIR", overlayJdks.toString()), Map.of("JK_JDKS_DIR", tmp("x").toString()))
                        .jdksDir())
                .isEqualTo(overlayJdks);
        assertThat(layered(Map.of(), Map.of("JK_JDKS_DIR", tmp("jk-shell-jdks").toString()))
                        .jdksDir())
                .isEqualTo(tmp("jk-shell-jdks"));
    }
}
