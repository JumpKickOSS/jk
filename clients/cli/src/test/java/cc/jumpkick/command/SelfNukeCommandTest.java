// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.command.SelfNukeCommand.Target;
import cc.jumpkick.util.JkDirs;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Uses the suite's isolated {@code JK_HOME} so nuke only touches throwaway trees under the test
 * harness, never the developer's real product dirs.
 */
class SelfNukeCommandTest {

    @Test
    void wipeRoots_never_includes_bin_jdks_product_lib_or_store_lib() throws Exception {
        JkDirs dirs = JkDirs.current();
        Path bin = dirs.binDirectory().toAbsolutePath().normalize();
        Path jdks = dirs.jdksDir().toAbsolutePath().normalize();
        Path productLib = dirs.productLibDir().toAbsolutePath().normalize();
        Path lib = dirs.libDir().toAbsolutePath().normalize();
        Files.createDirectories(productLib);
        Files.createDirectories(lib.resolve("jk-java-compiler"));

        List<Path> roots = SelfNukeCommand.wipeRoots(dirs);
        for (Path r : roots) {
            Path abs = r.toAbsolutePath().normalize();
            assertThat(abs).isNotEqualTo(bin);
            assertThat(abs).isNotEqualTo(jdks);
            assertThat(abs).isNotEqualTo(productLib);
            assertThat(abs).isNotEqualTo(lib);
            assertThat(abs.startsWith(bin)).isFalse();
            assertThat(abs.startsWith(jdks)).isFalse();
            assertThat(abs.startsWith(productLib)).isFalse();
            assertThat(abs.startsWith(lib)).isFalse();
        }
    }

    @Test
    void store_target_is_the_store_root_only_same_as_storage_nuke() throws Exception {
        JkDirs dirs = JkDirs.current();
        Path store = dirs.storeDir().toAbsolutePath().normalize();
        Files.createDirectories(store.resolve("sha256"));
        Files.createDirectories(dirs.libDir());

        List<Path> roots = SelfNukeCommand.wipeRoots(dirs, EnumSet.of(Target.STORE));
        assertThat(roots).containsExactly(store);
    }

    @Test
    @Tag("integration")
    void store_nuke_wipes_cas_including_lib_keeps_engine_jar_and_bin() throws Exception {
        JkDirs dirs = JkDirs.current();
        Path engineJar = dirs.productLibDir().resolve("jk-engine.jar");
        Path cas = dirs.storeDir().resolve("sha256");
        Path lib = dirs.libDir().resolve("jk-java-compiler");
        Path bin = dirs.binDirectory();
        Files.createDirectories(engineJar.getParent());
        Files.writeString(engineJar, "engine");
        Files.createDirectories(cas.resolve("ab"));
        Files.writeString(cas.resolve("ab/blob"), "cas");
        Files.createDirectories(lib);
        Files.writeString(lib.resolve("plugin.jar"), "plugin");
        Files.createDirectories(bin);
        Path foreign = bin.resolve("uv");
        Files.writeString(foreign, "foreign-tool");

        int exit = capture(() -> Jk.execute("self", "nuke", "--store", "-y"));
        assertThat(exit).isZero();
        // storage nuke: entire store, including plugin lib
        assertThat(cas.resolve("ab/blob")).doesNotExist();
        assertThat(lib.resolve("plugin.jar")).doesNotExist();
        // product-lib engine + PATH are not part of the store
        assertThat(engineJar).exists();
        assertThat(foreign).exists();
    }

    @Test
    void cache_only_selects_cache_dir() {
        JkDirs dirs = JkDirs.current();
        List<Path> roots = SelfNukeCommand.wipeRoots(dirs, EnumSet.of(Target.CACHE));
        assertThat(roots).containsExactly(dirs.cacheDir().toAbsolutePath().normalize());
    }

    @Test
    void cache_and_state_nuke_leaves_bin_alone() throws Exception {
        JkDirs dirs = JkDirs.current();
        Path cache = dirs.cacheDir();
        Path state = dirs.stateDir();
        Path bin = dirs.binDirectory();
        Files.createDirectories(cache.resolve("actions"));
        Files.writeString(cache.resolve("actions/marker"), "x");
        Files.createDirectories(state.resolve("aot"));
        Files.writeString(state.resolve("aot/marker"), "y");
        Files.createDirectories(bin);
        Path jkBin = bin.resolve("jk");
        if (!Files.exists(jkBin)) Files.writeString(jkBin, "#!/bin/sh\n");
        Path foreign = bin.resolve("uv");
        Files.writeString(foreign, "foreign-tool");

        int exit = capture(() -> Jk.execute("self", "nuke", "--cache", "--state", "-y"));
        assertThat(exit).isZero();
        assertThat(Files.exists(cache.resolve("actions/marker"))).isFalse();
        assertThat(Files.exists(state.resolve("aot/marker"))).isFalse();
        assertThat(Files.exists(jkBin)).isTrue();
        assertThat(Files.exists(foreign)).isTrue();
    }

