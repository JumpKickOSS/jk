// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.node;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NodeCachesTest {

    @TempDir
    Path tmp;

    private Path cache;
    private Path store;
    private Path home;
    private Path project;

    @BeforeEach
    void dirs() throws Exception {
        cache = tmp.resolve("jk/cache");
        store = tmp.resolve("jk/store");
        home = Files.createDirectories(tmp.resolve("home"));
        project = Files.createDirectories(tmp.resolve("work/web"));
    }

    private Map<String, String> passthrough(PackageManager pm, Map<String, String> inherited) {
        return NodeCaches.passthroughEnv(pm, cache, store, project, inherited, home);
    }

    @Test
    void everyManagerGetsNpmsCacheAndOnlyItsOwnKey() {
        String npm = cache.resolve("npm").toString();
        assertThat(NodeCaches.env(PackageManager.NPM, cache, store))
                .containsExactly(Map.entry("npm_config_cache", npm));
        assertThat(NodeCaches.env(PackageManager.YARN, cache, store))
                .containsExactly(
                        Map.entry("npm_config_cache", npm),
                        Map.entry("YARN_GLOBAL_FOLDER", cache.resolve("yarn").toString()));
        assertThat(NodeCaches.env(PackageManager.BUN, cache, store))
                .containsExactly(
                        Map.entry("npm_config_cache", npm),
                        Map.entry("BUN_INSTALL_CACHE_DIR", cache.resolve("bun").toString()));
        String pnpm = store.resolve("pnpm-store").toString();
        assertThat(NodeCaches.env(PackageManager.PNPM, cache, store))
                .containsExactly(
                        Map.entry("npm_config_cache", npm),
                        Map.entry("pnpm_config_store_dir", pnpm),
                        Map.entry("npm_config_store_dir", pnpm));
    }

    @Test
    void passthroughUsesJksCachesWhenTheUserSetNone() {
        for (PackageManager pm : PackageManager.values()) {
            assertThat(passthrough(pm, Map.of("PATH", "/usr/bin"))).isEqualTo(NodeCaches.env(pm, cache, store));
        }
    }

    @Test
    void anEnvironmentVariableInAnyCaseWins() {
        assertThat(passthrough(PackageManager.NPM, Map.of("NPM_CONFIG_CACHE", "/mine")))
                .isEmpty();
        assertThat(passthrough(PackageManager.YARN, Map.of("YARN_GLOBAL_FOLDER", "/mine")))
                .containsOnlyKeys("npm_config_cache");
        assertThat(passthrough(PackageManager.BUN, Map.of("BUN_INSTALL_CACHE_DIR", "/mine")))
                .containsOnlyKeys("npm_config_cache");
        assertThat(passthrough(PackageManager.PNPM, Map.of("npm_config_store_dir", "/mine")))
                .containsOnlyKeys("npm_config_cache");
        assertThat(passthrough(PackageManager.NPM, Map.of("npm_config_cache", " ")))
                .containsOnlyKeys("npm_config_cache");
    }

    @Test
    void aProjectOrParentNpmrcCacheWins() throws Exception {
        Files.writeString(project.resolve(".npmrc"), "; comment\ncache=/mine\n");
        assertThat(passthrough(PackageManager.NPM, Map.of())).isEmpty();
        Files.delete(project.resolve(".npmrc"));
        Files.writeString(tmp.resolve("work/.npmrc"), "registry=https://r.example/\ncache = /mine\n");
        assertThat(passthrough(PackageManager.NPM, Map.of())).isEmpty();
    }

    @Test
    void theUsersNpmrcWinsAndUserconfigNamesIt() throws Exception {
        Files.writeString(home.resolve(".npmrc"), "# cache=/commented\nregistry=https://r.example/\n");
        assertThat(passthrough(PackageManager.NPM, Map.of())).containsOnlyKeys("npm_config_cache");
        Files.writeString(home.resolve(".npmrc"), "cache=/mine\n");
        assertThat(passthrough(PackageManager.NPM, Map.of())).isEmpty();
        Path other = Files.writeString(tmp.resolve("other-npmrc"), "store-dir=/mine\n");
        assertThat(passthrough(PackageManager.PNPM, Map.of("npm_config_userconfig", other.toString())))
                .containsOnlyKeys("npm_config_cache");
    }

    @Test
    void eachManagersOwnConfigFileWins() throws Exception {
        Files.writeString(project.resolve("pnpm-workspace.yaml"), "packages: []\nstoreDir: /mine\n");
        assertThat(passthrough(PackageManager.PNPM, Map.of())).containsOnlyKeys("npm_config_cache");

        Files.writeString(home.resolve(".yarnrc.yml"), "globalFolder: /mine\n");
        assertThat(passthrough(PackageManager.YARN, Map.of())).containsOnlyKeys("npm_config_cache");

        Files.writeString(project.resolve("bunfig.toml"), "[install.cache]\ndir = \"/mine\"\n");
        assertThat(passthrough(PackageManager.BUN, Map.of())).containsOnlyKeys("npm_config_cache");
        Files.writeString(project.resolve("bunfig.toml"), "[install]\ncache = \"/mine\"\n");
        assertThat(passthrough(PackageManager.BUN, Map.of())).containsOnlyKeys("npm_config_cache");
        Files.writeString(project.resolve("bunfig.toml"), "[install]\ncache = false\n");
        assertThat(passthrough(PackageManager.BUN, Map.of())).containsKey("BUN_INSTALL_CACHE_DIR");
    }
}