    @Test
    void dry_run_cache_only_does_not_delete_or_show_self_table() throws Exception {
        JkDirs dirs = JkDirs.current();
        Path cache = dirs.cacheDir();
        Files.createDirectories(cache.resolve("actions"));
        Path marker = cache.resolve("actions/dry-run-keep");
        Files.writeString(marker, "keep");

        // Single-target --cache uses the same path as `jk cache nuke` (no self plan table).
        String out = Capture.stdout(() -> assertThat(Jk.execute("self", "nuke", "--cache", "--dry-run", "-y"))
                .isZero());
        assertThat(TestAnsi.strip(out)).containsIgnoringCase("dry run");
        assertThat(marker).exists();
        Files.deleteIfExists(marker);
    }

    @Test
    void config_under_jk_home_targets_only_config_file_even_with_outside_bin() throws Exception {
        Path root = Files.createTempDirectory("jk-purge-cfg");
        Path home = root.resolve("home");
        Path outsideBin = root.resolve("outside-bin");
        Files.createDirectories(home.resolve("lib"));
        Files.createDirectories(outsideBin);
        JkDirs dirs = JkDirs.of(
                env("JK_HOME", home.toString(), "JK_BIN_DIR", outsideBin.toString()),
                root.resolve("userhome").toString());

        List<Path> roots = SelfNukeCommand.wipeRoots(dirs, EnumSet.of(Target.CONFIG));
        Path homeAbs = home.toAbsolutePath().normalize();
        assertThat(roots).containsExactly(homeAbs.resolve("config.toml"));
        assertThat(roots).noneMatch(p -> p.equals(homeAbs));
    }

    @Test
    void guard_refuses_rows_that_contain_product_lib_or_store_lib() throws Exception {
        Path root = Files.createTempDirectory("jk-purge-anc");
        Path home = root.resolve("home");
        Files.createDirectories(home.resolve("lib"));
        Files.createDirectories(home.resolve("store").resolve("lib"));
        // JK_STATE_DIR mis-pointed at the umbrella root: state nuke must not take the whole tree.
        JkDirs dirs = JkDirs.of(
                env("JK_HOME", home.toString(), "JK_STATE_DIR", home.toString()),
                root.resolve("userhome").toString());

        List<Path> roots = SelfNukeCommand.wipeRoots(dirs, EnumSet.of(Target.STATE));
        Path homeAbs = home.toAbsolutePath().normalize();
        assertThat(roots).noneMatch(p -> p.equals(homeAbs));
        assertThat(roots).noneMatch(p -> homeAbs.resolve("lib").startsWith(p));
    }

    @Test
    void store_wipe_roots_is_store_dir_for_injected_dirs() throws Exception {
        Path root = Files.createTempDirectory("jk-purge-seam");
        Path home = root.resolve("home");
        Files.createDirectories(home.resolve("store").resolve("lib"));
        Files.createDirectories(home.resolve("store").resolve("sha256"));
        Files.createDirectories(home.resolve("lib"));
        JkDirs dirs = JkDirs.of(
                env("JK_HOME", home.toString()), root.resolve("userhome").toString());

        List<Path> roots = SelfNukeCommand.wipeRoots(dirs, EnumSet.of(Target.STORE));
        Path homeAbs = home.toAbsolutePath().normalize();
        assertThat(roots).containsExactly(homeAbs.resolve("store"));
    }

    @Test
    void symlinked_bin_dir_is_protected_via_realpath() throws Exception {
        Path root = Files.createTempDirectory("jk-purge-link");
        Path home = root.resolve("home");
        Path realBin = home.resolve("data").resolve("bin");
        Path linkBin = root.resolve("linked-bin");
        Files.createDirectories(realBin);
        Files.writeString(realBin.resolve("jk"), "#!/bin/sh\n");
        try {
            Files.createSymbolicLink(linkBin, realBin);
        } catch (UnsupportedOperationException | IOException e) {
            return; // filesystem without symlink support — nothing to verify here
        }
        JkDirs dirs = JkDirs.of(
                env("JK_HOME", home.toString(), "JK_BIN_DIR", linkBin.toString()),
                root.resolve("userhome").toString());

        List<Path> roots = SelfNukeCommand.wipeRoots(dirs, EnumSet.of(Target.STORE));
        Path realBinAbs = realBin.toAbsolutePath().normalize();
        assertThat(roots).noneMatch(p -> p.equals(realBinAbs) || realBinAbs.startsWith(p));
    }

    @Test
    void store_does_not_schedule_credentials_or_data_siblings() throws Exception {
        Path root = Files.createTempDirectory("jk-purge-cred");
        Path userHome = root.resolve("userhome");
        JkDirs dirs = JkDirs.of(env(), userHome.toString());
        Path data = dirs.dataDir();
        Files.createDirectories(data.resolve("credentials"));
        Files.createDirectories(data.resolve("repo-credentials"));
        Files.createDirectories(data.resolve("completions"));
        Files.createDirectories(data.resolve("store").resolve("sha256"));

        List<Path> roots = SelfNukeCommand.wipeRoots(dirs, EnumSet.of(Target.STORE));
        Path dataAbs = data.toAbsolutePath().normalize();
        assertThat(roots).containsExactly(dataAbs.resolve("store"));
        assertThat(roots).doesNotContain(dataAbs.resolve("credentials"));
        assertThat(roots).doesNotContain(dataAbs.resolve("completions"));
    }

    private static Function<String, String> env(String... kv) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) map.put(kv[i], kv[i + 1]);
        return map::get;
    }

    @Test
    void global_yes_works_before_the_command_and_between_group_and_sub() throws Exception {
        JkDirs dirs = JkDirs.current();
        Path cache = dirs.cacheDir();
        Files.createDirectories(cache.resolve("actions"));
        Files.writeString(cache.resolve("actions/pre-yes"), "x");

        int exit = capture(() -> Jk.execute("-y", "self", "nuke", "--cache"));
        assertThat(exit).isZero();
        assertThat(cache.resolve("actions/pre-yes")).doesNotExist();

        Files.createDirectories(cache.resolve("actions"));
        Files.writeString(cache.resolve("actions/mid-yes"), "x");
        // Hidden alias: purge → nuke
        exit = capture(() -> Jk.execute("self", "--yes", "purge", "--cache"));
        assertThat(exit).isZero();
        assertThat(cache.resolve("actions/mid-yes")).doesNotExist();
    }

    @Test
    void declining_the_prompt_deletes_nothing_and_exits_one() throws Exception {
        JkDirs dirs = JkDirs.current();
        Path cache = dirs.cacheDir();
        Files.createDirectories(cache.resolve("actions"));
        Path marker = cache.resolve("actions/decline-keep");
        Files.writeString(marker, "keep");

        InputStream prevIn = System.in;
        try {
            System.setIn(new ByteArrayInputStream(new byte[0]));
            int exit = capture(() -> Jk.execute("self", "nuke", "--cache"));
            assertThat(exit).isEqualTo(1);
        } finally {
            System.setIn(prevIn);
        }
        assertThat(marker).exists();
        Files.deleteIfExists(marker);
    }

    @Test
    void dry_run_without_yes_does_not_prompt_and_exits_zero() throws Exception {
        JkDirs dirs = JkDirs.current();
        Path cache = dirs.cacheDir();
        Files.createDirectories(cache.resolve("actions"));
        Path marker = cache.resolve("actions/dry-run-no-yes-keep");
        Files.writeString(marker, "keep");

        String out = Capture.stdout(() ->
                assertThat(Jk.execute("self", "nuke", "--cache", "--dry-run")).isZero());
        assertThat(TestAnsi.strip(out)).containsIgnoringCase("dry run");
        assertThat(TestAnsi.strip(out)).doesNotContain("Nuke aborted");
        assertThat(marker).exists();
        Files.deleteIfExists(marker);
    }

    @Test
    void displayPath_uses_tilde_under_home() {
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        Path under = home.resolve("cache").resolve("jk");
        assertThat(SelfNukeCommand.displayPath(under)).isEqualTo("~/cache/jk");
    }

    private static int capture(IntSupplier body) {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream cap = new PrintStream(buf, true, StandardCharsets.UTF_8);
        System.setOut(cap);
        System.setErr(cap);
        try {
            return body.getAsInt();
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
    }
}
